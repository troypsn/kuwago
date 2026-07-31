package com.example.kuwago

import android.content.Context
import android.util.Log
import com.example.kuwago.network.RetrofitClient
import com.example.kuwago.network.SmsScanRequest
import kotlinx.coroutines.withTimeout

object SmishingDetector {

    private const val TIMEOUT_MS = 6000L // 6 seconds timeout for API queries

    suspend fun analyze(context: Context?, message: String, sender: String = "Unknown"): DetectionResult {
        Log.i("SmishingDetector", "-----------------------------------------")
        Log.i("SmishingDetector", "STARTING HYBRID SCAN FOR \"$sender\": \"$message\"")

        // 1. Run local classification first
        val localResult = try {
            LocalClassifier.classify(context, message)
        } catch (e: Exception) {
            Log.e("SmishingDetector", "LOCAL CLASSIFIER ERROR: ${e.message}", e)
            DetectionResult(
                sender = sender,
                message = message,
                classification = Classification.SAFE,
                probability = 0f,
                isScanning = false
            )
        }

        val actualSender = if (sender.isNotBlank() && sender != "Unknown") sender else localResult.sender

        Log.i("SmishingDetector", "Local Classifier Result for $actualSender: prob=${localResult.probability}, class=${localResult.classification}")

        var finalProb = localResult.probability
        var cnnProb: Float? = null
        var isCnnSuccess = false

        // 2. Query remote CNN API with timeout
        try {
            withTimeout(TIMEOUT_MS) {
                val request = SmsScanRequest(message)
                Log.d("SmishingDetector", "Sending request to CNN API...")
                val response = RetrofitClient.instance.scanSms(request)
                Log.i("SmishingDetector", "CNN API SUCCESS! Response: $response")

                val fetchedCnnProb = response.probability
                cnnProb = fetchedCnnProb
                isCnnSuccess = true

                // Compute combined hybrid score: 50% Local ensembled score + 50% CNN API score
                finalProb = LocalClassifier.localWeight * localResult.probability + LocalClassifier.cnnWeight * fetchedCnnProb
                Log.i("SmishingDetector", "CNN API Combined Hybrid Probability: $finalProb")
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.e("SmishingDetector", "CNN API TIMEOUT: API took longer than ${TIMEOUT_MS / 1000}s. Falling back to local.")
        } catch (e: Exception) {
            Log.e("SmishingDetector", "CNN API FAILURE: ${e.javaClass.simpleName} - ${e.message}. Falling back to local.")
        }

        // 3. Determine final classification based on combined/local probability
        val finalClassification = when {
            finalProb >= LocalClassifier.smishingThreshold -> Classification.SMISHING
            finalProb >= LocalClassifier.suspiciousThreshold -> Classification.SUSPICIOUS
            else -> Classification.SAFE
        }

        Log.i("SmishingDetector", "FINAL DECISION FOR $actualSender: prob=$finalProb, class=$finalClassification, cnnSuccess=$isCnnSuccess")

        return DetectionResult(
            id = localResult.id,
            sender = actualSender,
            message = localResult.message,
            classification = finalClassification,
            probability = finalProb,
            isScanning = false,
            rfProb = localResult.rfProb,
            rfRawLogit = localResult.rfRawLogit,
            xgbProb = localResult.xgbProb,
            cnnProb = cnnProb
        )
    }
}
