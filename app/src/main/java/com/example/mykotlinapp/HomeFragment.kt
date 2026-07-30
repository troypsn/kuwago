package com.example.mykotlinapp

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import android.widget.Toast
import java.util.Locale

class HomeFragment : Fragment() {

    private lateinit var detectionsContainer: LinearLayout
    private lateinit var debugStatus: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_home, container, false)
        detectionsContainer = view.findViewById(R.id.detections_container)
        debugStatus = view.findViewById(R.id.debug_status)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        DetectionRepository.detections.observe(viewLifecycleOwner) { results ->
            updateDetectionsList(results)
        }

        // Periodically check if Notification Access is on
        view.postDelayed(object : Runnable {
            override fun run() {
                if (isAdded) {
                    checkServiceStatus()
                    view.postDelayed(this, 3000)
                }
            }
        }, 1000)
    }

    private fun checkServiceStatus() {
        val mainActivity = activity as? MainActivity
        val isSmsGranted = ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.RECEIVE_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val isNotifEnabled = mainActivity?.isNotificationServiceEnabled() ?: false
        
        val status = when {
            isSmsGranted -> "Status: Full Protection (SMS Permission Active)"
            isNotifEnabled -> "Status: Enhanced Protection (Notification Access Active)"
            else -> "Status: Protection DISABLED (Grant SMS or Notif Access)"
        }
        debugStatus.text = status
    }

    private fun updateDetectionsList(results: List<DetectionResult>) {
        detectionsContainer.removeAllViews()
        
        for (result in results) {
            val itemView = LayoutInflater.from(context).inflate(R.layout.item_detection, detectionsContainer, false)
            
            val senderText = itemView.findViewById<TextView>(R.id.detection_sender)
            val messageText = itemView.findViewById<TextView>(R.id.detection_message)
            val statusBadge = itemView.findViewById<TextView>(R.id.detection_status)
            val progressBar = itemView.findViewById<ProgressBar>(R.id.detection_progress)
            
            senderText.text = result.sender
            messageText.text = result.message
            
            if (result.isScanning) {
                statusBadge.visibility = View.GONE
                progressBar.visibility = View.VISIBLE
                itemView.isClickable = false
            } else {
                statusBadge.visibility = View.VISIBLE
                progressBar.visibility = View.GONE
                itemView.isClickable = true
                
                val classificationName = result.classification.name.lowercase().replaceFirstChar { it.uppercase() }
                statusBadge.text = classificationName
                
                val (bgColor, textColor) = when (result.classification) {
                    Classification.SAFE -> Pair(R.color.detection_green_bg, R.color.detection_green_stroke)
                    Classification.SUSPICIOUS -> Pair(R.color.detection_orange_bg, R.color.percentage_orange)
                    Classification.SMISHING -> Pair(R.color.detection_red_bg, R.color.percentage_red)
                }
                
                statusBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), bgColor))
                statusBadge.setTextColor(ContextCompat.getColor(requireContext(), textColor))

                itemView.setOnClickListener {
                    showDetectionDetails(result)
                }
            }
            
            detectionsContainer.addView(itemView)
        }
    }

    private fun showDetectionDetails(result: DetectionResult) {
        val ctx = requireContext()
        val confidencePercent = String.format(Locale.getDefault(), "%.1f%%", result.probability * 100)
        val classification = result.classification.name.lowercase().replaceFirstChar { it.uppercase() }
        val explanation = LocalClassifier.formatDetailsExplanation(result)
        val isBlacklisted = BlacklistRepository.isBlacklisted(ctx, result.sender)

        val builder = AlertDialog.Builder(ctx)
            .setTitle("Detection Details")
            .setMessage(
                "Sender: ${result.sender}\n\n" +
                "Classification: $classification\n" +
                "Confidence: $confidencePercent\n\n" +
                "$explanation\n\n" +
                "Message:\n\"${result.message}\""
            )
            .setPositiveButton("OK", null)

        if (isBlacklisted) {
            builder.setNeutralButton("Remove from Blacklist") { _, _ ->
                BlacklistRepository.removeEntry(ctx, result.sender)
                Toast.makeText(ctx, "Removed ${result.sender} from Blacklist", Toast.LENGTH_SHORT).show()
            }
        } else {
            builder.setNeutralButton("Add to Blacklist") { _, _ ->
                val calculatedRisk = when (result.classification) {
                    Classification.SMISHING -> RiskLevel.HIGH
                    Classification.SUSPICIOUS -> RiskLevel.MEDIUM
                    Classification.SAFE -> RiskLevel.LOW
                }
                BlacklistRepository.addOrUpdateEntry(ctx, result.sender, calculatedRisk, BlacklistMethod.MANUAL)
                Toast.makeText(ctx, "Added ${result.sender} to Blacklist (${calculatedRisk.name.lowercase()} risk)", Toast.LENGTH_SHORT).show()
            }
        }

        builder.setNegativeButton("Test Warning Overlay") { _, _ ->
            val intent = android.content.Intent(ctx, WarningOverlayActivity::class.java).apply {
                putExtra("EXTRA_SENDER", result.sender)
                putExtra("EXTRA_MESSAGE", result.message)
                putExtra("EXTRA_CONFIDENCE", confidencePercent)
            }
            startActivity(intent)
        }

        builder.show()
    }
}
