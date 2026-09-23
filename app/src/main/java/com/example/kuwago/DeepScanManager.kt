package com.example.kuwago

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages background CNN Deep Scan operations across fragment lifecycles and app pauses.
 * Ensures the downloading buffer state is preserved when tabbing in and out of screens.
 */
object DeepScanManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeScans = ConcurrentHashMap<String, Job>()
    private val statusTexts = ConcurrentHashMap<String, String>()

    private val _statusUpdates = MutableLiveData<Map<String, String>>(emptyMap())
    val statusUpdates: LiveData<Map<String, String>> = _statusUpdates

    fun getKey(result: DetectionResult): String {
        return if (result.id.isNotBlank()) result.id else "${result.sender}|${result.message}"
    }

    fun isScanning(result: DetectionResult): Boolean {
        return activeScans.containsKey(getKey(result))
    }

    fun getStatus(result: DetectionResult): String {
        return statusTexts[getKey(result)] ?: "Scanning…"
    }

    fun cancelScan(result: DetectionResult) {
        val key = getKey(result)
        val job = activeScans.remove(key)
        job?.cancel()
        statusTexts.remove(key)
        _statusUpdates.postValue(HashMap(statusTexts))
    }

    fun startDeepScan(
        context: Context,
        result: DetectionResult,
        onComplete: ((DetectionResult) -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        val key = getKey(result)
        if (activeScans.containsKey(key)) {
            return
        }

        val appContext = context.applicationContext
        com.example.kuwago.network.RetrofitClient.resetConnectionPool()

        statusTexts[key] = "Scanning…"
        _statusUpdates.postValue(HashMap(statusTexts))

        val job = scope.launch {
            // Ticker job for progress updates (total ~1 min for Render cold boot)
            val tickerJob = launch {
                delay(8000L)
                statusTexts[key] = "Waking up server (~1 min)…"
                _statusUpdates.postValue(HashMap(statusTexts))
                delay(22000L)
                statusTexts[key] = "Server spinning up… almost ready"
                _statusUpdates.postValue(HashMap(statusTexts))
                delay(20000L)
                statusTexts[key] = "Finalizing scan…"
                _statusUpdates.postValue(HashMap(statusTexts))
            }

            try {
                val scanResult = SmishingDetector.analyze(appContext, result.message, result.sender, isManual = true)
                val finalResult = scanResult.copy(id = result.id, sender = result.sender, timestamp = result.timestamp)

                val hasDlData = finalResult.cnnScore != null || finalResult.cnnProb != null
                if (hasDlData) {
                    DetectionRepository.addDetection(appContext, finalResult)
                    withContext(Dispatchers.Main) {
                        onComplete?.invoke(finalResult)
                    }
                } else {
                    val errMsg = finalResult.cnnVerdict ?: "API connection failed"
                    withContext(Dispatchers.Main) {
                        onError?.invoke(errMsg)
                    }
                }
            } catch (e: Exception) {
                val err = e.localizedMessage ?: "Unknown error"
                withContext(Dispatchers.Main) {
                    onError?.invoke(err)
                }
            } finally {
                tickerJob.cancel()
                activeScans.remove(key)
                statusTexts.remove(key)
                _statusUpdates.postValue(HashMap(statusTexts))
            }
        }

        activeScans[key] = job
    }
}
