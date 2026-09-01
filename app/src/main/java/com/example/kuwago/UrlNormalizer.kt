package com.example.kuwago

import java.net.URI

/**
 * Converts raw URLs (as extracted from SMS text) into a canonical hostname form
 * that can be reliably matched against network-layer observations.
 *
 * Rules applied:
 *   1. Strip scheme if missing (prepend https://).
 *   2. Parse hostname via java.net.URI.
 *   3. Lowercase.
 *   4. Strip "www." prefix.
 *   5. Strip port suffix.
 *   6. Return null for raw IPv4 addresses (not matched against URL scan results).
 *   7. Return null for blank or obviously non-host strings.
 *
 * Both the write path (SmsLocalRepository) and the VPN read path
 * (KuwagoVpnService) use this object so matching is always consistent.
 */
object UrlNormalizer {

    /** Regex for bare IPv4 addresses (e.g. 192.168.1.1). */
    private val IPV4_REGEX = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    /**
     * Extracts and normalizes the hostname from a raw URL string.
     * Returns null if the input cannot be parsed or if the result is an IP address.
     *
     * Example:
     *   "https://Www.Phishing-Site.com/login?token=abc" → "phishing-site.com"
     *   "phishing-site.com"                             → "phishing-site.com"
     *   "192.168.0.1"                                   → null
     */
    fun extractHost(rawUrl: String): String? {
        if (rawUrl.isBlank()) return null
        val candidate = rawUrl.trim()
        val withScheme = if (candidate.contains("://")) candidate else "https://$candidate"
        return try {
            val uri = URI(withScheme)
            val host = uri.host
            if (host != null) normalizeHost(host) else extractHostFallback(candidate)
        } catch (_: Exception) {
            extractHostFallback(candidate)
        }
    }

    private fun extractHostFallback(candidate: String): String? {
        val clean = candidate
            .removePrefix("http://")
            .removePrefix("https://")
            .removePrefix("HTTP://")
            .removePrefix("HTTPS://")
        val hostPart = clean.substringBefore("/").substringBefore("?").substringBefore("#").substringBefore(":")
        return normalizeHost(hostPart)
    }

    /**
     * Normalises a bare hostname (no scheme, no path) to the canonical form
     * used for reputation lookups.
     *
     * Example:
     *   "Www.Example.COM" → "example.com"
     *   "sub.example.com" → "sub.example.com"  (preserved — not collapsed to eTLD+1)
     *   "192.168.0.1"     → null
     */
    fun normalizeHost(host: String): String? {
        if (host.isBlank()) return null
        var h = host.trim().lowercase()
        // Strip port
        if (h.contains(":")) h = h.substringBefore(":")
        // Strip trailing dots, slashes, and common punctuation from text extraction
        h = h.trimEnd('.', '/', ',', ')', '?', '!', ';', ':', '\'')
        // Strip www. prefix only
        if (h.startsWith("www.")) h = h.removePrefix("www.")
        // Reject IP addresses
        if (IPV4_REGEX.matches(h)) return null
        // Must contain at least one dot and no spaces
        if (!h.contains(".") || h.contains(" ")) return null
        return h.ifEmpty { null }
    }
}
