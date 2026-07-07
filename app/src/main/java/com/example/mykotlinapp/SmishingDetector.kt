package com.example.mykotlinapp

import com.example.mykotlinapp.network.RetrofitClient
import com.example.mykotlinapp.network.SmsScanRequest
import android.util.Log
import kotlinx.coroutines.withTimeout

object SmishingDetector {

    private const val TIMEOUT_MS = 30000L // Increased to 30 seconds for Render "cold starts"

    suspend fun analyze(message: String): DetectionResult {
        Log.i("SmishingDetector", "-----------------------------------------")
        Log.i("SmishingDetector", "STARTING SCAN: \"$message\"")
        
        return try {
            withTimeout(TIMEOUT_MS) {
                val request = SmsScanRequest(message)
                Log.d("SmishingDetector", "Sending Request to Backend...")
                
                val response = RetrofitClient.instance.scanSms(request)
                Log.i("SmishingDetector", "API SUCCESS! Response: $response")
                
                val classification = when (response.verdict.lowercase()) {
                    "spam" -> Classification.SMISHING
                    "benign" -> Classification.SAFE
                    else -> {
                        Log.w("SmishingDetector", "Unknown verdict: ${response.verdict}, defaulting to SAFE")
                        Classification.SAFE
                    }
                }

                DetectionResult(
                    sender = "Unknown",
                    message = message,
                    classification = classification,
                    probability = response.probability,
                    isScanning = false
                )
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.e("SmishingDetector", "TIMEOUT ERROR: Backend took longer than ${TIMEOUT_MS/1000}s to respond.")
            DetectionResult(
                sender = "Unknown",
                message = "$message [Scan Timed Out]",
                classification = Classification.SAFE,
                probability = 0f,
                isScanning = false
            )
        } catch (e: Exception) {
            Log.e("SmishingDetector", "API FAILURE: ${e.javaClass.simpleName} - ${e.message}")
            Log.e("SmishingDetector", "Stack trace:", e)
            
            DetectionResult(
                sender = "Unknown",
                message = "$message [Network Error]",
                classification = Classification.SAFE,
                probability = 0f,
                isScanning = false
            )
        }
    }
}
