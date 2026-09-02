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

/**
 * Returned by [AnalysisDao.getHostReputation] for VPN enforcement lookups.
 */
data class HostReputationResult(
    val riskLevel: String
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

    @Query("SELECT COUNT(*) FROM sms_message WHERE sms_id NOT LIKE 'synced_%'")
    fun getTotalSmsReceived(): LiveData<Int>

    @Query("SELECT COUNT(fd.decision_id) FROM final_decision fd INNER JOIN sms_message s ON fd.sms_id = s.sms_id WHERE s.sms_id NOT LIKE 'synced_%'")
    fun getTotalSmsScanned(): LiveData<Int>

    @Query("SELECT COUNT(*) FROM final_decision fd INNER JOIN sms_message s ON fd.sms_id = s.sms_id WHERE UPPER(fd.risk_level) = 'SUSPICIOUS' AND s.sms_id NOT LIKE 'synced_%'")
    fun getTotalSuspiciousMessages(): LiveData<Int>

    @Query("SELECT COUNT(*) FROM final_decision fd INNER JOIN sms_message s ON fd.sms_id = s.sms_id WHERE UPPER(fd.risk_level) = 'SMISHING' AND s.sms_id NOT LIKE 'synced_%'")
    fun getTotalSmishingMessages(): LiveData<Int>

    @Query("""
        SELECT 
            (SELECT COUNT(*) FROM sms_message WHERE sms_id NOT LIKE 'synced_%') AS totalReceived,
            (SELECT COUNT(fd.decision_id) FROM final_decision fd INNER JOIN sms_message s ON fd.sms_id = s.sms_id WHERE s.sms_id NOT LIKE 'synced_%') AS totalScanned,
            (SELECT COUNT(*) FROM final_decision fd INNER JOIN sms_message s ON fd.sms_id = s.sms_id WHERE UPPER(fd.risk_level) = 'SUSPICIOUS' AND s.sms_id NOT LIKE 'synced_%') AS totalSuspicious,
            (SELECT COUNT(*) FROM final_decision fd INNER JOIN sms_message s ON fd.sms_id = s.sms_id WHERE UPPER(fd.risk_level) = 'SMISHING' AND s.sms_id NOT LIKE 'synced_%') AS totalSmishing
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

    @Query("SELECT * FROM sms_message WHERE sms_id NOT LIKE 'synced_%' ORDER BY received_timestamp DESC")
    fun getAllSmsLiveData(): LiveData<List<SmsMessageEntity>>

    @Query("SELECT * FROM sms_message WHERE sms_id NOT LIKE 'synced_%' ORDER BY received_timestamp DESC")
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

    /**
     * Reputation lookup used by the VPN enforcement layer.
     *
     * Returns the risk_level of the most recent [FinalDecisionEntity] for any
     * SMS that contained [host] as its [UrlAnalysisEntity.normalizedHost].
     *
     * The VPN calls this on a cache miss and blocks if risk_level == 'SMISHING'.
     */
    @Query("""
        SELECT 
            CASE 
                WHEN ua.is_malicious = 1 THEN 'SMISHING'
                ELSE fd.risk_level 
            END AS riskLevel
        FROM url_analysis ua
        JOIN final_decision fd ON ua.sms_id = fd.sms_id
        WHERE (
            ua.normalized_host = :host 
            OR :host LIKE '%.' || ua.normalized_host 
            OR ua.normalized_host LIKE '%.' || :host
        )
        ORDER BY 
            (CASE WHEN ua.is_malicious = 1 THEN 1 ELSE 0 END) DESC,
            fd.decision_timestamp DESC
        LIMIT 1
    """)
    suspend fun getHostReputation(host: String): HostReputationResult?

    /**
     * Returns true if at least one SMISHING result with a known URL host exists.
     * Used by [com.example.kuwago.MainActivity] to decide whether to suggest
     * enabling URL Shield to the user.
     */
    @Query("""
        SELECT COUNT(*) > 0
        FROM url_analysis ua
        JOIN final_decision fd ON ua.sms_id = fd.sms_id
        WHERE UPPER(fd.risk_level) IN ('SMISHING', 'SUSPICIOUS')
        AND ua.normalized_host IS NOT NULL
    """)
    suspend fun hasSmishingUrlResults(): Boolean
}
