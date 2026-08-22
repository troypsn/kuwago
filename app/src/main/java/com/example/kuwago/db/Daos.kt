package com.example.kuwago.db

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

data class HomeStats(
    val totalReceived: Int = 0,
    val totalScanned: Int = 0,
    val totalSuspicious: Int = 0,
    val totalSmishing: Int = 0
)

data class FullSmsRecord(
    val sms: SmsMessageEntity,
    val analysis: AnalysisResultEntity?,
    val urls: List<UrlAnalysisEntity>,
    val decision: FinalDecisionEntity?,
    val notification: NotificationEntity?
)

@Dao
interface HomeStatsDao {

    @Query("SELECT COUNT(*) FROM sms_message")
    fun getTotalSmsReceived(): LiveData<Int>

    @Query("SELECT COUNT(fd.decision_id) FROM final_decision fd INNER JOIN sms_message s ON fd.sms_id = s.sms_id")
    fun getTotalSmsScanned(): LiveData<Int>

    @Query("SELECT COUNT(*) FROM final_decision WHERE UPPER(risk_level) = 'SUSPICIOUS'")
    fun getTotalSuspiciousMessages(): LiveData<Int>

    @Query("SELECT COUNT(*) FROM final_decision WHERE UPPER(risk_level) = 'SMISHING'")
    fun getTotalSmishingMessages(): LiveData<Int>

    @Query("""
        SELECT 
            (SELECT COUNT(*) FROM sms_message) AS totalReceived,
            (SELECT COUNT(fd.decision_id) FROM final_decision fd INNER JOIN sms_message s ON fd.sms_id = s.sms_id) AS totalScanned,
            (SELECT COUNT(*) FROM final_decision WHERE UPPER(risk_level) = 'SUSPICIOUS') AS totalSuspicious,
            (SELECT COUNT(*) FROM final_decision WHERE UPPER(risk_level) = 'SMISHING') AS totalSmishing
    """)
    fun getHomeStatsLiveData(): LiveData<HomeStats>
}

@Dao
interface SmsDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSms(sms: SmsMessageEntity): Long

    @Update
    suspend fun updateSms(sms: SmsMessageEntity)

    @Query("UPDATE sms_message SET is_processed = :isProcessed WHERE sms_id = :smsId")
    suspend fun updateProcessingState(smsId: String, isProcessed: Int)

    @Query("SELECT * FROM sms_message WHERE sms_id = :smsId")
    suspend fun getSmsById(smsId: String): SmsMessageEntity?

    @Query("SELECT * FROM sms_message ORDER BY received_timestamp DESC")
    fun getAllSmsLiveData(): LiveData<List<SmsMessageEntity>>

    @Query("SELECT * FROM sms_message ORDER BY received_timestamp DESC")
    suspend fun getAllSmsList(): List<SmsMessageEntity>

    @Query("DELETE FROM sms_message WHERE sms_id = :smsId")
    suspend fun deleteSms(smsId: String)
}

@Dao
interface AnalysisDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnalysisResult(analysis: AnalysisResultEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUrlAnalyses(urls: List<UrlAnalysisEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFinalDecision(decision: FinalDecisionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotification(notification: NotificationEntity)

    @Query("SELECT * FROM analysis_result WHERE sms_id = :smsId")
    suspend fun getAnalysisResultBySmsId(smsId: String): AnalysisResultEntity?

    @Query("SELECT * FROM url_analysis WHERE sms_id = :smsId")
    suspend fun getUrlAnalysesBySmsId(smsId: String): List<UrlAnalysisEntity>

    @Query("SELECT * FROM final_decision WHERE sms_id = :smsId")
    suspend fun getFinalDecisionBySmsId(smsId: String): FinalDecisionEntity?

    @Query("SELECT * FROM notification WHERE decision_id = :decisionId")
    suspend fun getNotificationByDecisionId(decisionId: String): NotificationEntity?

    @Query("SELECT * FROM final_decision ORDER BY decision_timestamp DESC")
    fun getAllFinalDecisionsLiveData(): LiveData<List<FinalDecisionEntity>>
}
