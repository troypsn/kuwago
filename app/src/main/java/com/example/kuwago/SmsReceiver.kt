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
                        
                        val prefs = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
                        val onlineScanMode = prefs.getString(
                            SettingsFragment.KEY_ONLINE_SCAN_MODE,
                            SettingsFragment.MODE_AUTOMATIC
                        ) ?: SettingsFragment.MODE_AUTOMATIC

                        val finalResult = if (onlineScanMode == SettingsFragment.MODE_ON_APP || onlineScanMode == SettingsFragment.MODE_DISABLED) {
                            val localResult = LocalClassifier.classify(context, fullBody)
                            val hasUrl = LocalClassifier.hasUrl(fullBody)
                            val extractedUrl = LocalClassifier.extractUrl(fullBody)
                            val explanation = LocalClassifier.generateHumanReadableExplanation(fullBody, localResult.classification)
                            localResult.copy(
                                id = smsId,
                                sender = sender,
                                message = fullBody,
                                overallExplanation = explanation,
                                timestamp = firstTimestamp,
                                isScanning = false,
                                urlFound = hasUrl,
                                extractedUrl = extractedUrl,
                                localVerdict = localResult.classification.name.lowercase().replaceFirstChar { it.uppercase() },
                                ensembleFormula = "Local ML Model (75% RF + 25% XGB)"
                            )
                        } else {
                            SmishingDetector.analyze(context, fullBody, sender, isManual = false).copy(
                                id = smsId,
                                sender = sender,
                                timestamp = firstTimestamp
                            )
                        }
                        
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
