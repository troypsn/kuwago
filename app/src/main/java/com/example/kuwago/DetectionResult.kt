package com.example.kuwago

import java.io.Serializable
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
    val overallExplanation: String? = null,
    val localVerdict: String? = null,
    val ensembleFormula: String? = null,
    val urlTotalWeight: Float? = null,
    val urlContributions: List<String>? = null,

    // Local Model Scores
    val rfProb: Float = 0f,
    val rfRawLogit: Float = 0f,
    val xgbProb: Float = 0f,
    val cnnProb: Float? = null
) : Serializable {
    fun getEffectiveClassification(): Classification {
        if (classification == Classification.SMISHING && (urlVerdict?.equals("malicious", ignoreCase = true) == true && (explanation?.contains("shorten", ignoreCase = true) == true || overallExplanation?.contains("shorten", ignoreCase = true) == true))) {
            return Classification.SMISHING
        }
        val score = calculateEnsembleScore()
        return when {
            score >= LocalClassifier.smishingThreshold -> Classification.SMISHING
            score >= LocalClassifier.suspiciousThreshold -> Classification.SUSPICIOUS
            else -> Classification.SAFE
        }
    }

    fun getClassificationLabel(): String {
        return when (getEffectiveClassification()) {
            Classification.SMISHING -> "Harmful"
            Classification.SUSPICIOUS -> "Suspicious"
            Classification.SAFE -> "Safe"
        }
    }

    fun getClassificationBadgeText(): String {
        return when (getEffectiveClassification()) {
            Classification.SMISHING -> "HARMFUL"
            Classification.SUSPICIOUS -> "SUSPICIOUS"
            Classification.SAFE -> "SAFE"
        }
    }

    fun calculateEnsembleScore(): Float {
        val isShortenedHarmful = classification == Classification.SMISHING &&
                urlVerdict?.equals("malicious", ignoreCase = true) == true &&
                (explanation?.contains("shorten", ignoreCase = true) == true || overallExplanation?.contains("shorten", ignoreCase = true) == true)

        val mlScore = if (rfProb > 0f || xgbProb > 0f) 0.75f * rfProb + 0.25f * xgbProb else probability
        val dlScore = cnnScore ?: cnnProb
        val hasDl = dlScore != null
        val hasUrl = urlFound && urlScore != null

        val computed = when {
            hasDl && hasUrl -> (0.50f * dlScore!!) + (0.25f * urlScore!!) + (0.25f * mlScore)
            hasDl -> (2.0f / 3.0f * dlScore!!) + (1.0f / 3.0f * mlScore)
            hasUrl -> (0.50f * urlScore!!) + (0.50f * mlScore)
            else -> mlScore
        }

        return if (isShortenedHarmful) maxOf(computed, 0.95f) else computed
    }
}

enum class Classification : Serializable {
    SAFE,
    SUSPICIOUS,
    SMISHING
}

