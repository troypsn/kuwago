package com.example.kuwago

import android.content.Context
import com.example.kuwago.network.RetrofitClient
import com.example.kuwago.network.SmsScanRequest
import com.example.kuwago.network.CnnAnalysis
import com.example.kuwago.network.UrlAnalysis
import android.util.Log
import kotlinx.coroutines.withTimeout

object SmishingDetector {

    private const val TIMEOUT_MS = 35000L // 35 seconds to allow for cold starts + VirusTotal scan

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

        return try {
            withTimeout(TIMEOUT_MS) {
                Log.i("SmishingDetector", "Sending request to CNN-BiGRU API...")
                val request = SmsScanRequest(message)
                val response = RetrofitClient.instance.scanSms(request)
                Log.i("SmishingDetector", "API RESPONSE RECEIVED SUCCESSFULLY: $response")
                
                val cnn = response.cnnAnalysis ?: CnnAnalysis(0f, "benign")
                val url = response.urlAnalysis ?: UrlAnalysis(false, null, null, null, null, null, emptyList())

                val cnnScore = cnn.score
                val urlScore = url.score ?: 0f
                val localScore = localResult.probability

                val urlVerdictLower = (url.verdict ?: "").lowercase()
                val cnnVerdictLower = cnn.verdict.lowercase()

                val isUrlSmishing = urlVerdictLower in listOf("malicious", "suspicious", "phishing", "spam", "smishing")
                val isCnnSmishing = cnnVerdictLower in listOf("spam", "phishing", "malicious", "smishing")
                val isLocalSmishing = localResult.classification == Classification.SMISHING

                val classification = when {
                    isUrlSmishing || isCnnSmishing || isLocalSmishing -> Classification.SMISHING
                    urlScore >= LocalClassifier.smishingThreshold || cnnScore >= LocalClassifier.smishingThreshold -> Classification.SMISHING
                    urlScore >= LocalClassifier.suspiciousThreshold || cnnScore >= LocalClassifier.suspiciousThreshold -> Classification.SUSPICIOUS
                    localResult.classification == Classification.SUSPICIOUS -> Classification.SUSPICIOUS
                    else -> Classification.SAFE
                }

                val overallProb = maxOf(cnnScore, urlScore, localScore)

                Log.i("SmishingDetector", "FINAL CLASSIFICATION: verdict=$classification, prob=$overallProb (CNN score=$cnnScore, URL score=$urlScore, Local score=$localScore)")

                val finalResult = DetectionResult(
                    sender = sender,
                    message = message,
                    classification = classification,
                    probability = overallProb,
                    isScanning = false,
                    cnnScore = cnnScore,
                    cnnVerdict = cnn.verdict,
                    urlFound = url.hasUrl,
                    extractedUrl = url.extractedUrl,
                    urlScore = url.score,
                    urlVerdict = url.verdict,
                    explanation = url.explanation,
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
            localOnly.copy(
                sender = sender,
                message = message,
                isScanning = false,
                cnnProb = 0f,
                cnnScore = 0f,
                cnnVerdict = "API Error: ${e.localizedMessage ?: "Failed to connect"}"
            )
        }
    }
}
