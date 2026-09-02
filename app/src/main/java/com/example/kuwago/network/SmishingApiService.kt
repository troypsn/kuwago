package com.example.kuwago.network

import com.google.gson.annotations.SerializedName
import retrofit2.http.Body
import retrofit2.http.POST

data class SmsScanRequest(
    val message: String,
    @SerializedName("has_url") val hasUrl: Boolean = false,
    @SerializedName("extracted_url") val extractedUrl: String? = null,
    @SerializedName("allow_save") val allowSave: Boolean = false,
    val sender: String? = null,
    @SerializedName("ml_prediction") val mlPrediction: String? = null,
    @SerializedName("ml_confidence") val mlConfidence: Float? = null
)

data class CnnAnalysis(
    @SerializedName("score") val score: Float = 0f,
    @SerializedName("verdict") val verdict: String = "benign"
)

data class UrlAnalysis(
    @SerializedName("has_url") val hasUrl: Boolean = false,
    @SerializedName("extracted_url") val extractedUrl: String? = null,
    @SerializedName("score") val score: Float? = null,
    @SerializedName("verdict") val verdict: String? = null,
    @SerializedName("total_weight") val totalWeight: Float? = null,
    @SerializedName("explanation") val explanation: String? = null,
    @SerializedName("contributions") val contributions: List<String>? = emptyList()
)

data class SmsScanResponse(
    @SerializedName("message") val message: String = "",
    @SerializedName("cnn_analysis") val cnnAnalysis: CnnAnalysis? = null,
    @SerializedName("url_analysis") val urlAnalysis: UrlAnalysis? = null
)

data class UrlSyncItem(
    @SerializedName("extracted_url") val extractedUrl: String = "",
    @SerializedName("normalized_host") val normalizedHost: String? = null,
    @SerializedName("verdict") val verdict: String? = null,
    @SerializedName("score") val score: Float? = null,
    @SerializedName("total_weight") val totalWeight: Float? = null,
    @SerializedName("explanation") val explanation: String? = null,
    @SerializedName("contributions") val contributions: List<String>? = emptyList()
)

data class UrlSyncResponse(
    @SerializedName("total_records") val totalRecords: Int = 0,
    @SerializedName("last_synced_at") val lastSyncedAt: String? = null,
    @SerializedName("urls") val urls: List<UrlSyncItem> = emptyList()
)

interface SmishingApiService {
    @POST("scan-sms")
    suspend fun scanSms(@Body request: SmsScanRequest): SmsScanResponse

    @retrofit2.http.GET("url-reputations")
    suspend fun syncUrlReputations(
        @retrofit2.http.Query("since_timestamp") sinceTimestamp: Long
    ): UrlSyncResponse
}
