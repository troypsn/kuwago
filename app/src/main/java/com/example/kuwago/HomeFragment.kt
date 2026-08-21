package com.example.kuwago

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

class HomeFragment : Fragment() {

    private lateinit var detectionsContainer: LinearLayout
    private lateinit var tvSmsScannedCount: TextView
    private lateinit var tvSuspiciousCount: TextView
    private lateinit var tvSmishingCount: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_home, container, false)
        detectionsContainer = view.findViewById(R.id.detections_container)
        tvSmsScannedCount = view.findViewById(R.id.tv_sms_scanned_count)
        tvSuspiciousCount = view.findViewById(R.id.tv_suspicious_count)
        tvSmishingCount = view.findViewById(R.id.tv_smishing_count)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        DetectionRepository.detections.observe(viewLifecycleOwner) { results ->
            updateDetectionsList(results)
            updateDetectionsMetrics(results)
        }
    }

    private fun updateDetectionsMetrics(results: List<DetectionResult>) {
        val total = results.size
        val suspicious = results.count { it.classification == Classification.SUSPICIOUS }
        val smishing = results.count { it.classification == Classification.SMISHING }

        tvSmsScannedCount.text = total.toString()
        tvSuspiciousCount.text = suspicious.toString()
        tvSmishingCount.text = smishing.toString()
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
        val modal = AnalysisDetailsBottomSheetFragment.newInstance(result)
        modal.onBlacklistUpdatedListener = {
            // Refresh detections if needed
        }
        modal.show(parentFragmentManager, "AnalysisDetailsBottomSheetFragment")
    }
}
