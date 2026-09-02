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
            val isUrlMaliciousOrSuspicious =
                (result.urlScore != null && result.urlScore > 0.3f) ||
                (result.urlVerdict != null && !result.urlVerdict.equals("clean", ignoreCase = true) && !result.urlVerdict.equals("benign", ignoreCase = true)) ||
                result.classification == Classification.SMISHING ||
                result.classification == Classification.SUSPICIOUS

            val isMalicious = if (isUrlMaliciousOrSuspicious) 1 else 0
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

        // If a SMISHING or SUSPICIOUS result with a URL host was saved, flag the suggestion dialog
        // so that MainActivity can offer to enable URL Shield on next open.
        val hasThreatUrl = (result.classification == Classification.SMISHING || result.classification == Classification.SUSPICIOUS) &&
                urlEntities.any { it.normalizedHost != null }
        if (hasThreatUrl) {
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
                    val isMalicious = urls.firstOrNull()?.isMalicious == 1
                    val firstUrl = urls.firstOrNull()?.extractedUrl
                    val urlScore = if (urls.isNotEmpty()) (if (isMalicious) 1.0f else 0.0f) else null
                    val urlVerdict = if (urls.isNotEmpty()) (if (isMalicious) "malicious" else "clean") else null

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
                        urlScore = urlScore,
                        urlVerdict = urlVerdict,
                        explanation = if (isMalicious) "Flagged as malicious by local URL reputation database." else null,
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

    /**
     * Syncs pre-analyzed URLs from the backend (URLs created/analyzed in the last 3 months).
     * Populates both the local encrypted Room database and the in-memory UrlReputationCache
     * for instant VPN enforcement.
     */
    suspend fun syncUrlReputationsFromBackend(context: Context) {
        withContext(Dispatchers.IO) {
            try {
                // Calculate timestamp for 3 months ago (90 days in milliseconds)
                val threeMonthsAgoMs = System.currentTimeMillis() - (90L * 24 * 60 * 60 * 1000)
                android.util.Log.i("SmsLocalRepository", "Syncing URL reputations older than 3 months (since_timestamp=$threeMonthsAgoMs)...")
                val response = com.example.kuwago.network.RetrofitClient.instance.syncUrlReputations(threeMonthsAgoMs)
                android.util.Log.i("SmsLocalRepository", "Received ${response.urls.size} pre-analyzed URLs from backend sync")

                val db = getDatabase(context)
                for (item in response.urls) {
                    val rawUrl = item.extractedUrl.trim()
                    if (rawUrl.isEmpty()) continue

                    val host = item.normalizedHost ?: UrlNormalizer.extractHost(rawUrl) ?: continue

                    val isMaliciousOrSuspicious =
                        item.verdict?.equals("malicious", ignoreCase = true) == true ||
                        item.verdict?.equals("spam", ignoreCase = true) == true ||
                        (item.score != null && item.score >= 0.5f)

                    val classification = if (isMaliciousOrSuspicious) Classification.SMISHING else Classification.SAFE

                    // Populate in-memory LRU cache for VPN
                    com.example.kuwago.UrlReputationCache.put(host, classification)

                    // Ensure record exists in local database so getHostReputation query succeeds
                    val syntheticSmsId = "synced_${Math.abs(host.hashCode())}"
                    val smsEntity = SmsMessageEntity(
                        smsId = syntheticSmsId,
                        senderNumber = "Kuwago Database",
                        messageContent = "Pre-synced URL reputation: $rawUrl",
                        receivedTimestamp = System.currentTimeMillis(),
                        isProcessed = 1
                    )
                    db.smsDao().insertSms(smsEntity)

                    val urlEntity = UrlAnalysisEntity(
                        urlId = "url_$syntheticSmsId",
                        smsId = syntheticSmsId,
                        extractedUrl = rawUrl,
                        isMalicious = if (isMaliciousOrSuspicious) 1 else 0,
                        normalizedHost = host
                    )
                    db.analysisDao().insertUrlAnalyses(listOf(urlEntity))

                    val decisionEntity = FinalDecisionEntity(
                        decisionId = "dec_$syntheticSmsId",
                        smsId = syntheticSmsId,
                        finalScore = item.score ?: (if (isMaliciousOrSuspicious) 1.0f else 0.0f),
                        riskLevel = classification.name,
                        actionTaken = "SYNCED",
                        decisionTimestamp = System.currentTimeMillis()
                    )
                    db.analysisDao().insertFinalDecision(decisionEntity)
                }
            } catch (e: Exception) {
                android.util.Log.w("SmsLocalRepository", "URL reputation sync warning: ${e.message}")
            }
        }
    }
}
