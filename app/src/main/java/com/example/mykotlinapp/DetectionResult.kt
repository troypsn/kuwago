package com.example.mykotlinapp

import java.util.UUID

data class DetectionResult(
    val id: String = UUID.randomUUID().toString(),
    val sender: String,
    val message: String,
    val classification: Classification = Classification.SAFE,
    val probability: Float = 0f,
    val isScanning: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

enum class Classification {
    SAFE,
    SUSPICIOUS,
    SMISHING
}
