package com.example.kuwago

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import kotlinx.coroutines.*
import java.util.UUID

class SmsReceiver : BroadcastReceiver() {
    
    private val receiverJob = SupervisorJob()
    private val scope = CoroutineScope(receiverJob + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (messages.isNullOrEmpty()) return

            val pendingResult = goAsync()
            
            scope.launch {
                try {
                    val groupedMessages = messages.groupBy { it.displayOriginatingAddress ?: "Unknown" }
                    
                    for ((sender, parts) in groupedMessages) {
                        val fullBody = parts.joinToString("") { it.displayMessageBody ?: "" }
                        val firstTimestamp = parts.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()

                        // Stable ID generation using metadata + provider timestamp
                        val rawSeed = "rcv_${sender.hashCode()}_${fullBody.hashCode()}_${firstTimestamp}"
                        val smsId = UUID.nameUUIDFromBytes(rawSeed.toByteArray()).toString()
                        
                        Log.d("SmsReceiver", "Processing SMS broadcast (id=$smsId, parts=${parts.size})")
                        
                        val placeholder = DetectionResult(
                            id = smsId,
                            sender = sender,
                            message = fullBody,
                            isScanning = true,
                            timestamp = firstTimestamp
                        )
                        DetectionRepository.addDetection(context, placeholder)

                        val isBlacklisted = BlacklistRepository.isBlacklisted(context, sender) || 
                                (fullBody.length < 50 && BlacklistRepository.isBlacklisted(context, fullBody))
                        
                        if (isBlacklisted) {
                            Log.i("SmsReceiver", "Blacklisted sender intercepted (id=$smsId)")
                            SmsNotificationListener.instance?.triggerAggressiveKill("com.google.android.apps.messaging")
                            
                            val finalResult = DetectionResult(
                                id = smsId,
                                sender = sender,
                                message = fullBody,
                                classification = Classification.SUSPICIOUS,
                                probability = 1.0f,
                                isScanning = false,
                                timestamp = firstTimestamp
                            )
                            DetectionRepository.updateDetection(context, finalResult)
                            continue
                        }
                        
                        val finalResult = SmishingDetector.analyze(context, fullBody, sender).copy(
                            id = smsId,
                            sender = sender,
                            timestamp = firstTimestamp
                        )
                        
                        DetectionRepository.updateDetection(context, finalResult)
                        Log.d("SmsReceiver", "Analysis completed for id=$smsId")

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
                    Log.e("SmsReceiver", "Error processing background SMS", e)
                } finally {
                    pendingResult.finish()
                    Log.d("SmsReceiver", "Broadcast processing completed.")
                }
            }
        }
    }
}
