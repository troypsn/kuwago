package com.example.mykotlinapp

import com.example.mykotlinapp.network.RetrofitClient
import com.example.mykotlinapp.network.SmsScanRequest
import android.util.Log
import kotlinx.coroutines.withTimeout

object SmishingDetector {

    private const val TIMEOUT_MS = 15000L // 15 seconds

    suspend fun analyze(message: String): DetectionResult {
        Log.d("SmishingDetector", "--- New Analysis Request ---")
        Log.d("SmishingDetector", "Message: \"$message\"")
        
        return try {
            withTimeout(TIMEOUT_MS) {
                val request = SmsScanRequest(message)
                val response = RetrofitClient.instance.scanSms(request)
                Log.d("SmishingDetector", "API Response: $response")
                
                val classification = when (response.verdict.lowercase()) {
                    "spam" -> Classification.SMISHING
                    "benign" -> Classification.SAFE
                    else -> Classification.SAFE
                }

                DetectionResult(
                    sender = "Unknown",
                    message = message,
                    classification = classification,
                    probability = response.probability,
                    isScanning = false
                )
            }
        } catch (e: Exception) {
            Log.e("SmishingDetector", "Detection failed or timed out: ${e.message}")
            DetectionResult(
                sender = "Unknown",
                message = message,
                classification = Classification.SAFE, // Fallback
                probability = 0f,
                isScanning = false
            )
        }
    }
}
