package com.example.kuwago

import android.content.Context
import com.example.kuwago.network.RetrofitClient
import com.example.kuwago.network.SmsScanRequest
import com.example.kuwago.network.CnnAnalysis
import com.example.kuwago.network.UrlAnalysis
import android.util.Log
import kotlinx.coroutines.withTimeout

object SmishingDetector {

    private const val TIMEOUT_MS = 60000L // 60 seconds for backend CNN + VirusTotal URL scan

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

    suspend fun analyze(context: Context, message: String, sender: String): DetectionResult {
        Log.i("SmishingDetector", "Starting ensemble scan...")
        
        val localResult = try {
            LocalClassifier.classify(context, message)
        } catch (e: Exception) {
            Log.e("SmishingDetector", "Local Classifier error: ${e.javaClass.simpleName}")
            DetectionResult(
                sender = sender,
                message = message,
                classification = Classification.SAFE,
                probability = 0f,
                isScanning = false
            )
        }

        val hasUrl = LocalClassifier.hasUrl(message)
        val extractedUrl = LocalClassifier.extractUrl(message)
        Log.i("SmishingDetector", "URL Pre-Check: hasUrl=$hasUrl")

        val prefs = context.getSharedPreferences("kuwago_settings", Context.MODE_PRIVATE)
        val allowSave = prefs.getBoolean("help_train_ai", false)

        val censoredSenderName = censorSender(sender)
        Log.i("SmishingDetector", "AI Train Setting: allowSave=$allowSave")

        val mlPrediction = when (localResult.classification) {
            Classification.SAFE -> "benign"
            Classification.SUSPICIOUS -> "suspicious"
            Classification.SMISHING -> "smishing"
        }
        val mlConfidence = localResult.probability

        return try {
            // --- Local URL reputation cache check (avoids API round-trip for known hosts) ---
            val normalizedHost = if (hasUrl && extractedUrl != null) UrlNormalizer.normalizeHost(extractedUrl) else null
            val cachedReputation = if (normalizedHost != null) UrlReputationCache.get(normalizedHost) else null
            val isVpnActive = context.getSharedPreferences(KuwagoVpnService.PREFS_VPN, Context.MODE_PRIVATE)
                .getBoolean(KuwagoVpnService.KEY_VPN_ACTIVE, false)

            // Use cached URL reputation if available AND VPN is not active (no need to refresh blocklist)
            if (cachedReputation != null && !isVpnActive) {
                Log.i("SmishingDetector", "URL cache hit for host=$normalizedHost → $cachedReputation. Skipping backend URL scan.")
                val cachedUrlScore = if (cachedReputation == Classification.SMISHING) 1.0f
                                     else if (cachedReputation == Classification.SUSPICIOUS) 0.6f
                                     else 0.0f
                val cachedUrlVerdict = if (cachedReputation == Classification.SMISHING) "malicious"
                                       else if (cachedReputation == Classification.SUSPICIOUS) "suspicious"
                                       else "clean"
                val localScore = localResult.probability
                val finalProb = (0.50f * localScore) + (0.25f * cachedUrlScore) + (0.25f * localScore)
                val classification = when {
                    finalProb >= LocalClassifier.smishingThreshold -> Classification.SMISHING
                    finalProb >= LocalClassifier.suspiciousThreshold -> Classification.SUSPICIOUS
                    else -> Classification.SAFE
                }
                return DetectionResult(
                    sender = sender,
                    message = message,
                    classification = classification,
                    probability = finalProb,
                    isScanning = false,
                    cnnScore = null,
                    cnnVerdict = "Served from cache",
                    urlFound = hasUrl,
                    extractedUrl = extractedUrl,
                    urlScore = cachedUrlScore,
                    urlVerdict = cachedUrlVerdict,
                    explanation = "Result from local URL cache for host: $normalizedHost",
                    localVerdict = localResult.classification.name.lowercase().replaceFirstChar { it.uppercase() },
                    ensembleFormula = "Cache hit: 75% Local + 25% Cached URL",
                    rfProb = localResult.rfProb,
                    rfRawLogit = localResult.rfRawLogit,
                    xgbProb = localResult.xgbProb,
                    cnnProb = null
                )
            }

            withTimeout(TIMEOUT_MS) {
                Log.i("SmishingDetector", "Sending request to CNN-BiGRU API (has_url=$hasUrl, allow_save=$allowSave, ml_pred=$mlPrediction)...")
                val request = SmsScanRequest(
                    message = message,
                    hasUrl = hasUrl,
                    extractedUrl = extractedUrl,
                    allowSave = allowSave,
                    sender = censoredSenderName,
                    mlPrediction = mlPrediction,
                    mlConfidence = mlConfidence
                )
                val response = RetrofitClient.instance.scanSms(request)
                Log.i("SmishingDetector", "API response received successfully")
                
                val hasCnnData = response.cnnAnalysis != null
                val cnnScore = response.cnnAnalysis?.score
                val cnnVerdict = response.cnnAnalysis?.verdict
                val url = response.urlAnalysis ?: UrlAnalysis(false, null, null, null, null, null, emptyList())
                val containsUrl = hasUrl || url.hasUrl

                // Derive effective URL score if score is null but verdict is returned (e.g. backend database cache hit)
                val effectiveUrlScore: Float? = url.score ?: when (url.verdict?.lowercase()) {
                    "malicious", "smishing", "spam" -> 1.0f
                    "suspicious" -> 0.6f
                    "clean", "benign", "safe" -> 0.0f
                    else -> null
                }
                val effectiveUrlVerdict: String? = url.verdict ?: effectiveUrlScore?.let {
                    if (it >= 0.5f) "malicious" else if (it >= 0.3f) "suspicious" else "clean"
                }

                val localScore = localResult.probability

                // Update cache with fresh result from API
                if (normalizedHost != null && (effectiveUrlVerdict != null || effectiveUrlScore != null)) {
                    val freshReputation = when {
                        effectiveUrlVerdict?.lowercase() == "malicious" || (effectiveUrlScore ?: 0f) >= 0.5f -> Classification.SMISHING
                        effectiveUrlVerdict?.lowercase() == "suspicious" || (effectiveUrlScore ?: 0f) >= 0.3f -> Classification.SUSPICIOUS
                        else -> Classification.SAFE
                    }
                    UrlReputationCache.put(normalizedHost, freshReputation)
                    Log.i("SmishingDetector", "Updated URL cache: $normalizedHost → $freshReputation")
                }

                val (finalProb, formulaStr) = if (hasCnnData && cnnScore != null) {
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

                val classification = when {
                    finalProb >= LocalClassifier.smishingThreshold -> Classification.SMISHING
                    finalProb >= LocalClassifier.suspiciousThreshold -> Classification.SUSPICIOUS
                    else -> Classification.SAFE
                }

                Log.i("SmishingDetector", "Final classification complete: verdict=$classification, prob=$finalProb")

                var explanationText = response.overallExplanation ?: when {
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

                if (containsUrl && effectiveUrlScore == null && !explanationText.contains("cannot be guaranteed", ignoreCase = true) && !explanationText.contains("no online threat scan", ignoreCase = true)) {
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
                    extractedUrl = url.extractedUrl ?: extractedUrl,
                    urlScore = effectiveUrlScore,
                    urlVerdict = effectiveUrlVerdict,
                    explanation = url.explanation,
                    overallExplanation = explanationText,
                    urlTotalWeight = url.totalWeight,
                    urlContributions = url.contributions,
                    localVerdict = localResult.classification.name.lowercase().replaceFirstChar { it.uppercase() },
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
            val localOnly = LocalClassifier.classify(context, message)
            val fallbackExplanation = LocalClassifier.generateHumanReadableExplanation(message, localOnly.classification)
            localOnly.copy(
                sender = sender,
                message = message,
                overallExplanation = fallbackExplanation,
                isScanning = false,
                cnnProb = null,
                cnnScore = null,
                cnnVerdict = "API Error: ${e.localizedMessage ?: "Failed to connect"}",
                localVerdict = localOnly.classification.name.lowercase().replaceFirstChar { it.uppercase() },
                ensembleFormula = "Local Only (50% RF, 50% XGB)"
            )
        }
    }
}
