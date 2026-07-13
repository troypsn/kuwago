package com.example.mykotlinapp

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
                        
                        // 4. Analyze
                        val finalResult = SmishingDetector.analyze(context, fullBody).copy(
                            id = placeholder.id,
                            sender = sender
                        )
                        
                        // 5. Update UI
                        DetectionRepository.updateDetection(finalResult)
                        Log.d("SmsReceiver", "Final result updated for $sender")
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
