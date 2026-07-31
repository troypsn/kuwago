package com.example.kuwago

import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SmsNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        var instance: SmsNotificationListener? = null
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i("SmsNotifListener", "=== NOTIFICATION LISTENER CONNECTED & ACTIVE ===")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance == this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            processNotification(sbn)
        } catch (e: Exception) {
            Log.e("SmsNotifListener", "Crash in onNotificationPosted", e)
        }
    }

    private fun processNotification(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        val notification = sbn.notification
        val extras = notification.extras

        // Only process messaging-related notifications
        val isLikelyMessage = packageName.contains("message", ignoreCase = true) ||
                packageName.contains("sms", ignoreCase = true) ||
                packageName.contains("mms", ignoreCase = true) ||
                packageName.contains("telephony", ignoreCase = true) ||
                packageName.contains("chat", ignoreCase = true) ||
                notification.category == Notification.CATEGORY_MESSAGE

        if (!isLikelyMessage) return

        // Extract title (sender) and text safely
        var extractedSender = "Unknown"
        var extractedText = ""

        // 1. Try modern MessagingStyle first (used by Google Messages)
        try {
            val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
            if (style != null) {
                Log.d("SmsNotifListener", "MessagingStyle detected with ${style.messages.size} messages")
                val lastMessage = style.messages.lastOrNull()
                if (lastMessage != null) {
                    val personName = lastMessage.person?.name?.toString()
                    val personUri = lastMessage.person?.uri
                    val legacySender = lastMessage.sender?.toString()
                    Log.d("SmsNotifListener", "Extracted from MessagingStyle -> Name: $personName, URI: $personUri, LegacySender: $legacySender")
                    
                    // Prefer URI (often tel:number) if available, otherwise name/sender
                    val senderIdentifier = personUri ?: personName ?: legacySender
                    if (!senderIdentifier.isNullOrBlank()) {
                        extractedSender = senderIdentifier.toString()
                        // Strip "tel:" prefix if present
                        if (extractedSender.startsWith("tel:")) {
                            extractedSender = extractedSender.substring(4)
                        }
                    }
                    
                    val msgText = lastMessage.text?.toString()
                    if (!msgText.isNullOrBlank()) extractedText = msgText
                }
            } else {
                Log.d("SmsNotifListener", "MessagingStyle is null")
            }
        } catch (e: Exception) {
            Log.w("SmsNotifListener", "Could not parse MessagingStyle", e)
        }

        // 2. Fallback to classic EXTRA_TITLE
        if (extractedSender == "Unknown") {
            extractedSender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
                ?: "Unknown"
        }

        var text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        if (text.isEmpty()) {
            text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        }
        if (text.isEmpty()) {
            text = extras.getCharSequence("android.text")?.toString() ?: ""
        }

        if (extractedText.isEmpty()) {
            extractedText = text
        }

        Log.d("SmsNotifListener", "Final Extracted Sender: $extractedSender")
        Log.d("SmsNotifListener", "Final Extracted Text: ${extractedText.take(50)}")

        if (extractedText.isEmpty()) {
            Log.d("SmsNotifListener", "Text is empty, returning early")
            return
        }

        // Check if sender is blacklisted
        val isSenderBlacklisted = BlacklistRepository.isBlacklisted(this, extractedSender)
        val isTextBlacklisted = extractedText.length < 50 && BlacklistRepository.isBlacklisted(this, extractedText)
        val isBlacklisted = isSenderBlacklisted || isTextBlacklisted
        
        Log.d("SmsNotifListener", "Blacklist Check -> Sender Match: $isSenderBlacklisted, Text Match: $isTextBlacklisted, Result: $isBlacklisted")

        if (isBlacklisted) {
            Log.i("SmsNotifListener", "BLACKLISTED sender detected: $extractedSender - cancelling notification ${sbn.key}")
            cancelNotificationSafely(sbn.key)

            // Also kill any group summary and other notifications from same app
            killAllBlacklistedFromApp(packageName)

            // Re-check multiple times to catch re-posts
            for (delay in longArrayOf(200, 600, 1500, 3000)) {
                handler.postDelayed({ killAllBlacklistedFromApp(packageName) }, delay)
            }
            return
        }

        // Not blacklisted - scan it
        Log.i("SmsNotifListener", "Scanning message from: $extractedSender")
        scope.launch {
            try {
                val placeholder = DetectionResult(
                    sender = extractedSender,
                    message = extractedText,
                    isScanning = true
                )
                DetectionRepository.addDetection(placeholder)

                val finalResult = SmishingDetector.analyze(this@SmsNotificationListener, extractedText, extractedSender).copy(
                    id = placeholder.id,
                    sender = extractedSender
                )

                DetectionRepository.updateDetection(finalResult)

                if (finalResult.classification != Classification.SAFE &&
                    !BlacklistRepository.isBlacklisted(this@SmsNotificationListener, extractedSender) &&
                    !BlacklistRepository.isWarningAcknowledged(this@SmsNotificationListener, extractedSender)
                ) {
                    PendingWarningRepository.savePendingWarning(
                        this@SmsNotificationListener,
                        sender = extractedSender,
                        message = extractedText,
                        confidence = String.format(java.util.Locale.US, "%.1f%%", finalResult.probability * 100)
                    )
                }
            } catch (e: Exception) {
                Log.e("SmsNotifListener", "Error during scan", e)
            }
        }
    }

    private fun cancelNotificationSafely(key: String) {
        try {
            cancelNotification(key)
        } catch (e: Exception) {
            Log.e("SmsNotifListener", "Failed to cancel notification $key: ${e.message}")
        }
    }

    fun triggerAggressiveKill(pkg: String) {
        Log.i("SmsNotifListener", "Triggering aggressive kill for $pkg")
        killAllBlacklistedFromApp(pkg)
        for (delay in longArrayOf(200, 500, 1000, 2000, 4000)) {
            handler.postDelayed({ killAllBlacklistedFromApp(pkg) }, delay)
        }
    }

    fun killAllBlacklistedFromApp(pkg: String) {
        try {
            val active = getActiveNotifications() ?: return
            for (notif in active) {
                if (notif.packageName != pkg) continue

                val isGroupSummary = (notif.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
                
                var isSenderBlacklisted = false
                try {
                    val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notif.notification)
                    if (style != null) {
                        for (msg in style.messages) {
                            val personUri = msg.person?.uri?.removePrefix("tel:")
                            val personName = msg.person?.name?.toString()
                            val legacySender = msg.sender?.toString()
                            
                            val senderToCheck = personUri ?: personName ?: legacySender
                            
                            if (!senderToCheck.isNullOrBlank() && BlacklistRepository.isBlacklisted(this, senderToCheck)) {
                                isSenderBlacklisted = true
                                break
                            }
                        }
                    }
                } catch (e: Exception) {}

                val notifTitle = notif.notification.extras
                    .getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
                val notifText = notif.notification.extras
                    .getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

                val shouldKill = isGroupSummary || isSenderBlacklisted ||
                        BlacklistRepository.isBlacklisted(this, notifTitle) ||
                        (notifText.length < 50 && BlacklistRepository.isBlacklisted(this, notifText))

                if (shouldKill) {
                    cancelNotificationSafely(notif.key)
                    Log.i("SmsNotifListener", "Killed: key=${notif.key}, title=$notifTitle, group=$isGroupSummary")
                }
            }
        } catch (e: Exception) {
            Log.e("SmsNotifListener", "Error in killAllBlacklistedFromApp", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Not used
    }
}
