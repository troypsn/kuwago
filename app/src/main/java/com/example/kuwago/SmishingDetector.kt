package com.example.kuwago

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.kuwago.network.RetrofitClient
import com.example.kuwago.network.SmsScanRequest
import com.example.kuwago.network.CnnAnalysis
import com.example.kuwago.network.UrlAnalysis
import android.util.Log
import kotlinx.coroutines.withTimeout

object SmishingDetector {

    private const val TIMEOUT_MS = 120_000L // 120 seconds (2 minutes) for backend CNN + VirusTotal URL scan

    fun isConnectedToMobileData(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (e: Exception) {
            false
        }
    }

    fun censorSender(sender: String): String {
        if (sender.isBlank() || sender.equals("Unknown", ignoreCase = true)) {
            return "Unknown"
        }
        val clean = sender.replace(Regex("[\\s\\-()]"), "")
        val isPhone = clean.matches(Regex("\\+?\\d{3,15}"))
        if (isPhone) {
            val len = clean.length
            return if (clean.startsWith("+")) {
                if (len >= 10) {
                    clean.substring(0, 3) + "****" + clean.substring(len - 4)
                } else {
                    clean.substring(0, 2) + "****" + clean.substring(len - 2)
                }
            } else {
                if (len >= 8) {
                    clean.substring(0, 3) + "****" + clean.substring(len - 3)
                } else {
                    clean.substring(0, 2) + "****" + clean.substring(len - 2)
                }
            }
        }
        val len = sender.length
        if (len <= 2) return sender
        val start = sender.substring(0, 1)
        val end = sender.substring(len - 1)
        return start + "*".repeat(len - 2) + end
    }

    suspend fun analyze(
        context: Context,
        message: String,
        sender: String,
        isManual: Boolean = false
    ): DetectionResult {
        Log.i("SmishingDetector", "Starting ensemble scan (isManual=$isManual)...")
        
        val localResult = try {
            LocalClassifier.classify(context, message)
        } catch (e: Throwable) {
            Log.e("SmishingDetector", "Local Classifier error: ${e.javaClass.simpleName}, using heuristics")
            LocalClassifier.classifyWithHeuristics(message).copy(
                sender = sender,
                message = message
            )
        }

        val hasUrl = LocalClassifier.hasUrl(message)
        val extractedUrl = LocalClassifier.extractUrl(message)
        val isShortened = LocalClassifier.containsShortenedUrl(message) || (extractedUrl != null && LocalClassifier.isShortenedUrl(extractedUrl))
        Log.i("SmishingDetector", "URL Pre-Check: hasUrl=$hasUrl, isShortened=$isShortened")

        val prefs = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val allowSave = prefs.getBoolean("help_train_ai", false)
        val autoReport = prefs.getBoolean(SettingsFragment.KEY_AUTO_REPORT_NTC, false)

        val onlineScanMode = prefs.getString(
            SettingsFragment.KEY_ONLINE_SCAN_MODE,
            SettingsFragment.MODE_AUTOMATIC
        ) ?: SettingsFragment.MODE_AUTOMATIC
        val dataSaver = prefs.getBoolean(SettingsFragment.KEY_DATA_SAVER, false)
        val isMobileData = isConnectedToMobileData(context)
        val skipDueToDataSaver = dataSaver && isMobileData && !isManual
        val isOnlineDisabled = onlineScanMode == SettingsFragment.MODE_DISABLED && !isManual

        if (isOnlineDisabled || skipDueToDataSaver) {
            val skipReason = if (skipDueToDataSaver) "Skipped (Data Saver active on mobile data)" else "Online scan disabled"
            Log.i("SmishingDetector", "Skipping online scan: $skipReason (isManual=$isManual)")
            val fallbackExplanation = LocalClassifier.generateHumanReadableExplanation(message, localResult.classification)
            return localResult.copy(
                sender = sender,
                message = message,
                classification = if (isShortened) Classification.SMISHING else localResult.classification,
                probability = if (isShortened) maxOf(localResult.probability, 0.95f) else localResult.probability,
                overallExplanation = fallbackExplanation,
                isScanning = false,
                cnnProb = null,
                cnnScore = null,
                cnnVerdict = skipReason,
                urlFound = hasUrl || isShortened,
                extractedUrl = extractedUrl,
                urlScore = if (isShortened) 1.0f else null,
                urlVerdict = if (isShortened) "malicious" else null,
                urlContributions = if (isShortened) listOf("Shortened URL Detected") else null,
                explanation = if (isShortened) LocalClassifier.SHORTENED_URL_REASONING else null,
                localVerdict = if (isShortened) "Harmful" else localResult.classification.name.lowercase().replaceFirstChar { it.uppercase() },
                ensembleFormula = if (isShortened) "Rule-based: Shortened URL (Harmful) + Local ML" else "Local ML Model (75% RF + 25% XGB)"
            )
        }

        val censoredSenderName = censorSender(sender)
        Log.i("SmishingDetector", "AI Train Setting: allowSave=$allowSave, autoReport=$autoReport")

        val mlPrediction = when {
            isShortened -> "smishing"
            localResult.classification == Classification.SAFE -> "benign"
            localResult.classification == Classification.SUSPICIOUS -> "suspicious"
            else -> "smishing"
        }
        val mlConfidence = if (isShortened) maxOf(localResult.probability, 0.95f) else localResult.probability

        return try {
            // --- Local URL reputation cache check (avoids API round-trip for known hosts, but never bypasses explicit manual DL scan) ---
            val normalizedHost = if (hasUrl && extractedUrl != null) UrlNormalizer.normalizeHost(extractedUrl) else null
            val cachedReputation = if (normalizedHost != null) UrlReputationCache.get(normalizedHost) else null
            val isVpnActive = context.getSharedPreferences(KuwagoVpnService.PREFS_VPN, Context.MODE_PRIVATE)
                .getBoolean(KuwagoVpnService.KEY_VPN_ACTIVE, false)

            // Use cached URL reputation if available AND VPN is not active AND user didn't explicitly request Deep Scan
            if (!isManual && cachedReputation != null && !isVpnActive) {
                Log.i("SmishingDetector", "URL cache hit for host=$normalizedHost → $cachedReputation. Skipping backend URL scan.")
                val cachedUrlScore = if (cachedReputation == Classification.SMISHING || isShortened) 1.0f
                                     else if (cachedReputation == Classification.SUSPICIOUS) 0.6f
                                     else 0.0f
                val cachedUrlVerdict = if (cachedReputation == Classification.SMISHING || isShortened) "malicious"
                                       else if (cachedReputation == Classification.SUSPICIOUS) "suspicious"
                                       else "clean"
                val localScore = localResult.probability
                val finalProb = if (isShortened) maxOf((0.50f * localScore) + (0.25f * cachedUrlScore) + (0.25f * localScore), 0.95f)
                                else (0.50f * localScore) + (0.25f * cachedUrlScore) + (0.25f * localScore)
                val classification = if (isShortened) Classification.SMISHING else when {
                    finalProb >= LocalClassifier.smishingThreshold -> Classification.SMISHING
                    finalProb >= LocalClassifier.suspiciousThreshold -> Classification.SUSPICIOUS
                    else -> Classification.SAFE
                }
                val explanation = if (isShortened) {
                    "Flagged as Harmful: This message contains a shortened URL ($extractedUrl). ${LocalClassifier.SHORTENED_URL_REASONING}"
                } else {
                    "Result from local URL cache for host: $normalizedHost"
                }
                return DetectionResult(
                    sender = sender,
                    message = message,
                    classification = classification,
                    probability = finalProb,
                    isScanning = false,
                    cnnScore = null,
                    cnnVerdict = "Served from cache",
                    urlFound = hasUrl || isShortened,
                    extractedUrl = extractedUrl,
                    urlScore = cachedUrlScore,
                    urlVerdict = cachedUrlVerdict,
                    explanation = if (isShortened) LocalClassifier.SHORTENED_URL_REASONING else "Result from local URL cache for host: $normalizedHost",
                    overallExplanation = explanation,
                    urlContributions = if (isShortened) listOf("Shortened URL Detected") else null,
                    localVerdict = if (isShortened) "Harmful" else localResult.classification.name.lowercase().replaceFirstChar { it.uppercase() },
                    ensembleFormula = if (isShortened) "Rule-based: Shortened URL (Harmful) + Cached URL" else "Cache hit: 75% Local + 25% Cached URL",
                    rfProb = localResult.rfProb,
                    rfRawLogit = localResult.rfRawLogit,
                    xgbProb = localResult.xgbProb,
                    cnnProb = null
                )
            }

            withTimeout(TIMEOUT_MS) {
                // If the URL is shortened, it is already concluded as malicious.
                // We send the backend request WITHOUT the URL (hasUrl = false, extractedUrl = null)
                // so the backend skips the slow remote URL scan and quickly runs CNN text analysis.
                val apiHasUrl = if (isShortened) false else hasUrl
                val apiExtractedUrl = if (isShortened) null else extractedUrl

                Log.i("SmishingDetector", "Sending request to CNN-BiGRU API (has_url=$apiHasUrl, is_shortened=$isShortened, allow_save=$allowSave, auto_report=$autoReport, ml_pred=$mlPrediction)...")
                val request = SmsScanRequest(
                    message = message,
                    hasUrl = apiHasUrl,
                    extractedUrl = apiExtractedUrl,
                    sender = censoredSenderName,
                    mlPrediction = mlPrediction,
                    mlConfidence = mlConfidence,
                    allowSave = allowSave,
                    autoReport = autoReport
                )
                val response = RetrofitClient.instance.scanSms(request)
                Log.i("SmishingDetector", "API response received successfully")
                
                val hasCnnData = response.cnnAnalysis != null
                val cnnScore = response.cnnAnalysis?.score
                val cnnVerdict = response.cnnAnalysis?.verdict
                val url = response.urlAnalysis ?: UrlAnalysis(false, null, null, null, null, null, emptyList())
                val containsUrl = hasUrl || url.hasUrl || isShortened

                // Derive effective URL score if score is null but verdict is returned (e.g. backend database cache hit)
                val effectiveUrlScore: Float? = if (isShortened) 1.0f else (url.score ?: when (url.verdict?.lowercase()) {
                    "malicious", "smishing", "spam" -> 1.0f
                    "suspicious" -> 0.6f
                    "clean", "benign", "safe" -> 0.0f
                    else -> null
                })
                val effectiveUrlVerdict: String? = if (isShortened) "malicious" else (url.verdict ?: effectiveUrlScore?.let {
                    if (it >= 0.5f) "malicious" else if (it >= 0.3f) "suspicious" else "clean"
                })
                val effectiveUrlExplanation: String? = if (isShortened) LocalClassifier.SHORTENED_URL_REASONING else url.explanation
                val effectiveUrlContributions: List<String>? = if (isShortened) listOf("Shortened URL Detected") else url.contributions

                val localScore = localResult.probability

                // Update cache with fresh result from API or shortened URL rule
                if (normalizedHost != null && (isShortened || effectiveUrlVerdict != null || effectiveUrlScore != null)) {
                    val freshReputation = when {
                        isShortened || effectiveUrlVerdict?.lowercase() == "malicious" || (effectiveUrlScore ?: 0f) >= 0.5f -> Classification.SMISHING
                        effectiveUrlVerdict?.lowercase() == "suspicious" || (effectiveUrlScore ?: 0f) >= 0.3f -> Classification.SUSPICIOUS
                        else -> Classification.SAFE
                    }
                    UrlReputationCache.put(normalizedHost, freshReputation)
                    Log.i("SmishingDetector", "Updated URL cache: $normalizedHost → $freshReputation")
                }

                val (finalProb, formulaStr) = if (isShortened) {
                    val score = if (hasCnnData && cnnScore != null) {
                        maxOf((0.50f * cnnScore) + (0.25f * 1.0f) + (0.25f * localScore), 0.95f)
                    } else {
                        maxOf((0.50f * 1.0f) + (0.50f * localScore), 0.95f)
                    }
                    val formula = if (hasCnnData && cnnScore != null) {
                        "Rule-based Shortened URL (Harmful) + 50% CNN + 25% Local"
                    } else {
                        "Rule-based Shortened URL (Harmful) + Local ML"
                    }
                    Pair(score, formula)
                } else if (hasCnnData && cnnScore != null) {
                    if (containsUrl && effectiveUrlScore != null) {
                        val score = (0.50f * cnnScore) + (0.25f * effectiveUrlScore) + (0.25f * localScore)
                        val formula = "Weighted Ensemble: 50% CNN + 25% URL + 25% Local"
                        Pair(score, formula)
                    } else {
                        val score = (2.0f / 3.0f * cnnScore) + (1.0f / 3.0f * localScore)
                        val formula = if (containsUrl) "Weighted Ensemble: 66.7% CNN + 33.3% Local (URL scan pending)" else "Weighted Ensemble: 66.7% CNN + 33.3% Local"
                        Pair(score, formula)
                    }
                } else {
                    if (containsUrl && effectiveUrlScore != null) {
                        val score = (0.50f * effectiveUrlScore) + (0.50f * localScore)
                        val formula = "Weighted Ensemble: 50% URL + 50% Local"
                        Pair(score, formula)
                    } else {
                        val score = localScore
                        val formula = if (containsUrl) "Local ML Model (75% RF + 25% XGB, URL scan pending)" else "Local ML Model (75% RF + 25% XGB)"
                        Pair(score, formula)
                    }
                }

                val classification = if (isShortened) Classification.SMISHING else when {
                    finalProb >= LocalClassifier.smishingThreshold -> Classification.SMISHING
                    finalProb >= LocalClassifier.suspiciousThreshold -> Classification.SUSPICIOUS
                    else -> Classification.SAFE
                }

                Log.i("SmishingDetector", "Final classification complete: verdict=$classification, prob=$finalProb")

                var explanationText = if (isShortened) {
                    "Flagged as Harmful: This message contains a shortened URL (${extractedUrl ?: "short link"}). ${LocalClassifier.SHORTENED_URL_REASONING}"
                } else {
                    response.overallExplanation ?: when {
                        effectiveUrlVerdict?.lowercase() == "malicious" || (effectiveUrlScore ?: 0f) >= 0.5f ->
                            "This message contains an unverified web link that was flagged as malicious by security threat intelligence."
                        effectiveUrlScore == null && containsUrl ->
                            "This message contains an unverified web link, but no online threat scan result is available yet. Exercise caution as its safety cannot be guaranteed without verification."
                        classification == Classification.SMISHING ->
                            "This message uses urgent call-to-action language, prize promises, or financial triggers typically associated with SMS scams."
                        classification == Classification.SUSPICIOUS ->
                            "This message exhibits characteristics of unsolicited or promotional SMS content. Exercise caution before opening links or replying."
                        else ->
                            "No suspicious patterns, urgency triggers, or malicious web links were detected in this message."
                    }
                }

                if (containsUrl && !isShortened && effectiveUrlScore == null && !explanationText.contains("cannot be guaranteed", ignoreCase = true) && !explanationText.contains("no online threat scan", ignoreCase = true)) {
                    explanationText += " Exercise caution: this message contains an unverified web link that has not been scanned by online threat intelligence yet, so its safety cannot be guaranteed."
                }

                val finalResult = DetectionResult(
                    sender = sender,
                    message = message,
                    classification = classification,
                    probability = finalProb,
                    isScanning = false,
                    cnnScore = cnnScore,
                    cnnVerdict = cnnVerdict,
                    urlFound = containsUrl,
                    extractedUrl = extractedUrl ?: url.extractedUrl,
                    urlScore = effectiveUrlScore,
                    urlVerdict = effectiveUrlVerdict,
                    explanation = effectiveUrlExplanation,
                    overallExplanation = explanationText,
                    urlTotalWeight = if (isShortened) 1.0f else url.totalWeight,
                    urlContributions = effectiveUrlContributions,
                    localVerdict = if (isShortened) "Harmful" else localResult.classification.name.lowercase().replaceFirstChar { it.uppercase() },
                    ensembleFormula = formulaStr,
                    rfProb = localResult.rfProb,
                    rfRawLogit = localResult.rfRawLogit,
                    xgbProb = localResult.xgbProb,
                    cnnProb = cnnScore
                )

                if (classification == Classification.SMISHING && BlacklistRepository.isAutoBlacklistEnabled(context)) {
                    Log.i("SmishingDetector", "Auto-blacklisting high-risk sender")
                    BlacklistRepository.addOrUpdateEntry(
                        context = context,
                        sender = sender,
                        riskLevel = RiskLevel.HIGH,
                        method = BlacklistMethod.MANUAL
                    )
                }

                finalResult
            }
        } catch (e: Exception) {
            Log.e("SmishingDetector", "CNN-BiGRU API request failed: ${e.javaClass.simpleName}")
            // Evict stale/broken sockets from OkHttp connection pool so subsequent retries connect cleanly
            RetrofitClient.resetConnectionPool()

            val localOnly = try {
                LocalClassifier.classify(context, message)
            } catch (t: Throwable) {
                LocalClassifier.classifyWithHeuristics(message)
            }
            val fallbackExplanation = LocalClassifier.generateHumanReadableExplanation(message, if (isShortened) Classification.SMISHING else localOnly.classification)

            val httpCode = (e as? retrofit2.HttpException)?.code() ?: 0
            val is5xxWakeup = httpCode in 502..504 ||
                    (e.localizedMessage?.contains("502") == true) ||
                    (e.localizedMessage?.contains("503") == true) ||
                    (e.localizedMessage?.contains("504") == true)
            val isTimeout = e is kotlinx.coroutines.TimeoutCancellationException ||
                            e is java.net.SocketTimeoutException ||
                            (e.localizedMessage?.contains("timeout", ignoreCase = true) == true)
            val isConnect = e is java.net.ConnectException ||
                            (e.localizedMessage?.contains("failed to connect", ignoreCase = true) == true) ||
                            (e.localizedMessage?.contains("connection reset", ignoreCase = true) == true)
            val errorVerdict = when {
                is5xxWakeup -> "Server waking up (~1-2 mins on free cloud). Please tap to retry in a moment."
                isTimeout -> "Server wake-up timed out. Free cloud instances take ~1-2 mins to wake up. Tap to retry."
                isConnect -> "Server waking up or unreachable. Please tap to retry in a moment."
                else -> "API Error: ${e.localizedMessage ?: "Failed to connect"}"
            }
            localOnly.copy(
                sender = sender,
                message = message,
                classification = if (isShortened) Classification.SMISHING else localOnly.classification,
                probability = if (isShortened) maxOf(localOnly.probability, 0.95f) else localOnly.probability,
                overallExplanation = fallbackExplanation,
                isScanning = false,
                cnnProb = null,
                cnnScore = null,
                cnnVerdict = errorVerdict,
                urlFound = hasUrl || isShortened,
                extractedUrl = extractedUrl,
                urlScore = if (isShortened) 1.0f else null,
                urlVerdict = if (isShortened) "malicious" else null,
                urlContributions = if (isShortened) listOf("Shortened URL Detected") else null,
                explanation = if (isShortened) LocalClassifier.SHORTENED_URL_REASONING else null,
                localVerdict = if (isShortened) "Harmful" else localOnly.classification.name.lowercase().replaceFirstChar { it.uppercase() },
                ensembleFormula = if (isShortened) "Rule-based: Shortened URL (Harmful) + Local ML" else "Local ML Model (75% RF + 25% XGB)"
            )
        }
    }
}
