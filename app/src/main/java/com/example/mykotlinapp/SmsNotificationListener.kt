package com.example.mykotlinapp

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SmsNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i("SmsNotificationListener", "-----------------------------------------")
        Log.i("SmsNotificationListener", "NOTIFICATION LISTENER CONNECTED & ACTIVE")
        Log.i("SmsNotificationListener", "-----------------------------------------")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        val notification = sbn.notification
        val extras = notification.extras
        
        Log.d("SmsNotificationListener", ">>> New Notification Detected from: $packageName")
        
        // Log all extras to help identify where the text is hidden
        Log.v("SmsNotificationListener", "Dumping Extras for $packageName:")
        for (key in extras.keySet()) {
            val value = extras.get(key)
            Log.v("SmsNotificationListener", "  [Extra] $key = $value")
        }

        // Try to extract text and title
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: 
                    extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString() ?: "Unknown"
        
        // Check multiple text fields used by different apps/Android versions
        var text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        if (text.isEmpty()) {
            text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        }
        if (text.isEmpty()) {
            // Some messaging apps use EXTRA_MESSAGES which is a list of Parcelables
            text = extras.getCharSequence("android.text")?.toString() ?: ""
        }

        Log.d("SmsNotificationListener", "Extracted Title: $title")
        Log.d("SmsNotificationListener", "Extracted Text: $text")

        if (text.isEmpty()) {
            Log.w("SmsNotificationListener", "Notification text is empty or could not be extracted. Skipping.")
            return
        }

        // Broader filtering to catch variations across manufacturers
        val isLikelyMessage = packageName.contains("message", ignoreCase = true) || 
                             packageName.contains("sms", ignoreCase = true) || 
                             packageName.contains("mms", ignoreCase = true) ||
                             packageName.contains("telephony", ignoreCase = true) ||
                             packageName.contains("chat", ignoreCase = true) ||
                             notification.category == Notification.CATEGORY_MESSAGE

        if (!isLikelyMessage) {
            Log.d("SmsNotificationListener", "Ignoring non-message notification (Category: ${notification.category})")
            return
        }

        // Check if sender is blacklisted -> cancel notification popup immediately
        if (BlacklistRepository.isBlacklisted(this, title)) {
            Log.i("SmsNotificationListener", "BLOCKED & SUPPRESSED notification popup from blacklisted sender: $title")
            try {
                cancelNotification(sbn.key)
            } catch (e: Exception) {
                Log.e("SmsNotificationListener", "Could not cancel notification: ${e.message}")
            }
            return
        }

        Log.i("SmsNotificationListener", "SUCCESS: Found a message to scan. Sender: $title")

        scope.launch {
            try {
                val placeholder = DetectionResult(
                    sender = title,
                    message = text,
                    isScanning = true
                )
                DetectionRepository.addDetection(placeholder)

                val finalResult = SmishingDetector.analyze(this@SmsNotificationListener, text).copy(
                    id = placeholder.id,
                    sender = title
                )

                DetectionRepository.updateDetection(finalResult)
                Log.i("SmsNotificationListener", "Update complete for message: ${placeholder.id}")

                // Check if this sender is high-risk smishing, not blacklisted yet, and unacknowledged
                if (finalResult.classification == Classification.SMISHING &&
                    !BlacklistRepository.isBlacklisted(this@SmsNotificationListener, title) &&
                    !BlacklistRepository.isWarningAcknowledged(this@SmsNotificationListener, title)
                ) {
                    Log.w("SmsNotificationListener", "Unhandled high-risk sender detected: $title. Will alert when user accesses SMS app.")
                    // Trigger Full-Screen Warning Overlay when user accesses SMS app notification
                    val intent = android.content.Intent(this@SmsNotificationListener, WarningOverlayActivity::class.java).apply {
                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra("EXTRA_SENDER", title)
                        putExtra("EXTRA_MESSAGE", text)
                        putExtra("EXTRA_CONFIDENCE", String.format(java.util.Locale.US, "%.1f%%", finalResult.probability * 100))
                    }
                    startActivity(intent)
                }
            } catch (e: Exception) {
                Log.e("SmsNotificationListener", "Critical error during notification scan", e)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Not used
    }
}
