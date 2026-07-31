package com.example.kuwago

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class WarningOverlayActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_warning_overlay)

        val sender = intent.getStringExtra("EXTRA_SENDER") ?: "Unknown Sender"
        val message = intent.getStringExtra("EXTRA_MESSAGE") ?: "Suspicious message content detected."
        val confidence = intent.getStringExtra("EXTRA_CONFIDENCE") ?: "High Threat"

        val tvSender = findViewById<TextView>(R.id.warning_sender)
        val tvConfidence = findViewById<TextView>(R.id.warning_confidence)
        val tvMessage = findViewById<TextView>(R.id.warning_message)

        val btnAddBlacklist = findViewById<Button>(R.id.btn_add_blacklist_now)
        val btnProceedAnyway = findViewById<TextView>(R.id.btn_proceed_anyway)

        tvSender.text = "Sender: $sender"
        tvConfidence.text = confidence
        tvMessage.text = "\"$message\""

        btnAddBlacklist.setOnClickListener {
            BlacklistRepository.addOrUpdateEntry(
                context = this,
                sender = sender,
                riskLevel = RiskLevel.HIGH,
                method = BlacklistMethod.MANUAL
            )
            Toast.makeText(this, "$sender has been blacklisted!", Toast.LENGTH_SHORT).show()
            finish()
        }

        btnProceedAnyway.setOnClickListener {
            BlacklistRepository.markWarningAcknowledged(this, sender)
            finish()
        }
    }
}
