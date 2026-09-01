package com.example.kuwago

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.kuwago.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Kuwago URL Shield — VPN-based enforcement service.
 *
 * Architecture overview
 * ─────────────────────
 * This service operates as a local DNS proxy. It intercepts outgoing DNS
 * queries (UDP port 53) and, for any hostname previously classified as
 * SMISHING by Kuwago's existing scanner, responds with NXDOMAIN to prevent
 * the connection from being established at all.
 *
 * All other traffic (TCP, non-DNS UDP, and Kuwago's own backend traffic) is
 * completely unaffected — the service's VPN interface only routes traffic
 * that is addressed to its own virtual DNS server IP (10.111.222.2/32).
 *
 * Decision pipeline
 * ─────────────────
 *   DNS query arrives for "phishing.xyz"
 *        ↓
 *   UrlReputationCache.get("phishing.xyz")     [in-memory, O(1)]
 *        ↓  cache miss
 *   AnalysisDao.getHostReputation("phishing.xyz")  [encrypted Room DB]
 *        ↓
 *   Classification.SMISHING  →  respond NXDOMAIN + notify user
 *   Classification.SAFE / null  →  forward query to upstream DNS
 *
 * The service does NOT:
 *   - Perform any ML inference.
 *   - Inspect HTTPS content or decrypt TLS traffic.
 *   - Contact Kuwago's backend for every connection.
 *   - Route or relay general TCP/UDP application traffic.
 */
class KuwagoVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.example.kuwago.action.VPN_START"
        const val ACTION_STOP  = "com.example.kuwago.action.VPN_STOP"

        /** Shared-prefs key — whether the VPN is currently considered active. */
        const val PREFS_VPN      = "kuwago_vpn_prefs"
        const val KEY_VPN_ACTIVE = "vpn_active"

        private const val TAG = "KuwagoVpnService"

        // Virtual network addresses used for the tun interface
        private const val VPN_ADDRESS      = "10.111.222.1"   // tun0 IP
        private const val DNS_PROXY_IP     = "10.111.222.2"   // our DNS proxy "server"
        private const val DNS_PROXY_PREFIX = 32               // /32 → only the proxy IP routed

        // Public upstream resolver used when forwarding non-blocked DNS queries.
        // Only DNS queries (not general traffic) are forwarded here.
        private const val UPSTREAM_DNS_HOST = "1.1.1.1"
        private const val UPSTREAM_DNS_PORT = 53
        private const val DNS_TIMEOUT_MS    = 5_000

        private const val NOTIF_ID_ONGOING = 8001
    }

    private var tunFd: ParcelFileDescriptor? = null
    @Volatile private var running = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Pre-parsed bytes for the DNS proxy IP (used when constructing IP responses)
    private val dnsProxyIpBytes = byteArrayOf(10, 111, 222.toByte(), 2)

    // ─────────────────────────────────────────────────────────────────────────
    // Service lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> { stopVpn(); START_NOT_STICKY }
            else        -> { startVpn(); START_STICKY }
        }
    }

    override fun onRevoke() {
        // Called by Android when the user disables the VPN via system settings.
        Log.i(TAG, "VPN revoked by system")
        stopVpn()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VPN setup / teardown
    // ─────────────────────────────────────────────────────────────────────────

    private fun startVpn() {
        if (running) return
        Log.i(TAG, "Starting Kuwago VPN (URL Shield)")

        try {
            val builder = Builder()
                .setSession("Kuwago URL Shield")
                .addAddress(VPN_ADDRESS, 24)
                // Point all DNS through our virtual DNS server
                .addDnsServer(DNS_PROXY_IP)
                // Only route traffic addressed to our virtual DNS server
                // through the tun interface.  All other traffic (web, API, etc.)
                // continues via the normal network stack.
                .addRoute(DNS_PROXY_IP, DNS_PROXY_PREFIX)
                // Exclude Kuwago's own traffic to prevent a routing loop where
                // backend scan requests re-enter the VPN indefinitely.
                .addDisallowedApplication(packageName)

            tunFd = builder.establish()
            if (tunFd == null) {
                Log.e(TAG, "VPN establish() returned null — permission may be missing")
                return
            }

            running = true
            updateActiveState(true)

            // Show the mandatory ongoing foreground notification
            startForeground(NOTIF_ID_ONGOING, buildOngoingNotification())

            // Start the DNS proxy loop on a background thread
            serviceScope.launch { runDnsProxy() }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN", e)
            running = false
        }
    }

    private fun stopVpn() {
        if (!running) return
        Log.i(TAG, "Stopping Kuwago VPN")
        running = false

        try { tunFd?.close() } catch (_: Exception) {}
        tunFd = null

        UrlReputationCache.invalidate()
        updateActiveState(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun updateActiveState(active: Boolean) {
        getSharedPreferences(PREFS_VPN, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_VPN_ACTIVE, active)
            .apply()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DNS proxy loop
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun runDnsProxy() = withContext(Dispatchers.IO) {
        val fd = tunFd?.fileDescriptor ?: return@withContext
        val inputStream  = FileInputStream(fd)
        val outputStream = FileOutputStream(fd)
        val readBuffer   = ByteArray(4096)

        Log.i(TAG, "DNS proxy loop started")

        while (running) {
            val len = try {
                inputStream.read(readBuffer)
            } catch (e: Exception) {
                if (running) Log.w(TAG, "Tun read error: ${e.message}")
                break
            }
            if (len <= 0) continue

            // Copy to avoid aliasing issues when the next read overwrites the buffer
            val rawPacket = readBuffer.copyOf(len)

            // Each packet is handled concurrently so slow DB lookups
            // do not stall subsequent DNS queries
            serviceScope.launch { processPacket(rawPacket, outputStream) }
        }

        Log.i(TAG, "DNS proxy loop ended")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Per-packet processing
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun processPacket(rawPacket: ByteArray, outputStream: FileOutputStream) {
        // Minimum: 20-byte IPv4 header + 8-byte UDP header = 28 bytes
        if (rawPacket.size < 28) return

        // Only handle IPv4
        val ipVersion = (rawPacket[0].toInt() ushr 4) and 0xF
        if (ipVersion != 4) return

        val ipHeaderLen = (rawPacket[0].toInt() and 0xF) * 4

        // Only handle UDP (protocol = 17)
        val protocol = rawPacket[9].toInt() and 0xFF
        if (protocol != 17) return

        val udpOffset = ipHeaderLen
        if (rawPacket.size < udpOffset + 8) return

        // Only handle port 53 (DNS)
        val dstPort = readUint16(rawPacket, udpOffset + 2)
        if (dstPort != 53) return

        val srcPort    = readUint16(rawPacket, udpOffset)
        val srcIpBytes = rawPacket.copyOfRange(12, 16)

        val dnsOffset = udpOffset + 8
        if (rawPacket.size <= dnsOffset) return
        val dnsPayload = rawPacket.copyOfRange(dnsOffset, rawPacket.size)

        // Parse the queried hostname from the DNS question section
        val qname = DnsPacketHelper.extractQname(dnsPayload) ?: return
        val normalizedHost = UrlNormalizer.normalizeHost(qname)

        Log.d(TAG, "DNS query: $qname → normalized: $normalizedHost")

        // Reputation lookup: cache first, then DB
        val classification = if (normalizedHost != null) {
            UrlReputationCache.get(normalizedHost) ?: run {
                val dbResult = lookupFromDatabase(normalizedHost)
                UrlReputationCache.put(normalizedHost, dbResult)
                dbResult
            }
        } else {
            Classification.SAFE
        }

        val responsePayload: ByteArray
        if (classification == Classification.SMISHING) {
            Log.i(TAG, "BLOCKING smishing host: $qname (host=$normalizedHost)")
            responsePayload = DnsPacketHelper.buildNxdomainResponse(dnsPayload)
            notifyBlocked(qname)
        } else {
            // Forward to upstream resolver and relay the response
            responsePayload = forwardDns(dnsPayload) ?: return
        }

        // Build and write the IP/UDP response packet back to the tun interface
        val responsePacket = DnsPacketHelper.buildIpUdpPacket(
            srcIpBytes = dnsProxyIpBytes,
            dstIpBytes = srcIpBytes,
            srcPort    = 53,
            dstPort    = srcPort,
            payload    = responsePayload
        )

        synchronized(outputStream) {
            try {
                outputStream.write(responsePacket)
            } catch (e: Exception) {
                Log.w(TAG, "Tun write error: ${e.message}")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reputation lookup (DB)
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun lookupFromDatabase(host: String): Classification =
        withContext(Dispatchers.IO) {
            try {
                val db     = AppDatabase.getInstance(applicationContext)
                val result = db.analysisDao().getHostReputation(host)
                    ?: return@withContext Classification.SAFE
                try {
                    Classification.valueOf(result.riskLevel)
                } catch (_: IllegalArgumentException) {
                    Classification.SAFE
                }
            } catch (e: Exception) {
                Log.e(TAG, "DB reputation lookup failed for $host", e)
                Classification.SAFE // fail-open: unknown → allow
            }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // DNS forwarding (non-blocked queries)
    // ─────────────────────────────────────────────────────────────────────────

    private fun forwardDns(query: ByteArray): ByteArray? {
        return try {
            val socket = DatagramSocket()
            protect(socket)   // bypass the VPN so the upstream request is not routed back to us
            socket.soTimeout = DNS_TIMEOUT_MS

            val upstream = InetAddress.getByName(UPSTREAM_DNS_HOST)
            socket.send(DatagramPacket(query, query.size, upstream, UPSTREAM_DNS_PORT))

            val buf     = ByteArray(4096)
            val recvPkt = DatagramPacket(buf, buf.size)
            socket.receive(recvPkt)
            socket.close()

            buf.copyOf(recvPkt.length)
        } catch (e: Exception) {
            Log.w(TAG, "DNS forward failed: ${e.message}")
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // User notification when a host is blocked
    // ─────────────────────────────────────────────────────────────────────────

    private fun notifyBlocked(hostname: String) {
        val tapIntent = Intent(this, VpnBlockedActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(VpnBlockedActivity.EXTRA_HOSTNAME, hostname)
        }
        val pi = PendingIntent.getActivity(
            this,
            (hostname.hashCode() and 0x7FFFFFFF) % 1000 + 7000,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, SettingsFragment.CHANNEL_VPN_BLOCK)
            .setSmallIcon(R.drawable.ic_block)
            .setContentTitle("🛡️ Connection Blocked")
            .setContentText("Kuwago blocked $hostname — previously identified as phishing")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Kuwago blocked a connection to $hostname because this site was previously identified as a phishing destination. Tap to learn more.")
            )
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(
                (hostname.hashCode() and 0x7FFFFFFF) % 100 + 7100,
                notif
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post block notification", e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Foreground (ongoing) notification
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildOngoingNotification(): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, SettingsFragment.CHANNEL_VPN_ONGOING)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("Kuwago URL Shield Active")
            .setContentText("Monitoring DNS to block phishing sites")
            .setContentIntent(pi)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utility
    // ─────────────────────────────────────────────────────────────────────────

    /** Reads a big-endian unsigned 16-bit integer from [buf] at [offset]. */
    private fun readUint16(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)
}


// =============================================================================
// DNS packet parsing / building helpers (package-private — kept in same file
// to avoid leaking DNS implementation details into the rest of the codebase).
// =============================================================================

internal object DnsPacketHelper {

    /**
     * Extracts the first QNAME from the DNS question section.
     * DNS wire format: each label is preceded by its byte-length,
     * terminated by a zero byte.
     * Example: `\x03www\x07example\x03com\x00` → "www.example.com"
     */
    fun extractQname(dnsPayload: ByteArray): String? {
        // DNS header is 12 bytes; question section starts at offset 12
        if (dnsPayload.size < 13) return null
        var pos = 12
        val labels = mutableListOf<String>()

        while (pos < dnsPayload.size) {
            val labelLen = dnsPayload[pos].toInt() and 0xFF
            if (labelLen == 0) break
            // Guard against malformed packets
            if (pos + labelLen >= dnsPayload.size) return null
            pos++
            labels.add(String(dnsPayload, pos, labelLen, Charsets.ISO_8859_1))
            pos += labelLen
        }

        return if (labels.isEmpty()) null else labels.joinToString(".")
    }

    /**
     * Builds a minimal NXDOMAIN response from an existing DNS query payload.
     * - Sets QR=1 (response), RA=1 (recursion available), RCODE=3 (NXDOMAIN).
     * - Zeroes answer / authority / additional counts.
     * - Strips any stray answer records (should not be present in a query, but
     *   truncates defensively to just the header + question section).
     */
    fun buildNxdomainResponse(queryPayload: ByteArray): ByteArray {
        if (queryPayload.size < 12) return queryPayload

        val response = queryPayload.copyOf()

        // Byte 2: Flags high — set QR=1 (bit 7)
        response[2] = (response[2].toInt() or 0x80).toByte()
        // Byte 3: Flags low — set RA=1 (bit 7), RCODE=3 (bits 0-3)
        response[3] = 0x83.toByte()

        // Zero out answer / authority / additional counts
        response[6]  = 0; response[7]  = 0   // ANCOUNT
        response[8]  = 0; response[9]  = 0   // NSCOUNT
        response[10] = 0; response[11] = 0   // ARCOUNT

        // Find the end of the question section so we can truncate there
        var pos = 12
        while (pos < response.size) {
            val len = response[pos].toInt() and 0xFF
            if (len == 0) { pos++; break }
            if (pos + len >= response.size) break
            pos += len + 1
        }
        pos += 4  // QTYPE (2) + QCLASS (2)

        return response.copyOf(minOf(pos, response.size))
    }

    /**
     * Constructs a minimal IPv4 / UDP datagram wrapping [payload].
     * UDP checksum is set to zero (valid for IPv4 per RFC 768).
     */
    fun buildIpUdpPacket(
        srcIpBytes: ByteArray,
        dstIpBytes: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpLen   = 8 + payload.size
        val totalLen = 20 + udpLen
        val pkt      = ByteArray(totalLen)

        // IPv4 header (20 bytes, no options)
        pkt[0]  = 0x45.toByte()                         // Version=4, IHL=5
        pkt[1]  = 0                                      // DSCP + ECN
        pkt[2]  = (totalLen ushr 8).toByte()
        pkt[3]  = (totalLen and 0xFF).toByte()
        pkt[4]  = 0; pkt[5] = 0                          // Identification
        pkt[6]  = 0; pkt[7] = 0                          // Flags + Fragment Offset
        pkt[8]  = 64                                     // TTL
        pkt[9]  = 17                                     // Protocol = UDP
        pkt[10] = 0; pkt[11] = 0                         // Checksum (compute below)
        System.arraycopy(srcIpBytes, 0, pkt, 12, 4)
        System.arraycopy(dstIpBytes, 0, pkt, 16, 4)

        // IP header checksum
        val checksum = ipChecksum(pkt, 0, 20)
        pkt[10] = (checksum ushr 8).toByte()
        pkt[11] = (checksum and 0xFF).toByte()

        // UDP header (8 bytes)
        pkt[20] = (srcPort ushr 8).toByte()
        pkt[21] = (srcPort and 0xFF).toByte()
        pkt[22] = (dstPort ushr 8).toByte()
        pkt[23] = (dstPort and 0xFF).toByte()
        pkt[24] = (udpLen ushr 8).toByte()
        pkt[25] = (udpLen and 0xFF).toByte()
        pkt[26] = 0; pkt[27] = 0                         // UDP checksum = 0

        // DNS payload
        System.arraycopy(payload, 0, pkt, 28, payload.size)

        return pkt
    }

    /**
     * Computes the ones-complement checksum over [len] bytes of [buf]
     * starting at [offset], as required by RFC 791 for IPv4 headers.
     */
    private fun ipChecksum(buf: ByteArray, offset: Int, len: Int): Int {
        var sum = 0L
        var i   = offset
        while (i < offset + len - 1) {
            val word = ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            sum += word
            i   += 2
        }
        if ((len and 1) == 1) {
            sum += (buf[offset + len - 1].toInt() and 0xFF) shl 8
        }
        while (sum ushr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return (sum.inv() and 0xFFFF).toInt()
    }
}
