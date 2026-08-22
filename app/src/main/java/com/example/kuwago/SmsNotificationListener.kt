package com.example.kuwago

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class SmsNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())

    // Auto-incrementing IDs for Kuwago's own notifications
    private val notifIdCounter = AtomicInteger(1000)

    // Maps scan-job ID → the captured original notification data (for clone-on-safe flow)
    private data class CapturedNotif(
        val title: String,
        val text: String,
        val originalKey: String,
        val packageName: String,
        val contentIntent: PendingIntent?
    )
    private val pendingClones = ConcurrentHashMap<String, CapturedNotif>()

    companion object {
        var instance: SmsNotificationListener? = null
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i("SmsNotifListener", "=== NOTIFICATION LISTENER CONNECTED & ACTIVE ===")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance == this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            processNotification(sbn)
        } catch (e: Exception) {
            Log.e("SmsNotifListener", "Crash in onNotificationPosted", e)
        }
    }

    private fun processNotification(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        if (packageName == this.packageName) return
        val notification = sbn.notification
        val extras = notification.extras

        val prefs = getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val scanOtherApps = prefs.getBoolean(SettingsFragment.KEY_SCAN_OTHER_APPS, true)
        val scanInstantly = prefs.getBoolean(SettingsFragment.KEY_SCAN_INSTANTLY, false)

        // --- App-filter logic (unchanged) ---
        val isNativeSms = packageName.contains("message", ignoreCase = true) ||
                packageName.contains("sms", ignoreCase = true) ||
                packageName.contains("mms", ignoreCase = true) ||
                packageName.contains("telephony", ignoreCase = true)

        val isOtherMessagingApp = !isNativeSms && (
                packageName.contains("chat", ignoreCase = true) ||
                notification.category == Notification.CATEGORY_MESSAGE
        )

        if (!isNativeSms && !isOtherMessagingApp) return
        if (!scanOtherApps && isOtherMessagingApp) return

        // --- Extract sender + text (unchanged logic) ---
        var extractedSender = "Unknown"
        var extractedText = ""

        try {
            val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
            if (style != null) {
                Log.d("SmsNotifListener", "MessagingStyle detected with ${style.messages.size} messages")
                val lastMessage = style.messages.lastOrNull()
                if (lastMessage != null) {
                    val personName = lastMessage.person?.name?.toString()
                    val personUri = lastMessage.person?.uri
                    val legacySender = lastMessage.sender?.toString()
                    val senderIdentifier = personUri ?: personName ?: legacySender
                    if (!senderIdentifier.isNullOrBlank()) {
                        extractedSender = senderIdentifier.toString()
                        if (extractedSender.startsWith("tel:")) {
                            extractedSender = extractedSender.substring(4)
                        }
                    }
                    val msgText = lastMessage.text?.toString()
                    if (!msgText.isNullOrBlank()) extractedText = msgText
                }
            }
        } catch (e: Exception) {
            Log.w("SmsNotifListener", "Could not parse MessagingStyle", e)
        }

        if (extractedSender == "Unknown") {
            extractedSender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
                ?: "Unknown"
        }

        var text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        if (text.isEmpty()) text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        if (text.isEmpty()) text = extras.getCharSequence("android.text")?.toString() ?: ""

        if (extractedText.isEmpty()) {
            extractedText = text
        } else if (text.isNotEmpty() && text.length > extractedText.length) {
            extractedText = text
        }

        Log.d("SmsNotifListener", "Extracted notification event (length=${extractedText.length})")

        if (extractedText.isEmpty()) {
            Log.d("SmsNotifListener", "Text is empty, returning early")
            return
        }

        // --- Blacklist check (takes priority over everything) ---
        val isSenderBlacklisted = BlacklistRepository.isBlacklisted(this, extractedSender)
        val isTextBlacklisted = extractedText.length < 50 && BlacklistRepository.isBlacklisted(this, extractedText)
        val isBlacklisted = isSenderBlacklisted || isTextBlacklisted

        Log.d("SmsNotifListener", "Blacklist check complete: sender=$isSenderBlacklisted, text=$isTextBlacklisted")

        if (isBlacklisted) {
            Log.i("SmsNotifListener", "Blacklisted notification intercepted — cancelling event")
            cancelNotificationSafely(sbn.key)
            killAllBlacklistedFromApp(packageName)
            for (delay in longArrayOf(200, 600, 1500, 3000)) {
                handler.postDelayed({ killAllBlacklistedFromApp(packageName) }, delay)
            }
            return
        }

        // --- Two-flow scan ---
        if (scanInstantly) {
            // FLOW A: Suppress original → scan → only let safe through
            scanInstantlyFlow(sbn, extractedSender, extractedText)
        } else {
            // FLOW B: Let original through → scan in background → post result notification
            scanPassthroughFlow(sbn, extractedSender, extractedText)
        }
    }

    // -------------------------------------------------------------------------
    // FLOW A — "Scan Incoming Messages Instantly" is ON
    //   1. Cancel the original notification immediately.
    //   2. Post an ongoing "Scanning…" notification.
    //   3. Run the scan.
    //   4. Cancel the scanning notification.
    //   5a. SAFE → re-post a clone of the original.
    //   5b. SUSPICIOUS/SMISHING → post a threat result notification only.
    // -------------------------------------------------------------------------
    private fun scanInstantlyFlow(
        sbn: StatusBarNotification,
        sender: String,
        messageText: String
    ) {
        Log.i("SmsNotifListener", "[INSTANT] Intercepting notification from $sender")

        // Capture the original notification data before we cancel it
        val contentIntent: PendingIntent? = sbn.notification.contentIntent

        val captured = CapturedNotif(
            title = sender,
            text = messageText,
            originalKey = sbn.key,
            packageName = sbn.packageName,
            contentIntent = contentIntent
        )

        // 1. Cancel the original notification immediately
        cancelNotificationSafely(sbn.key)

        // 2. Post a "Scanning…" notification
        val scanNotifId = notifIdCounter.getAndIncrement()
        postScanningNotification(scanNotifId, sender)

        // 3. Run the scan
        scope.launch {
            try {
                val placeholder = DetectionResult(
                    sender = sender,
                    message = messageText,
                    isScanning = true
                )
                DetectionRepository.addDetection(placeholder)

                val finalResult = SmishingDetector.analyze(
                    this@SmsNotificationListener, messageText, sender
                ).copy(id = placeholder.id, sender = sender)

                DetectionRepository.updateDetection(finalResult)

                // 4. Cancel the scanning notification
                cancelOwnNotification(scanNotifId)

                val confidencePct = String.format(
                    java.util.Locale.US, "%.1f%%", finalResult.probability * 100
                )

                // 5a. Safe → re-post a safe clone
                if (finalResult.classification == Classification.SAFE) {
                    Log.i("SmsNotifListener", "[INSTANT] SAFE — re-posting clone for $sender")
                    postSafeCloneNotification(captured, confidencePct)
                } else {
                    // 5b. Threat → post a threat notification
                    Log.i("SmsNotifListener", "[INSTANT] THREAT (${finalResult.classification}) — posting result for $sender")
                    postThreatNotification(sender, finalResult.classification, confidencePct)

                    // Save pending warning for in-app overlay
                    if (!BlacklistRepository.isBlacklisted(this@SmsNotificationListener, sender) &&
                        !BlacklistRepository.isWarningAcknowledged(this@SmsNotificationListener, sender)
                    ) {
                        PendingWarningRepository.savePendingWarning(
                            this@SmsNotificationListener,
                            sender = sender,
                            message = messageText,
                            confidence = confidencePct
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("SmsNotifListener", "[INSTANT] Error during scan", e)
                cancelOwnNotification(scanNotifId)
            }
        }
    }

    // -------------------------------------------------------------------------
    // FLOW B — "Scan Incoming Messages Instantly" is OFF
    //   Original notification passes through untouched.
    //   Kuwago posts an ongoing "Scanning…" notification, then a result notification.
    // -------------------------------------------------------------------------
    private fun scanPassthroughFlow(
        sbn: StatusBarNotification,
        sender: String,
        messageText: String
    ) {
        Log.i("SmsNotifListener", "[PASSTHROUGH] Scanning message from: $sender")

        // Post a "Scanning…" notification
        val scanNotifId = notifIdCounter.getAndIncrement()
        postScanningNotification(scanNotifId, sender)

        scope.launch {
            try {
                val placeholder = DetectionResult(
                    sender = sender,
                    message = messageText,
                    isScanning = true
                )
                DetectionRepository.addDetection(placeholder)

                val finalResult = SmishingDetector.analyze(
                    this@SmsNotificationListener, messageText, sender
                ).copy(id = placeholder.id, sender = sender)

                DetectionRepository.updateDetection(finalResult)

                // Cancel the scanning notification
                cancelOwnNotification(scanNotifId)

                val confidencePct = String.format(
                    java.util.Locale.US, "%.1f%%", finalResult.probability * 100
                )

                // Always post a result notification so the user knows Kuwago checked it
                if (finalResult.classification == Classification.SAFE) {
                    postSafeResultNotification(sender, confidencePct)
                } else {
                    postThreatNotification(sender, finalResult.classification, confidencePct)

                    if (!BlacklistRepository.isBlacklisted(this@SmsNotificationListener, sender) &&
                        !BlacklistRepository.isWarningAcknowledged(this@SmsNotificationListener, sender)
                    ) {
                        PendingWarningRepository.savePendingWarning(
                            this@SmsNotificationListener,
                            sender = sender,
                            message = messageText,
                            confidence = confidencePct
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("SmsNotifListener", "[PASSTHROUGH] Error during scan", e)
                cancelOwnNotification(scanNotifId)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Notification helpers
    // -------------------------------------------------------------------------

    /** Posts an ongoing "Scanning message…" notification on the low-priority channel. */
    private fun postScanningNotification(notifId: Int, sender: String) {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, notifId, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, SettingsFragment.CHANNEL_SCANNING)
            .setSmallIcon(R.drawable.ic_scan)
            .setContentTitle(getString(R.string.notif_scanning_title))
            .setContentText(getString(R.string.notif_scanning_text))
            .setSubText(sender)
            .setOngoing(true)
            .setProgress(0, 0, true)          // indeterminate progress bar
            .setContentIntent(pi)
            .setAutoCancel(false)
            .build()

        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(notifId, notif)
        } catch (e: Exception) {
            Log.e("SmsNotifListener", "Failed to post scanning notification", e)
        }
    }

    /**
     * Posts a safe-result notification in Flow B (passthrough mode).
     * Shows a brief "verified safe" confirmation under the result channel.
     */
    private fun postSafeResultNotification(sender: String, confidencePct: String) {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, notifIdCounter.get(), tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, SettingsFragment.CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.notif_result_safe_title))
            .setContentText(getString(R.string.notif_result_safe_text, sender))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(notifIdCounter.getAndIncrement(), notif)
        } catch (e: Exception) {
            Log.e("SmsNotifListener", "Failed to post safe result notification", e)
        }
    }

    /**
     * In Flow A (scan-instantly), if the message is SAFE we re-post the original message
     * as a Kuwago-branded clone so the user can still read it.
     */
    private fun postSafeCloneNotification(captured: CapturedNotif, confidencePct: String) {
        // Try to use the original contentIntent (opens the messaging app); fall back to Kuwago.
        val tapIntent = captured.contentIntent ?: PendingIntent.getActivity(
            this,
            notifIdCounter.get(),
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, SettingsFragment.CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("✓ ${captured.title}")          // Kuwago-branded safe badge
            .setContentText(captured.text)
            .setSubText(getString(R.string.notif_result_safe_title))
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .build()

        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(notifIdCounter.getAndIncrement(), notif)
        } catch (e: Exception) {
            Log.e("SmsNotifListener", "Failed to post safe clone notification", e)
        }
    }

    /** Posts a threat (suspicious / smishing) result notification. */
    private fun postThreatNotification(
        sender: String,
        classification: Classification,
        confidencePct: String
    ) {
        val (title, body, icon) = when (classification) {
            Classification.SUSPICIOUS -> Triple(
                getString(R.string.notif_result_suspicious_title),
                getString(R.string.notif_result_suspicious_text, sender, confidencePct),
                R.drawable.ic_warning_triangle
            )
            Classification.SMISHING -> Triple(
                getString(R.string.notif_result_smishing_title),
                getString(R.string.notif_result_smishing_text, sender, confidencePct),
                R.drawable.ic_block
            )
            else -> return
        }

        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_SENDER", sender)
            putExtra("EXTRA_MESSAGE", body)
            putExtra("EXTRA_CONFIDENCE", confidencePct)
        }
        val pi = PendingIntent.getActivity(
            this, notifIdCounter.get(), tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, SettingsFragment.CHANNEL_RESULT)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(notifIdCounter.getAndIncrement(), notif)
        } catch (e: Exception) {
            Log.e("SmsNotifListener", "Failed to post threat notification", e)
        }
    }

    /** Cancels one of Kuwago's own notifications by its int ID. */
    private fun cancelOwnNotification(notifId: Int) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.cancel(notifId)
        } catch (e: Exception) {
            Log.e("SmsNotifListener", "Failed to cancel own notification $notifId", e)
        }
    }

    // -------------------------------------------------------------------------
    // Existing helpers (unchanged)
    // -------------------------------------------------------------------------

    private fun cancelNotificationSafely(key: String) {
        try {
            cancelNotification(key)
        } catch (e: Exception) {
            Log.e("SmsNotifListener", "Failed to cancel notification $key: ${e.message}")
        }
    }

    fun triggerAggressiveKill(pkg: String) {
        Log.i("SmsNotifListener", "Triggering aggressive kill for $pkg")
        killAllBlacklistedFromApp(pkg)
        for (delay in longArrayOf(200, 500, 1000, 2000, 4000)) {
            handler.postDelayed({ killAllBlacklistedFromApp(pkg) }, delay)
        }
    }

    fun killAllBlacklistedFromApp(pkg: String) {
        try {
            val active = getActiveNotifications() ?: return
            for (notif in active) {
                if (notif.packageName != pkg) continue

                val isGroupSummary = (notif.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0

                var isSenderBlacklisted = false
                try {
                    val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notif.notification)
                    if (style != null) {
                        for (msg in style.messages) {
                            val personUri = msg.person?.uri?.removePrefix("tel:")
                            val personName = msg.person?.name?.toString()
                            val legacySender = msg.sender?.toString()
                            val senderToCheck = personUri ?: personName ?: legacySender
                            if (!senderToCheck.isNullOrBlank() && BlacklistRepository.isBlacklisted(this, senderToCheck)) {
                                isSenderBlacklisted = true
                                break
                            }
                        }
                    }
                } catch (e: Exception) {}

                val notifTitle = notif.notification.extras
                    .getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
                val notifText = notif.notification.extras
                    .getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

                val shouldKill = isGroupSummary || isSenderBlacklisted ||
                        BlacklistRepository.isBlacklisted(this, notifTitle) ||
                        (notifText.length < 50 && BlacklistRepository.isBlacklisted(this, notifText))

                if (shouldKill) {
                    cancelNotificationSafely(notif.key)
                    Log.i("SmsNotifListener", "Killed: key=${notif.key}, title=$notifTitle, group=$isGroupSummary")
                }
            }
        } catch (e: Exception) {
            Log.e("SmsNotifListener", "Error in killAllBlacklistedFromApp", e)
        }
    }
}
