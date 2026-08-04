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
    
    // Detailed Analysis
    val cnnScore: Float? = null,
    val cnnVerdict: String? = null,
    val urlFound: Boolean = false,
    val extractedUrl: String? = null,
    val urlScore: Float? = null,
    val urlVerdict: String? = null,
    val explanation: String? = null,
    val localVerdict: String? = null,
    val ensembleFormula: String? = null,

    // Local Model Scores
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
