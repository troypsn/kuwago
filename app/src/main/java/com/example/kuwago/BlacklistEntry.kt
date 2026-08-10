package com.example.kuwago

/**
 * Represents a single entry in the user's blacklist.
 */
data class BlacklistEntry(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sender: String,
    val riskLevel: RiskLevel,
    val flaggedCount: Int,
    val method: BlacklistMethod,
    val timestamp: Long = System.currentTimeMillis()
)

enum class RiskLevel {
    HIGH,
    MEDIUM,
    LOW
}

enum class BlacklistMethod {
    MANUAL
}
