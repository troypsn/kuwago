package com.example.kuwago

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Stores pending smishing warnings so they can be shown as a popup
 * when the user opens the app, instead of interrupting them in real-time.
 */
object PendingWarningRepository {

    private const val PREFS_NAME = "kuwago_pending_warnings"
    private const val KEY_HAS_WARNING = "has_pending_warning"
    private const val KEY_SENDER = "pending_sender"
    private const val KEY_MESSAGE = "pending_message"
    private const val KEY_CONFIDENCE = "pending_confidence"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Save a pending warning from the background service.
     * Only the most recent unhandled warning is kept (newest overwrites older).
     */
    fun savePendingWarning(context: Context, sender: String, message: String, confidence: String) {
        getPrefs(context).edit()
            .putBoolean(KEY_HAS_WARNING, true)
            .putString(KEY_SENDER, sender)
            .putString(KEY_MESSAGE, message)
            .putString(KEY_CONFIDENCE, confidence)
            .apply()
        Log.i("PendingWarningRepo", "Saved pending warning for sender: $sender")
    }

    /**
     * Check if there is a pending warning that hasn't been shown yet.
     */
    fun hasPendingWarning(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_HAS_WARNING, false)
    }

    /**
     * Retrieve the pending warning data. Returns null if there is none.
     */
    fun getPendingWarning(context: Context): PendingWarning? {
        val prefs = getPrefs(context)
        if (!prefs.getBoolean(KEY_HAS_WARNING, false)) return null

        return PendingWarning(
            sender = prefs.getString(KEY_SENDER, "Unknown") ?: "Unknown",
            message = prefs.getString(KEY_MESSAGE, "") ?: "",
            confidence = prefs.getString(KEY_CONFIDENCE, "High Threat") ?: "High Threat"
        )
    }

    /**
     * Clear the pending warning after it has been shown to the user.
     */
    fun clearPendingWarning(context: Context) {
        getPrefs(context).edit()
            .putBoolean(KEY_HAS_WARNING, false)
            .remove(KEY_SENDER)
            .remove(KEY_MESSAGE)
            .remove(KEY_CONFIDENCE)
            .apply()
        Log.i("PendingWarningRepo", "Pending warning cleared.")
    }
}

data class PendingWarning(
    val sender: String,
    val message: String,
    val confidence: String
)
