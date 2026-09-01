package com.example.kuwago.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "sms_message",
    indices = [
        Index(value = ["received_timestamp"]),
        Index(value = ["is_processed"])
    ]
)
data class SmsMessageEntity(
    @PrimaryKey
    @ColumnInfo(name = "sms_id")
    val smsId: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "sender_number")
    val senderNumber: String,

    @ColumnInfo(name = "message_content")
    val messageContent: String,

    @ColumnInfo(name = "received_timestamp")
    val receivedTimestamp: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "is_processed", defaultValue = "0")
    val isProcessed: Int = 0
)

@Entity(
    tableName = "analysis_result",
    foreignKeys = [
        ForeignKey(
            entity = SmsMessageEntity::class,
            parentColumns = ["sms_id"],
            childColumns = ["sms_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["sms_id"], unique = true)
    ]
)
data class AnalysisResultEntity(
    @PrimaryKey
    @ColumnInfo(name = "analysis_id")
    val analysisId: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "sms_id")
    val smsId: String,

    @ColumnInfo(name = "ml_prediction")
    val mlPrediction: String?,

    @ColumnInfo(name = "ml_confidence")
    val mlConfidence: Float?,

    @ColumnInfo(name = "dl_prediction")
    val dlPrediction: String?,

    @ColumnInfo(name = "dl_confidence")
    val dlConfidence: Float?
)

@Entity(
    tableName = "url_analysis",
    foreignKeys = [
        ForeignKey(
            entity = SmsMessageEntity::class,
            parentColumns = ["sms_id"],
            childColumns = ["sms_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["sms_id"]),
        Index(value = ["extracted_url"], name = "idx_url_analysis_extracted_url"),
        Index(value = ["normalized_host"],  name = "idx_url_analysis_host")
    ]
)
data class UrlAnalysisEntity(
    @PrimaryKey
    @ColumnInfo(name = "url_id")
    val urlId: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "sms_id")
    val smsId: String,

    @ColumnInfo(name = "extracted_url")
    val extractedUrl: String,

    @ColumnInfo(name = "is_malicious", defaultValue = "0")
    val isMalicious: Int = 0,

    /**
     * Canonical hostname extracted by [com.example.kuwago.UrlNormalizer].
     * Stored in lowercased, www-stripped form (e.g. "phishing-site.com").
     * Used by [com.example.kuwago.KuwagoVpnService] for host-based reputation
     * lookups without needing to parse full URLs at query time.
     * Null when no valid hostname could be extracted.
     */
    @ColumnInfo(name = "normalized_host")
    val normalizedHost: String? = null
)

@Entity(
    tableName = "final_decision",
    foreignKeys = [
        ForeignKey(
            entity = SmsMessageEntity::class,
            parentColumns = ["sms_id"],
            childColumns = ["sms_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["sms_id"], unique = true),
        Index(value = ["risk_level"])
    ]
)
data class FinalDecisionEntity(
    @PrimaryKey
    @ColumnInfo(name = "decision_id")
    val decisionId: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "sms_id")
    val smsId: String,

    @ColumnInfo(name = "final_score")
    val finalScore: Float,

    @ColumnInfo(name = "risk_level")
    val riskLevel: String,

    @ColumnInfo(name = "action_taken")
    val actionTaken: String?,

    @ColumnInfo(name = "decision_timestamp")
    val decisionTimestamp: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "notification",
    foreignKeys = [
        ForeignKey(
            entity = FinalDecisionEntity::class,
            parentColumns = ["decision_id"],
            childColumns = ["decision_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["decision_id"])
    ]
)
data class NotificationEntity(
    @PrimaryKey
    @ColumnInfo(name = "notification_id")
    val notificationId: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "decision_id")
    val decisionId: String,

    @ColumnInfo(name = "alert_message")
    val alertMessage: String,

    @ColumnInfo(name = "alert_color")
    val alertColor: String,

    @ColumnInfo(name = "shown_timestamp")
    val shownTimestamp: Long = System.currentTimeMillis()
)
