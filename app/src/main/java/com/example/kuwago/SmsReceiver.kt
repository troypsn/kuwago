package com.example.kuwago

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import kotlinx.coroutines.*

class SmsReceiver : BroadcastReceiver() {
    
    private val receiverJob = SupervisorJob()
    private val scope = CoroutineScope(receiverJob + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (messages.isNullOrEmpty()) return

            // Mark the broadcast as asynchronous
            val pendingResult = goAsync()
            
            scope.launch {
                try {
                    // 1. Group multi-part SMS by sender
                    // Usually, one intent contains all parts of one message, but we group to be safe.
                    val groupedMessages = messages.groupBy { it.displayOriginatingAddress ?: "Unknown" }
                    
                    for ((sender, parts) in groupedMessages) {
                        // 2. Concatenate all parts into one full message
                        val fullBody = parts.joinToString("") { it.displayMessageBody ?: "" }
                        
                        Log.d("SmsReceiver", "Consolidated SMS from $sender (${parts.size} parts): \"$fullBody\"")
                        
                        // 3. Create placeholder
                        val placeholder = DetectionResult(
                            sender = sender,
                            message = fullBody,
                            isScanning = true
                        )
                        DetectionRepository.addDetection(placeholder)

                        // 3.5. INSTANT KILL: Bypass Android's OEM NotificationListenerService delays
                        val isBlacklisted = BlacklistRepository.isBlacklisted(context, sender) || 
                                (fullBody.length < 50 && BlacklistRepository.isBlacklisted(context, fullBody))
                        
                        if (isBlacklisted) {
                            Log.i("SmsReceiver", "BLACKLISTED sender detected instantly in SmsReceiver: $sender")
                            // We don't have the notification key here, but we can trigger the NotificationListenerService
                            // to aggressively poll and kill it the exact millisecond Google Messages posts it!
                            SmsNotificationListener.instance?.triggerAggressiveKill("com.google.android.apps.messaging")
                            
                            // We still run the analyzer just to update the History tab properly,
                            // but we could also skip it if we wanted to save CPU. Let's just update the UI.
                            val finalResult = DetectionResult(
                                id = placeholder.id,
                                sender = sender,
                                message = fullBody,
                                classification = Classification.SUSPICIOUS,
                                probability = 1.0f,
                                isScanning = false
                            )
                            DetectionRepository.updateDetection(finalResult)
                            Log.d("SmsReceiver", "Final result updated for $sender (Skipped AI, was blacklisted)")
                            continue
                        }
                        
                        // 4. Analyze
                        val finalResult = SmishingDetector.analyze(context, fullBody, sender).copy(
                            id = placeholder.id,
                            sender = sender
                        )
                        
                        // 5. Update UI
                        DetectionRepository.updateDetection(finalResult)
                        Log.d("SmsReceiver", "Final result updated for $sender")

                        // 6. Save pending warning for in-app overlay
                        if (finalResult.classification != Classification.SAFE &&
                            !BlacklistRepository.isBlacklisted(context, sender) &&
                            !BlacklistRepository.isWarningAcknowledged(context, sender)
                        ) {
                            val confidencePct = String.format(
                                java.util.Locale.US, "%.1f%%", finalResult.probability * 100
                            )
                            PendingWarningRepository.savePendingWarning(
                                context,
                                sender = sender,
                                message = fullBody,
                                confidence = confidencePct
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SmsReceiver", "Error in background SMS processing", e)
                } finally {
                    // CRITICAL: Always finish the pending result
                    pendingResult.finish()
                    Log.d("SmsReceiver", "Broadcast processing completed.")
                }
            }
        }
    }
}
