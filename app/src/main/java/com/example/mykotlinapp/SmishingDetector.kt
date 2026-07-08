package com.example.mykotlinapp

import android.content.Context
import android.util.Log
import com.example.mykotlinapp.network.RetrofitClient
import com.example.mykotlinapp.network.SmsScanRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

object SmishingDetector {

    private const val NETWORK_TIMEOUT_MS = 6000L // 6 seconds timeout for CNN remote call

    suspend fun analyze(context: Context, message: String): DetectionResult = withContext(Dispatchers.Default) {
        Log.d("SmishingDetector", "--- New Analysis Request (Hybrid Local + Remote CNN) ---")
        Log.d("SmishingDetector", "Message: \"$message\"")

        // 1. Initialize local classifier (loads models and weights if not done)
        LocalClassifier.init(context)

        // 2. Perform local classification (XGBoost + RF)
        val localResult = try {
            LocalClassifier.classify(context, message)
        } catch (e: Exception) {
            Log.e("SmishingDetector", "Local classification failed: ${e.message}")
            null
        }

        val pLocal = localResult?.probability ?: 0.0f
        Log.d("SmishingDetector", "Local prediction probability: $pLocal")

        // 3. Attempt to fetch CNN-BiGRU prediction from the remote API
        var pCnn: Float? = null
        try {
            // Run network request on IO dispatcher with timeout
            withContext(Dispatchers.IO) {
                withTimeout(NETWORK_TIMEOUT_MS) {
                    val request = SmsScanRequest(message)
                    val response = RetrofitClient.instance.scanSms(request)
                    pCnn = response.probability
                    Log.d("SmishingDetector", "Remote CNN API success: prob=$pCnn")
                }
            }
        } catch (e: Exception) {
            // Gracefully catch timeout, connection loss (WiFi dropped), or server errors
            Log.w("SmishingDetector", "Remote CNN API failed/timed out: ${e.message}. Falling back to local models.")
        }

        // 4. Combine verdicts using the dynamic weights
        val finalProb: Float
        if (pCnn != null) {
            // Hybrid combination: 50% Local, 50% CNN
            finalProb = LocalClassifier.localWeight * pLocal + LocalClassifier.cnnWeight * pCnn!!
            Log.d("SmishingDetector", "Hybrid verdict calculated: finalProb=$finalProb (Local weight: ${LocalClassifier.localWeight}, CNN weight: ${LocalClassifier.cnnWeight})")
        } else {
            // Fallback: Use local models only
            finalProb = pLocal
            Log.d("SmishingDetector", "Fallback local-only verdict calculated: finalProb=$finalProb")
        }

        val finalClassification = when {
            finalProb >= LocalClassifier.smishingThreshold -> Classification.SMISHING
            finalProb >= LocalClassifier.suspiciousThreshold -> Classification.SUSPICIOUS
            else -> Classification.SAFE
        }

        DetectionResult(
            sender = "Unknown",
            message = message,
            classification = finalClassification,
            probability = finalProb,
            isScanning = false
        )
    }
}
