# Kuwago — VPN-Based Malicious URL Blocking: Implementation Plan

## Executive Summary

After fully reading every source file in the project, this document records:
1. What the existing architecture actually looks like.
2. Exactly which gaps exist that must be closed before a VPN can enforce URL decisions.
3. The proposed implementation plan, file by file.
4. A focused set of questions whose answers materially change the design.

---

## Step 1 — Architecture Analysis (What Actually Exists)

### Android Setup
| Property | Value |
|---|---|
| `minSdk` | 24 (Android 7.0 Nougat) |
| `targetSdk` / `compileSdk` | 37 (Android 15) |
| Language | Kotlin |
| Build system | Gradle KTS + KSP |
| Backend | Retrofit 3 → `https://kwagobackend.onrender.com/` |

### Existing Components

#### Detection Pipeline
1. **`SmsReceiver`** — `BroadcastReceiver` for `SMS_RECEIVED`. Calls `SmishingDetector.analyze()` on `Dispatchers.IO`.
2. **`SmsNotificationListener`** — `NotificationListenerService`. Intercepts SMS app notifications, runs `SmishingDetector.analyze()` for both instant and passthrough scan modes.
3. **`SmishingDetector`** — Orchestrator. Calls `LocalClassifier` (local ONNX ensemble) then POSTs to backend CNN-BiGRU + VirusTotal URL scanner. Produces a `DetectionResult`.
4. **`LocalClassifier`** — On-device RF + XGBoost (ONNX) ensemble. Exposes `hasUrl()` and `extractUrl()`.
5. **`DetectionRepository`** — In-memory `LiveData` + persistence bridge. Delegates to `SmsLocalRepository`.

#### Classification Model
```kotlin
enum class Classification { SAFE, SUSPICIOUS, SMISHING }
```
> **Note:** The classifier uses `SMISHING`, not `MALICIOUS`. The VPN must treat `SMISHING` as the "block" trigger.

#### Database Layer
- **`AppDatabase`** — Room + SQLCipher (AES-256-GCM, key via Android Keystore).
- **`DatabaseSecurityManager`** — Generates and retrieves the SQLCipher passphrase from `AndroidKeyStore`.
- **Tables**: `sms_message`, `analysis_result`, `url_analysis`, `final_decision`, `notification`.

**Critical finding in `url_analysis` table:**
```kotlin
data class UrlAnalysisEntity(
    val urlId: String,
    val smsId: String,
    val extractedUrl: String,     // raw URL string from SMS
    val isMalicious: Int = 0      // 1 if urlScore > 0.5f
)
```
The `extracted_url` column stores the raw URL as extracted from the SMS text (e.g. `https://example.com/login?token=abc`). There is **no normalized hostname column**. This is the primary gap the VPN needs addressed.

**`UrlAnalysisDao` — current queries:**
```kotlin
@Query("SELECT * FROM url_analysis WHERE sms_id = :smsId")
suspend fun getUrlAnalysesBySmsId(smsId: String): List<UrlAnalysisEntity>
```
There is currently **no query to look up a URL or hostname by reputation**. This must be added.

#### Warning / Notification System
- **`WarningOverlayActivity`** — Full-screen "UNHANDLED SMISHING ALERT" shown when user opens the app after a threat detection. Accepts `EXTRA_SENDER`, `EXTRA_MESSAGE`, `EXTRA_CONFIDENCE` via intent.
- **`PendingWarningRepository`** — SharedPreferences store for a single pending warning. Written by background services, consumed by `MainActivity.onResume()`.
- **`SmsNotificationListener`** — Posts system notifications via `CHANNEL_RESULT` (high-importance) and `CHANNEL_SCANNING` (low-importance).
- **`BlacklistRepository`** — SharedPreferences-backed sender blacklist with normalization and acknowledged-warning tracking.

#### Existing Permissions
```xml
RECEIVE_SMS, READ_SMS, INTERNET, POST_NOTIFICATIONS
```
No `BIND_VPN_SERVICE`, no `FOREGROUND_SERVICE`, no `SYSTEM_ALERT_WINDOW`.

#### Existing Services
- `SmsNotificationListener` — `NotificationListenerService` (already background-running).
- No foreground service, no VPN service, no WorkManager/JobScheduler.

#### Backend Networking
- `RetrofitClient` → base URL from `BuildConfig.API_URL` (defaulting to `kwagobackend.onrender.com`).
- Uses OkHttp with auth header. No special socket or TLS config.
- The backend host will need to be excluded from VPN routing to avoid a loopback.

---

## Step 2 — Identified Gaps

| Gap | Severity | Resolution |
|---|---|---|
| No hostname column in `url_analysis` | **Blocker** | Add `normalized_host` column + DAO query |
| No DAO query for hostname reputation lookup | **Blocker** | Add `getHostReputation(host)` to `AnalysisDao` |
| No `VpnService` component | **Blocker** | New `KuwagoVpnService` |
| No VPN permission request flow in UI | **Blocker** | Add to `MainActivity` or Settings |
| No in-memory reputation cache | Performance | New `UrlReputationCache` singleton |
| No foreground service notification channel | Required | Add `CHANNEL_VPN` |
| No VPN blocking warning activity | UX | Extend `WarningOverlayActivity` or add new one |
| `BIND_VPN_SERVICE` permission not declared | **Blocker** | Add to `AndroidManifest.xml` |
| No database migration (schema change) | **Blocker** | Room migration `1 → 2` |

---

## Open Questions

> [!IMPORTANT]
> **Q1 — URL matching granularity (hostname vs exact URL)**
>
> The VPN can observe the **destination hostname** from DNS/TLS SNI. The existing `url_analysis` table stores the full raw URL (e.g. `https://phishing.xyz/login?token=abc`). The most reliable matching the VPN can do at the network layer is at the **registered domain** level (e.g. `phishing.xyz`).
>
> **Should the VPN block the entire domain when any URL from that domain was previously classified SMISHING, or only when the exact URL was matched?**
>
> - Option A — **Block the registered domain** (e.g. all traffic to `phishing.xyz`). Simpler, works fully offline, but could theoretically affect legitimate subdomains of shared hosting providers.
> - Option B — **Block only exact extracted URL** host. Currently that's the same as the domain in practice, but is explicit. This is the recommended safe default.
>
> The implementation plan uses **Option B (registered domain matching)** unless you indicate otherwise.

> [!IMPORTANT]
> **Q2 — SUSPICIOUS URLs: block or allow?**
>
> The existing classification has three states: `SAFE`, `SUSPICIOUS`, `SMISHING`.
>
> Currently the VPN plan is: `SMISHING → BLOCK`, `SAFE → ALLOW`, `SUSPICIOUS → ALLOW` (with optional future warning).
>
> **Should the VPN also block or warn on `SUSPICIOUS` URLs, or only on `SMISHING`?**

> [!IMPORTANT]
> **Q3 — VPN user opt-in requirement**
>
> Android requires the user to explicitly grant VPN permission via a system dialog (`VpnService.prepare()`). The VPN cannot be silently activated.
>
> **Where should the VPN permission request and on/off toggle live?**
> - Option A: New dedicated "URL Shield" row in the Settings screen (consistent with existing settings pattern).
> - Option B: Shown on-demand the first time Kuwago detects a SMISHING URL.
>
> The plan uses **Option A** (Settings row) as the primary toggle.

> [!CAUTION]
> **Q4 — Database migration strategy**
>
> Adding `normalized_host` to `url_analysis` requires a Room migration (`version 1 → 2`). The current `AppDatabase` is configured with `fallbackToDestructiveMigration()`, which will **drop and recreate all tables** on a version bump if no explicit migration is provided.
>
> This means existing scan history will be lost on upgrade.
>
> **Should the migration be a proper `Migration(1, 2)` (preserves data) or is destructive migration acceptable for now?**
>
> The plan writes a **proper non-destructive migration** unless you indicate destructive is acceptable.

> [!NOTE]
> **Q5 — VPN traffic routing scope**
>
> To avoid a routing loop where Kuwago's own backend requests go through the VPN and trigger blocking of `kwagobackend.onrender.com`, the VPN must exclude Kuwago's own network traffic.
>
> The safest approach is: only route `0.0.0.0/0` through the VPN, but add Kuwago's own UID to the `VpnService.Builder`'s `addDisallowedApplication(packageName)` list so that Kuwago's own traffic bypasses the VPN entirely.
>
> **This is the recommended approach and is being implemented by default — please flag if you disagree.**

---

## Step 3 — Architecture Fit

```
SMS received
    ↓
SmishingDetector.analyze()
    ↓
DetectionResult (extractedUrl, classification = SMISHING)
    ↓
SmsLocalRepository.saveAnalysisComplete()
    ↓  [NEW] also writes normalized_host to url_analysis
    ↓
url_analysis table {extracted_url, normalized_host, is_malicious}
    ↓
User taps the URL in any app
    ↓
KuwagoVpnService (new VpnService)
    ↓
Reads destination hostname from DNS / TLS SNI / IP flow
    ↓
UrlReputationCache (in-memory, new)
    ↓  cache miss
AnalysisDao.getHostReputation(host) (new DAO query)
    ↓
    ┌─────────────────────────┐
    │                         │
  SMISHING                  SAFE / UNKNOWN
    │                         │
    ↓                         ↓
VPN resets connection      VPN passes traffic
    ↓
VpnBlockedActivity (new, extends WarningOverlayActivity style)
    ↓
User sees "Kuwago blocked this site — previously identified as SMISHING"
```

---

## Step 4 — Proposed Changes

### A. Database Layer

---

#### [MODIFY] [`Entities.kt`](file:///c:/Users/troyp/AndroidStudioProjects/kuwago/app/src/main/java/com/example/kuwago/db/Entities.kt)

Add `normalized_host` column to `UrlAnalysisEntity`:

```kotlin
@ColumnInfo(name = "normalized_host")
val normalizedHost: String? = null   // e.g. "phishing.xyz" (registered domain, lowercased)
```

---

#### [MODIFY] [`Daos.kt`](file:///c:/Users/troyp/AndroidStudioProjects/kuwago/app/src/main/java/com/example/kuwago/db/Daos.kt)

Add index on `normalized_host` and a new reputation lookup query:

```kotlin
// In UrlAnalysisEntity @Entity indices:
Index(value = ["normalized_host"], name = "idx_url_analysis_host")

// New DAO query:
@Query("""
    SELECT ua.is_malicious, fd.risk_level
    FROM url_analysis ua
    JOIN final_decision fd ON ua.sms_id = fd.sms_id
    WHERE ua.normalized_host = :host
    ORDER BY fd.decision_timestamp DESC
    LIMIT 1
""")
suspend fun getHostReputation(host: String): HostReputationResult?
```

New data class:
```kotlin
data class HostReputationResult(
    val isMalicious: Int,
    val riskLevel: String
)
```

---

#### [MODIFY] [`AppDatabase.kt`](file:///c:/Users/troyp/AndroidStudioProjects/kuwago/app/src/main/java/com/example/kuwago/db/AppDatabase.kt)

Bump version to `2`, remove `fallbackToDestructiveMigration()`, add proper migration:

```kotlin
version = 2,

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE url_analysis ADD COLUMN normalized_host TEXT"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_url_analysis_host ON url_analysis(normalized_host)"
        )
    }
}

// In buildDatabase():
.addMigrations(AppDatabase.MIGRATION_1_2)
// Remove .fallbackToDestructiveMigration()
```

---

### B. URL Normalization Utility

#### [NEW] `UrlNormalizer.kt`

A pure utility (no Android dependencies) to extract the registered domain from any URL string. This is shared between `SmsLocalRepository` (write path) and `KuwagoVpnService` (read path) to guarantee consistent matching.

```kotlin
object UrlNormalizer {
    /** Returns lowercase registered domain (eTLD+1) or null if not parseable. */
    fun extractHost(rawUrl: String): String?
    
    /** Returns the same normalized form used for lookups. */
    fun normalizeHost(host: String): String?
}
```

Key behaviors:
- Strips `www.` prefix.
- Lowercases.
- Returns `null` for IP addresses (not matched against URL scan results).
- No external libraries needed; pure string parsing using `java.net.URI`.

---

### C. Repository Write Path

#### [MODIFY] [`SmsLocalRepository.kt`](file:///c:/Users/troyp/AndroidStudioProjects/kuwago/app/src/main/java/com/example/kuwago/db/SmsLocalRepository.kt)

In `saveAnalysisComplete()`, when building `UrlAnalysisEntity`, also populate `normalizedHost`:

```kotlin
val normalizedHost = UrlNormalizer.extractHost(extractedUrl)
urlEntities.add(
    UrlAnalysisEntity(
        ...,
        normalizedHost = normalizedHost
    )
)
```

---

### D. In-Memory Reputation Cache

#### [NEW] `UrlReputationCache.kt`

```kotlin
object UrlReputationCache {
    // LRU-style map, max ~500 entries, thread-safe
    private val cache = object : LinkedHashMap<String, CachedReputation>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedReputation>) =
            size > MAX_ENTRIES
    }
    
    data class CachedReputation(
        val classification: Classification,
        val cachedAt: Long = System.currentTimeMillis()
    )
    
    fun get(host: String): Classification?
    fun put(host: String, classification: Classification)
    fun invalidate()
}
```

The cache is populated on first VPN lookup and cleared when the VPN stops.

---

### E. VPN Service

#### [NEW] `KuwagoVpnService.kt`

Extends `android.net.VpnService`. Key responsibilities:

1. **`onStartCommand()`** — Build VPN interface via `VpnService.Builder`:
   - Route `0.0.0.0/0` and `::/0` through the tunnel.
   - `addDisallowedApplication(packageName)` — excludes Kuwago's own traffic from the VPN to prevent the routing loop.
   - Establish a `ParcelFileDescriptor` (tun interface).
   - Start the packet-reading coroutine.

2. **Packet reading loop** — Reads raw IP packets from the tun fd.
   - For **UDP port 53** (DNS): parse DNS query, extract queried hostname → reputation lookup.
   - For **TCP port 443 / 80**: attempt TLS SNI extraction from the Client Hello (first byte of TCP payload, before encryption begins) → hostname → reputation lookup.
   - All other traffic: forward as-is.

3. **Reputation lookup** — `UrlReputationCache.get(host)` → cache miss → `AnalysisDao.getHostReputation(host)` on `Dispatchers.IO` → populate cache.

4. **Blocking** — When classification is `SMISHING`:
   - Drop/reset the connection (for TCP: send RST; for DNS: send NXDOMAIN or empty response).
   - Fire `VpnBlockedActivity` via `startActivity()` with `FLAG_ACTIVITY_NEW_TASK`.
   - Post a system notification via `CHANNEL_VPN_BLOCK`.

5. **Foreground service** — The VPN service runs as a foreground service with an ongoing notification (Android 8+ requirement for long-running services).

6. **Lifecycle** — `onRevoke()` handles user-initiated VPN disconnection gracefully. Clears the cache, closes the tun fd.

> [!CAUTION]
> **Technical accuracy note on TLS SNI:** SNI extraction works only for the initial unencrypted TLS Client Hello. Once the TLS handshake completes, subsequent packets are encrypted and the hostname is no longer visible. This covers the first connection but not reconnects using session resumption if the SNI is omitted. This is an acknowledged limitation.

> [!NOTE]
> **DNS-based blocking:** DNS query interception is the most reliable method because DNS queries always include the plaintext hostname. However, DNS over HTTPS (DoH) bypasses this if the app has DoH enabled (Chrome, Firefox). The VPN layer handles standard system DNS (port 53 UDP/TCP). DoH traffic is encrypted HTTPS and can only be identified by hostname of the DoH resolver, not the queried domain.

---

### F. VPN Permission & Settings UI

#### [MODIFY] [`SettingsFragment.kt`](file:///c:/Users/troyp/AndroidStudioProjects/kuwago/app/src/main/java/com/example/kuwago/SettingsFragment.kt)

Add `KEY_VPN_ENABLED` constant. The VPN settings row uses the same pattern as other settings rows — opens a sub-page.

#### [NEW] `SettingsVpnShieldFragment.kt`

Sub-page following the same pattern as `SettingsRealtimeScanFragment`, `SettingsDeepAnalysisFragment`, etc. Contains:
- Toggle switch for "URL Shield" (VPN protection).
- Explanation text.
- On enable: calls `VpnService.prepare(context)`, handles `onActivityResult`, starts `KuwagoVpnService`.
- On disable: sends `ACTION_VPN_STOP` to `KuwagoVpnService`.

#### [MODIFY] [`MainActivity.kt`](file:///c:/Users/troyp/AndroidStudioProjects/kuwago/app/src/main/java/com/example/kuwago/MainActivity.kt)

Handle VPN permission `onActivityResult` (needed for `VpnService.prepare()` result).
Add `CHANNEL_VPN_BLOCK` notification channel creation.

---

### G. VPN Blocked Warning Activity

#### [NEW] `VpnBlockedActivity.kt`

A new activity styled after `WarningOverlayActivity` (same dark-red dark theme). Shows:
- Blocked URL/domain.
- "Kuwago blocked this connection — previously identified as a phishing site."
- Confidence/score from the stored scan result.
- Option to view full scan history entry.
- "I understand the risk — allow this time" (clears that host from cache, does NOT delete the DB record).

This is a separate activity from `WarningOverlayActivity` because:
- The trigger context is different (network block, not incoming SMS).
- The message content is different.
- Future extensibility (the SMS warning may evolve independently).

#### [NEW] `activity_vpn_blocked.xml`

Same visual design language as `activity_warning_overlay.xml`.

---

### H. AndroidManifest Changes

#### [MODIFY] [`AndroidManifest.xml`](file:///c:/Users/troyp/AndroidStudioProjects/kuwago/app/src/main/AndroidManifest.xml)

Add:
```xml
<uses-permission android:name="android.permission.BIND_VPN_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />

<service
    android:name=".KuwagoVpnService"
    android:permission="android.permission.BIND_VPN_SERVICE"
    android:exported="false">
    <intent-filter>
        <action android:name="android.net.VpnService" />
    </intent-filter>
</service>

<activity
    android:name=".VpnBlockedActivity"
    android:exported="false"
    android:theme="@style/Theme.Mykotlinapp"
    android:taskAffinity=""
    android:excludeFromRecents="true" />
```

> [!NOTE]
> `FOREGROUND_SERVICE_SPECIAL_USE` is required on Android 14+ for foreground services that don't fit a predefined type. `VpnService` has its own foreground mechanism (the VPN ongoing notification) so the type handling is somewhat special — covered in the implementation.

---

### I. Notification Channel

#### [MODIFY] [`MainActivity.kt`](file:///c:/Users/troyp/AndroidStudioProjects/kuwago/app/src/main/java/com/example/kuwago/MainActivity.kt)

Add to `createNotificationChannels()`:
```kotlin
val vpnChannel = NotificationChannel(
    SettingsFragment.CHANNEL_VPN_BLOCK,
    "URL Shield Alerts",
    NotificationManager.IMPORTANCE_HIGH
).apply { description = "Alerts when Kuwago blocks a malicious URL" }
nm.createNotificationChannel(vpnChannel)

val vpnOngoing = NotificationChannel(
    SettingsFragment.CHANNEL_VPN_ONGOING,
    "URL Shield Status",
    NotificationManager.IMPORTANCE_LOW
).apply {
    description = "Shows when Kuwago URL Shield is active"
    setShowBadge(false)
}
nm.createNotificationChannel(vpnOngoing)
```

---

### J. String Resources

#### [MODIFY] `strings.xml`

Add VPN-related strings (settings description, warning messages, notification text).

---

## Step 5 — File Summary

| File | Action | Purpose |
|---|---|---|
| `db/Entities.kt` | Modify | Add `normalized_host` to `UrlAnalysisEntity` |
| `db/Daos.kt` | Modify | Add `getHostReputation()` query + `HostReputationResult` |
| `db/AppDatabase.kt` | Modify | Version 2, proper migration |
| `UrlNormalizer.kt` | **New** | Shared URL → hostname normalization |
| `UrlReputationCache.kt` | **New** | In-memory LRU cache for reputation lookups |
| `db/SmsLocalRepository.kt` | Modify | Populate `normalizedHost` when saving URL |
| `KuwagoVpnService.kt` | **New** | VPN enforcement service |
| `VpnBlockedActivity.kt` | **New** | Warning UI when VPN blocks a destination |
| `activity_vpn_blocked.xml` | **New** | Layout for `VpnBlockedActivity` |
| `SettingsVpnShieldFragment.kt` | **New** | Settings sub-page for VPN toggle |
| `SettingsFragment.kt` | Modify | Add VPN row, `CHANNEL_VPN_*` constants |
| `MainActivity.kt` | Modify | VPN channels, `onActivityResult` for VPN permission |
| `AndroidManifest.xml` | Modify | VPN service, permissions, new activity |
| `strings.xml` | Modify | VPN-related UI strings |

---

## Step 6 — Verification Plan

### Automated (Build)
```
./gradlew assembleDebug
```
Verifies compilation, Room schema consistency, and KSP annotation processing.

### Manual Device Tests Required

| Test Case | Expected Behavior |
|---|---|
| Previously SMISHING URL tapped | VPN intercepts, `VpnBlockedActivity` shown, connection dropped |
| Previously SAFE URL tapped | Connection passes through normally |
| Unknown URL tapped | Connection passes through normally |
| Offline + previously SMISHING URL | Local cache/DB lookup → block (no network needed) |
| Kuwago backend calls | Pass through (excluded from VPN via `addDisallowedApplication`) |
| General browsing (HTTPS, HTTP) | Passes through unaffected |
| VPN enabled, app killed by system | VPN revoked gracefully, setting reflects "off" state |
| VPN toggle off in settings | VPN stops, all traffic passes normally |
| New SMISHING result received | Cache populated, subsequent tap is blocked |

---

## Step 7 — Known Limitations

1. **DNS-over-HTTPS bypass**: If the browser uses DoH (Chrome, Firefox with DoH enabled), DNS queries are encrypted HTTPS and the VPN cannot see the queried hostname via DNS interception. In this case, the TLS SNI fallback is used instead, which covers the initial TCP connection.

2. **HTTPS path/query invisible**: The VPN cannot see URL paths or query parameters for HTTPS traffic. Matching is at the hostname level only.

3. **IP-address URLs**: If the phishing URL uses a raw IP address (e.g. `http://203.0.113.5/phish`), there is no hostname to match. These are not blocked by this implementation. A future extension could add IP reputation lookup.

4. **Session resumption without SNI**: TLS sessions can resume without repeating the Client Hello, so SNI-based blocking may not fire for cached connections. DNS-based blocking still works for these cases as long as the device's system DNS is used.

5. **VPN cannot be held indefinitely**: Android battery optimization may stop the VPN service on some OEMs. Users should follow the battery optimization guidance already in the app's Help section.

6. **`addDisallowedApplication` scope**: Excluding Kuwago's own package from the VPN means Kuwago's backend scans bypass the VPN completely. This is intentional and documented (avoids routing loop, does not compromise security since Kuwago's backend is trusted infrastructure).
