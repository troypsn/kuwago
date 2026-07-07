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
import java.util.Locale

class HomeFragment : Fragment() {

    private lateinit var detectionsContainer: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_home, container, false)
        detectionsContainer = view.findViewById(R.id.detections_container)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        DetectionRepository.detections.observe(viewLifecycleOwner) { results ->
            updateDetectionsList(results)
        }
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
        val confidencePercent = String.format(Locale.getDefault(), "%.1f%%", result.probability * 100)
        val classification = result.classification.name.lowercase().replaceFirstChar { it.uppercase() }

        AlertDialog.Builder(requireContext())
            .setTitle("Detection Details")
            .setMessage(
                "Sender: ${result.sender}\n\n" +
                "Classification: $classification\n" +
                "Confidence: $confidencePercent\n\n" +
                "Message:\n\"${result.message}\""
            )
            .setPositiveButton("OK", null)
            .show()
    }
}
