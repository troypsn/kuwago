package com.example.kuwago

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.kuwago.db.SmsLocalRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object DetectionRepository {
    private const val MAX_DETECTIONS = 200
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _detections = MutableLiveData<List<DetectionResult>>(emptyList())
    val detections: LiveData<List<DetectionResult>> = _detections

    private var appContext: Context? = null
    private var isLoaded = false

    @Synchronized
    fun loadIfNeeded(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
        val ctx = appContext ?: context.applicationContext
        if (isLoaded) return

        // Observe Room DB LiveData reactively
        SmsLocalRepository.getAllDetectionsLiveData(ctx).observeForever { dbResults ->
            val list = dbResults.take(MAX_DETECTIONS)
            _detections.postValue(list)
        }
        isLoaded = true
    }

    fun addDetection(context: Context? = null, result: DetectionResult) {
        val ctx = context?.applicationContext ?: appContext ?: return
        loadIfNeeded(ctx)
        scope.launch {
            if (result.isScanning) {
                SmsLocalRepository.saveSmsReceived(
                    context = ctx,
                    smsId = result.id,
                    sender = result.sender,
                    messageContent = result.message,
                    timestamp = result.timestamp
                )
            } else {
                SmsLocalRepository.saveAnalysisComplete(
                    context = ctx,
                    result = result
                )
            }
        }
    }

    fun addDetection(result: DetectionResult) {
        addDetection(null, result)
    }

    fun addDetections(context: Context? = null, results: List<DetectionResult>) {
        val ctx = context?.applicationContext ?: appContext ?: return
        loadIfNeeded(ctx)
        scope.launch {
            for (res in results) {
                if (res.isScanning) {
                    SmsLocalRepository.saveSmsReceived(
                        context = ctx,
                        smsId = res.id,
                        sender = res.sender,
                        messageContent = res.message,
                        timestamp = res.timestamp
                    )
                } else {
                    SmsLocalRepository.saveAnalysisComplete(
                        context = ctx,
                        result = res
                    )
                }
            }
        }
    }

    fun addDetections(results: List<DetectionResult>) {
        addDetections(null, results)
    }

    fun updateDetection(context: Context? = null, updatedResult: DetectionResult) {
        addDetection(context, updatedResult)
    }

    fun updateDetection(updatedResult: DetectionResult) {
        updateDetection(null, updatedResult)
    }
}
