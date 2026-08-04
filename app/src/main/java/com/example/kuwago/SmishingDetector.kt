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

    suspend fun analyze(context: Context, message: String, sender: String): DetectionResult {
        Log.i("SmishingDetector", "=========================================")
        Log.i("SmishingDetector", "STARTING ENSEMBLE SCAN: Sender=$sender, Message=\"$message\"")
        
        val localResult = try {
            LocalClassifier.classify(context, message)
        } catch (e: Exception) {
            Log.e("SmishingDetector", "Local Classifier error: ${e.message}", e)
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
        Log.i("SmishingDetector", "URL Pre-Check: hasUrl=$hasUrl, extractedUrl=$extractedUrl")

        return try {
            withTimeout(TIMEOUT_MS) {
                Log.i("SmishingDetector", "Sending request to CNN-BiGRU API (has_url=$hasUrl, extracted_url=$extractedUrl)...")
                val request = SmsScanRequest(
                    message = message,
                    hasUrl = hasUrl,
                    extractedUrl = extractedUrl
                )
                val response = RetrofitClient.instance.scanSms(request)
                Log.i("SmishingDetector", "API RESPONSE RECEIVED SUCCESSFULLY: $response")
                
                val cnn = response.cnnAnalysis ?: CnnAnalysis(0f, "benign")
                val url = response.urlAnalysis ?: UrlAnalysis(false, null, null, null, null, null, emptyList())

                val cnnScore = cnn.score
                val urlScore = url.score ?: 0f
                val localScore = localResult.probability
                val containsUrl = hasUrl || url.hasUrl

                // Calculate weighted ensemble probability
                val (finalProb, formulaStr) = if (containsUrl) {
                    val score = (0.50f * cnnScore) + (0.25f * urlScore) + (0.25f * localScore)
                    val formula = "Weighted Ensemble: 50% CNN + 25% URL + 25% Local"
                    Pair(score, formula)
                } else {
                    val score = (0.50f * cnnScore) + (0.50f * localScore)
                    val formula = "Weighted Ensemble: 50% CNN + 50% Local"
                    Pair(score, formula)
                }

                // Determine final classification based on weighted probability score
                val classification = when {
                    finalProb >= LocalClassifier.smishingThreshold -> Classification.SMISHING
                    finalProb >= LocalClassifier.suspiciousThreshold -> Classification.SUSPICIOUS
                    else -> Classification.SAFE
                }

                Log.i("SmishingDetector", "FINAL CLASSIFICATION: verdict=$classification, prob=$finalProb ($formulaStr)")

                val finalResult = DetectionResult(
                    sender = sender,
                    message = message,
                    classification = classification,
                    probability = finalProb,
                    isScanning = false,
                    cnnScore = cnnScore,
                    cnnVerdict = cnn.verdict,
                    urlFound = containsUrl,
                    extractedUrl = url.extractedUrl ?: extractedUrl,
                    urlScore = url.score,
                    urlVerdict = url.verdict,
                    explanation = url.explanation,
                    localVerdict = localResult.classification.name.lowercase().replaceFirstChar { it.uppercase() },
                    ensembleFormula = formulaStr,
                    rfProb = localResult.rfProb,
                    rfRawLogit = localResult.rfRawLogit,
                    xgbProb = localResult.xgbProb,
                    cnnProb = cnnScore
                )

                // Auto-blacklist if enabled and high-risk smishing detected
                if (classification == Classification.SMISHING && BlacklistRepository.isAutoBlacklistEnabled(context)) {
                    Log.i("SmishingDetector", "Auto-blacklisting high-risk sender: $sender")
                    BlacklistRepository.addOrUpdateEntry(
                        context = context,
                        sender = sender,
                        riskLevel = RiskLevel.HIGH,
                        method = BlacklistMethod.AUTO
                    )
                }

                finalResult
            }
        } catch (e: Exception) {
            Log.e("SmishingDetector", "CNN-BiGRU API request failed or timed out: ${e.javaClass.simpleName} - ${e.message}", e)
            val localOnly = LocalClassifier.classify(context, message)
            // cnnProb and cnnScore remain NULL so the UI knows the remote scan did NOT complete successfully and allows retrying!
            localOnly.copy(
                sender = sender,
                message = message,
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
