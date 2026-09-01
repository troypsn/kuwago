package com.example.kuwago.db

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import com.example.kuwago.Classification
import com.example.kuwago.DetectionResult
import com.example.kuwago.LocalClassifier
import com.example.kuwago.UrlNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.room.withTransaction
import java.util.UUID

object SmsLocalRepository {

    fun getDatabase(context: Context): AppDatabase {
        return AppDatabase.getInstance(context)
    }

    fun getHomeStatsLiveData(context: Context): LiveData<HomeStats> {
        return getDatabase(context).homeStatsDao().getHomeStatsLiveData()
    }

    suspend fun saveSmsReceived(
        context: Context,
        smsId: String,
        sender: String,
        messageContent: String,
        timestamp: Long = System.currentTimeMillis()
    ) = withContext(Dispatchers.IO) {
        val db = getDatabase(context)
        val smsEntity = SmsMessageEntity(
            smsId = smsId,
            senderNumber = sender,
            messageContent = messageContent,
            receivedTimestamp = timestamp,
            isProcessed = 0
        )
        db.smsDao().insertSms(smsEntity)
        smsEntity
    }

    suspend fun recordAnalysisFailure(
        context: Context,
        smsId: String
    ) = withContext(Dispatchers.IO) {
        val db = getDatabase(context)
        db.smsDao().updateProcessingState(smsId, 0)
    }

    suspend fun saveAnalysisComplete(
        context: Context,
        result: DetectionResult,
        actionTaken: String = "PROCESSED",
        alertMessage: String? = null,
        alertColor: String? = null
    ) = withContext(Dispatchers.IO) {
        val db = getDatabase(context)
        val smsDao = db.smsDao()
        val analysisDao = db.analysisDao()

        // 1. Ensure SmsMessage exists
        var existingSms = smsDao.getSmsById(result.id)
        if (existingSms == null) {
            existingSms = SmsMessageEntity(
                smsId = result.id,
                senderNumber = result.sender,
                messageContent = result.message,
                receivedTimestamp = result.timestamp,
                isProcessed = 0
            )
            smsDao.insertSms(existingSms)
        }

        // 2. Build AnalysisResultEntity
        val analysisEntity = AnalysisResultEntity(
            analysisId = UUID.randomUUID().toString(),
            smsId = result.id,
            mlPrediction = result.localVerdict ?: result.classification.name,
            mlConfidence = result.rfProb,
            dlPrediction = result.cnnVerdict,
            dlConfidence = result.cnnScore ?: result.cnnProb
        )

        // 3. Build UrlAnalysisEntity list
        val urlEntities = mutableListOf<UrlAnalysisEntity>()
        val hasUrl = LocalClassifier.hasUrl(result.message) || result.urlFound
        val extractedUrl = result.extractedUrl ?: LocalClassifier.extractUrl(result.message)
        if (hasUrl && !extractedUrl.isNullOrBlank()) {
            val isMalicious = if (result.urlScore != null && result.urlScore > 0.5f) 1 else 0
            // Normalize the hostname so the VPN can match it by host at enforcement time
            val normalizedHost = UrlNormalizer.extractHost(extractedUrl.trim())
            urlEntities.add(
                UrlAnalysisEntity(
                    urlId = UUID.randomUUID().toString(),
                    smsId = result.id,
                    extractedUrl = extractedUrl.trim(),
                    isMalicious = isMalicious,
                    normalizedHost = normalizedHost
                )
            )
        }

        // 4. Build FinalDecisionEntity
        val decisionId = UUID.randomUUID().toString()
        val decisionEntity = FinalDecisionEntity(
            decisionId = decisionId,
            smsId = result.id,
            finalScore = result.probability,
            riskLevel = result.classification.name,
            actionTaken = actionTaken,
            decisionTimestamp = System.currentTimeMillis()
        )

        // 5. Optional NotificationEntity
        val notifEntity = if (!alertMessage.isNullOrBlank() && !alertColor.isNullOrBlank()) {
            NotificationEntity(
                notificationId = UUID.randomUUID().toString(),
                decisionId = decisionId,
                alertMessage = alertMessage,
                alertColor = alertColor,
                shownTimestamp = System.currentTimeMillis()
            )
        } else null

        // Execute as an atomic database transaction
        db.withTransaction {
            kotlin.runCatching {
                analysisDao.insertAnalysisResult(analysisEntity)
                if (urlEntities.isNotEmpty()) {
                    analysisDao.insertUrlAnalyses(urlEntities)
                }
                analysisDao.insertFinalDecision(decisionEntity)
                if (notifEntity != null) {
                    analysisDao.insertNotification(notifEntity)
                }
                // Mark processed ONLY after successful insertion of final_decision
                db.openHelper.writableDatabase.execSQL(
                    "UPDATE sms_message SET is_processed = 1 WHERE sms_id = ?",
                    arrayOf(result.id)
                )
            }
        }

        // If a SMISHING result with a URL host was saved, flag the suggestion dialog
        // so that MainActivity can offer to enable URL Shield on next open.
        val hasMaliciousUrl = result.classification == Classification.SMISHING &&
                urlEntities.any { it.normalizedHost != null }
        if (hasMaliciousUrl) {
            context.getSharedPreferences("kuwago_vpn_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("suggest_vpn_shield", true)
                .apply()
        }
    }

    fun getAllDetectionsLiveData(context: Context): LiveData<List<DetectionResult>> {
        val db = getDatabase(context)
        val smsLiveData = db.smsDao().getAllSmsLiveData()
        val resultMediator = MediatorLiveData<List<DetectionResult>>()

        resultMediator.addSource(smsLiveData) { smsList ->
            val contextRef = context.applicationContext
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                val detectionResults = smsList.map { sms ->
                    val analysis = db.analysisDao().getAnalysisResultBySmsId(sms.smsId)
                    val urls = db.analysisDao().getUrlAnalysesBySmsId(sms.smsId)
                    val decision = db.analysisDao().getFinalDecisionBySmsId(sms.smsId)

                    val classification = decision?.riskLevel?.let {
                        try { Classification.valueOf(it) } catch (_: Exception) { Classification.SAFE }
                    } ?: Classification.SAFE

                    val prob = decision?.finalScore ?: analysis?.mlConfidence ?: 0f
                    val firstUrl = urls.firstOrNull()?.extractedUrl

                    DetectionResult(
                        id = sms.smsId,
                        sender = sms.senderNumber,
                        message = sms.messageContent,
                        classification = classification,
                        probability = prob,
                        isScanning = sms.isProcessed == 0,
                        timestamp = sms.receivedTimestamp,
                        cnnScore = analysis?.dlConfidence,
                        cnnVerdict = analysis?.dlPrediction,
                        urlFound = urls.isNotEmpty(),
                        extractedUrl = firstUrl,
                        urlScore = urls.firstOrNull()?.isMalicious?.toFloat(),
                        localVerdict = analysis?.mlPrediction,
                        rfProb = analysis?.mlConfidence ?: 0f
                    )
                }
                withContext(Dispatchers.Main) {
                    resultMediator.value = detectionResults
                }
            }
        }

        return resultMediator
    }
}
