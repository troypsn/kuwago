package com.example.kuwago

import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * Comprehensive unit test for the Smishing Detector.
 *
 * Tests the full ML pipeline (TF-IDF → Scaler → Random Forest + XGBoost)
 * against real-world safe and phishing messages.
 *
 * Run this in Android Studio: Right-click this file → Run 'SmishingDetectorTest'
 * No emulator or phone needed!
 */
class SmishingDetectorTest {

    @Before
    fun setup() {
        LocalClassifier.initialize(null)
    }

    // ==========================================
    // HELPER
    // ==========================================

    private fun classifyAndPrint(label: String, message: String): DetectionResult {
        val result = LocalClassifier.classify(null, message)
        val status = when (result.classification) {
            Classification.SAFE -> "✅ SAFE"
            Classification.SUSPICIOUS -> "⚠️ SUSPICIOUS"
            Classification.SMISHING -> "🚨 SMISHING"
        }
        println("[$label] $status (${String.format("%.1f%%", result.probability * 100)}) → \"${message.take(60)}...\"")
        return result
    }

    // ==========================================
    // SAFE MESSAGES - Should all be < 70%
    // ==========================================

    private val safeMessages = listOf(
        // Casual Filipino conversations
        "prro feeling a lot better naa!",
        "Uy pre, anong oras tayo magkikita mamaya?",
        "Haha oo nga eh. Kain tayo sa Jollibee mamaya",
        "Salamat sa tulong mo kanina, ang bait mo talaga",
        "Nakauwi ka na ba? Ingat ka palagi ha",
        "Happy birthday sayo! God bless you always!",
        "Kumusta na yung anak mo? Malaki na siguro",
        "Miss ko na kayo! Kelan tayo magkikita ulit?",
        "Uy may pasok ba bukas? Di ko sure eh",
        "Ge sige, text mo na lang ako pag free ka",

        // Casual English conversations
        "Hey are you free for coffee later today?",
        "Just got home from work, super tired lol",
        "Can you pick up some groceries on the way home?",
        "Happy anniversary! Love you so much!",
        "The weather is so nice today, let's go to the park",
        "Did you watch the game last night? It was amazing!",
        "Running late, be there in 10 minutes sorry!",
        "Thanks for dinner last night, it was delicious",
        "Good morning! Hope you have a great day ahead",
        "See you at the meeting tomorrow at 3pm",

        // Legitimate business/service messages
        "Your GrabFood order is on the way. Estimated arrival: 25 mins",
        "Reminder: Your dental appointment is scheduled for Aug 10 at 2PM",
        "Your Shopee order has been shipped. Track it in the app.",
        "Globe: You have used 80% of your data allocation for this month.",
        "SM Supermalls: Sale starts this weekend! Visit your nearest SM store."
    )

    // ==========================================
    // PHISHING MESSAGES - Should all be >= 70%
    // ==========================================

    private val phishingMessages = listOf(
        // Classic bank phishing
        "URGENT: Your BDO account has been locked due to suspicious activity. Verify now at http://bdo-verify.com/login to avoid suspension",
        "BPI ALERT: Unauthorized transaction detected on your account. Click here immediately to secure your funds: http://bit.ly/bpi-secure",
        "Your Metrobank card will be deactivated in 24 hours. Update your information now: http://metrobank-update.net",
        "LANDBANK: Your online banking access will expire today. Re-verify at http://landbank-portal.com/verify",
        "RCBC Notice: We detected unusual login attempts. Confirm your identity: http://rcbc-confirm.xyz",

        // GCash/Maya scams
        "GCASH: CONGRATULATIONS! You won 10,000 PHP! Claim your reward now at http://gcash-rewards.com before it expires!",
        "Maya: Your account has been compromised. Verify immediately at http://maya-secure.net to prevent unauthorized access",
        "GCASH PROMO: You are selected to receive FREE 5000 PHP load! Click to claim: http://bit.ly/gcash-free-load",
        "Paymaya: Urgent security update required. Your wallet will be frozen. Update now: http://paymaya-update.com",
        "GCash Alert: Someone tried to access your account. Verify your identity here: http://gcash-verify-login.com",

        // Telco scams
        "SMART: Congratulations! You won a brand new iPhone 15! Claim here: http://smart-promo-winner.com",
        "Globe Telecom: Your SIM will be deactivated. Register now to keep your number: http://globe-register.net",
        "TNT PROMO: You have been selected for unlimited data for 1 year FREE! Activate now: http://tnt-activate.com",

        // Generic phishing with urgency
        "NOTICE: Your account will be permanently deleted. Verify within 24 hours: http://account-verify.xyz",
        "WARNING: Suspicious login detected on your device. Secure your account immediately: http://secure-login.net",
        "You have an unclaimed package. Pay the delivery fee of 50 PHP to receive it: http://delivery-fee.com",
        "URGENT: You have an outstanding balance. Pay now to avoid legal action: http://pay-balance.net",
        "Your subscription is about to expire. Renew now at a special discount: http://renew-subscription.xyz",
        "FINAL NOTICE: Your insurance policy will lapse today. Renew immediately: http://insurance-renew.com",
        "Congratulations! You've been pre-approved for a 500,000 PHP loan! Apply now: http://easy-loan.net",

        // Filipino-language phishing
        "PANALO KA! Manalo ng 50,000 PHP mula sa Globe promo! I-click para kunin ang premyo: http://globe-panalo.com",
        "AGAD na i-verify ang iyong account para maiwasan ang suspension. Mag-login dito: http://verify-agad.net",
        "Libreng load! Mag-click dito para makuha ang 1000 PHP free load: http://libre-load.com",
        "I-update ang iyong GCash account ngayon. Mag-click dito: http://gcash-update.xyz bago mag-expire",
        "HULING ARAW! I-claim ang iyong reward bago mawala. Pumunta sa: http://claim-reward.net"
    )

    // ==========================================
    // TESTS
    // ==========================================

    @Test
    fun allSafeMessagesShouldBeClassifiedAsSafe() {
        println("\n========== SAFE MESSAGES ==========")
        var failures = 0

        for ((i, msg) in safeMessages.withIndex()) {
            val result = classifyAndPrint("SAFE #${i + 1}", msg)
            if (result.classification != Classification.SAFE) {
                failures++
                println("   ❌ FALSE POSITIVE! Expected SAFE but got ${result.classification}")
            }
        }

        println("\n--- SAFE RESULTS: ${safeMessages.size - failures}/${safeMessages.size} correct ---")
        if (failures > 0) {
            println("⚠️ $failures safe message(s) were incorrectly flagged!")
        }
        // Allow up to 2 false positives (some edge cases are okay)
        assertTrue(
            "Too many false positives: $failures out of ${safeMessages.size} safe messages were incorrectly flagged",
            failures <= 2
        )
    }

    @Test
    fun allPhishingMessagesShouldBeFlagged() {
        println("\n========== PHISHING MESSAGES ==========")
        var failures = 0

        for ((i, msg) in phishingMessages.withIndex()) {
            val result = classifyAndPrint("PHISH #${i + 1}", msg)
            if (result.classification == Classification.SAFE) {
                failures++
                println("   ❌ MISSED! Expected SUSPICIOUS/SMISHING but got SAFE")
            }
        }

        println("\n--- PHISHING RESULTS: ${phishingMessages.size - failures}/${phishingMessages.size} caught ---")
        if (failures > 0) {
            println("⚠️ $failures phishing message(s) were missed!")
        }
        // Allow up to 3 misses (some sophisticated phishing may slip through)
        assertTrue(
            "Too many missed phishing: $failures out of ${phishingMessages.size} phishing messages were not caught",
            failures <= 3
        )
    }

    @Test
    fun printFullSummary() {
        println("\n╔══════════════════════════════════════════╗")
        println("║     KUWAGO SMISHING DETECTOR TEST        ║")
        println("║     Thresholds: SAFE<70% | SMISH≥85%     ║")
        println("╚══════════════════════════════════════════╝")

        var safeCorrect = 0
        var phishCorrect = 0

        println("\n── SAFE MESSAGES ──")
        for ((i, msg) in safeMessages.withIndex()) {
            val result = classifyAndPrint("SAFE #${i + 1}", msg)
            if (result.classification == Classification.SAFE) safeCorrect++
        }

        println("\n── PHISHING MESSAGES ──")
        for ((i, msg) in phishingMessages.withIndex()) {
            val result = classifyAndPrint("PHISH #${i + 1}", msg)
            if (result.classification != Classification.SAFE) phishCorrect++
        }

        val totalCorrect = safeCorrect + phishCorrect
        val totalMessages = safeMessages.size + phishingMessages.size
        val accuracy = (totalCorrect.toFloat() / totalMessages * 100)

        println("\n╔══════════════════════════════════════════╗")
        println("║              FINAL RESULTS               ║")
        println("╠══════════════════════════════════════════╣")
        println("║  Safe correctly identified:  $safeCorrect / ${safeMessages.size}".padEnd(44) + "║")
        println("║  Phishing correctly caught:  $phishCorrect / ${phishingMessages.size}".padEnd(44) + "║")
        println("║  Overall Accuracy:           ${String.format("%.1f%%", accuracy)}".padEnd(44) + "║")
        println("║  False Positives:            ${safeMessages.size - safeCorrect}".padEnd(44) + "║")
        println("║  Missed Phishing:            ${phishingMessages.size - phishCorrect}".padEnd(44) + "║")
        println("╚══════════════════════════════════════════╝")
    }
}
