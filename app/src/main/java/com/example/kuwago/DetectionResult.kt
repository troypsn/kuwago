package com.example.kuwago

import java.util.UUID

data class DetectionResult(
    val id: String = UUID.randomUUID().toString(),
    val sender: String,
    val message: String,
    val classification: Classification = Classification.SAFE,
    val probability: Float = 0f,
    val isScanning: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val rfProb: Float = 0f,
    val rfRawLogit: Float = 0f,
    val xgbProb: Float = 0f,
    val cnnProb: Float? = null
)

enum class Classification {
    SAFE,
    SUSPICIOUS,
    SMISHING
}
