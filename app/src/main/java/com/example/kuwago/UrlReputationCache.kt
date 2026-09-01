package com.example.kuwago

/**
 * Thread-safe, bounded in-memory LRU cache for URL host reputation decisions.
 *
 * The VPN service checks this cache first on every DNS query before hitting
 * the Room database, keeping the hot path allocation-free for known hosts.
 *
 * Cache is populated on first DB lookup and cleared when the VPN stops to
 * avoid serving stale decisions after new scans have been completed.
 */
object UrlReputationCache {

    private const val MAX_SIZE = 500

    // Access-ordered LinkedHashMap → LRU eviction of eldest entry beyond MAX_SIZE.
    // Synchronised externally via the @Synchronized annotations below.
    private val cache: LinkedHashMap<String, Classification> =
        object : LinkedHashMap<String, Classification>(64, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, Classification>
            ) = size > MAX_SIZE
        }

    /**
     * Returns the cached [Classification] for [host], or null if not cached.
     * [host] must already be in normalized form (via [UrlNormalizer.normalizeHost]).
     */
    @Synchronized
    fun get(host: String): Classification? = cache[host]

    /**
     * Stores [classification] for [host] in the cache.
     * Overwrites any existing entry for the same host.
     */
    @Synchronized
    fun put(host: String, classification: Classification) {
        cache[host] = classification
    }

    /**
     * Removes all entries. Called when the VPN service stops so that the next
     * session always fetches fresh decisions from the database.
     */
    @Synchronized
    fun invalidate() = cache.clear()

    /** Returns the current number of cached entries (for diagnostics). */
    @Synchronized
    fun size(): Int = cache.size
}
