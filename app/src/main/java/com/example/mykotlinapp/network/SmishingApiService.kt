package com.example.mykotlinapp.network

import retrofit2.http.Body
import retrofit2.http.POST

data class SmsScanRequest(
    val message: String
)

data class SmsScanResponse(
    val message: String,
    val verdict: String,
    val probability: Float
)

interface SmishingApiService {
    @POST("scan-sms")
    suspend fun scanSms(@Body request: SmsScanRequest): SmsScanResponse
}
