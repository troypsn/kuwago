package com.example.kuwago

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.kuwago.db.SmsLocalRepository

class HomeFragment : Fragment() {

    private lateinit var detectionsContainer: LinearLayout
    private lateinit var tvSmsScannedCount: TextView
    private lateinit var tvSuspiciousCount: TextView
    private lateinit var tvSmishingCount: TextView
    private lateinit var llSystemStatusButton: View
    private lateinit var tvSystemStatus: TextView
    private lateinit var viewStatusDot: View

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
        llSystemStatusButton = view.findViewById(R.id.ll_system_status_button)
        tvSystemStatus = view.findViewById(R.id.tv_system_status)
        viewStatusDot = view.findViewById(R.id.view_status_dot)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        context?.let { DetectionRepository.loadIfNeeded(it) }

        updateStatusUI()
        llSystemStatusButton.setOnClickListener {
            showChangeStatusDialog()
        }

        // "View All" link navigates to History tab
        view.findViewById<TextView>(R.id.tv_view_all)?.setOnClickListener {
            (activity as? MainActivity)?.navigateToHistory()
        }

        // Observe Home Page statistics directly from the encrypted local Room database
        SmsLocalRepository.getHomeStatsLiveData(requireContext()).observe(viewLifecycleOwner) { stats ->
            if (stats != null) {
                tvSmsScannedCount.text = stats.totalScanned.toString()
                tvSuspiciousCount.text = stats.totalSuspicious.toString()
                tvSmishingCount.text = stats.totalSmishing.toString()
            }
        }

        // Observe recent detections list for UI rendering
        DetectionRepository.detections.observe(viewLifecycleOwner) { results ->
            updateDetectionsList(results)
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatusUI()
    }

    private fun updateStatusUI() {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val status = prefs.getString(SettingsFragment.KEY_SYSTEM_STATUS, SettingsFragment.STATUS_ON)

        when (status) {
            SettingsFragment.STATUS_OFF -> {
                tvSystemStatus.text = "Status: Off"
                viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FF4D55"))
            }
            SettingsFragment.STATUS_DATA_SAVER -> {
                tvSystemStatus.text = "Status: Local Layer"
                viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F07048"))
            }
            else -> {
                tvSystemStatus.text = "Status: On"
                viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#26CE6B"))
            }
        }
    }

    private fun showChangeStatusDialog() {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)

        val dialog = Dialog(ctx)
        dialog.setContentView(R.layout.dialog_change_system_status)
        dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val displayMetrics = resources.displayMetrics
            val horizontalMarginPx = (24 * displayMetrics.density).toInt()
            val maxDialogWidthPx = (360 * displayMetrics.density).toInt()
            val targetWidth = (displayMetrics.widthPixels - (horizontalMarginPx * 2)).coerceAtMost(maxDialogWidthPx)
            window.setLayout(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val btnClose = dialog.findViewById<ImageView>(R.id.btn_close_dialog)
        val btnCancel = dialog.findViewById<Button>(R.id.btn_cancel_status)
        val optOn = dialog.findViewById<View>(R.id.option_status_on)
        val optOff = dialog.findViewById<View>(R.id.option_status_off)
        val optLocalLayer = dialog.findViewById<View>(R.id.option_status_local_layer)

        btnClose.setOnClickListener { dialog.dismiss() }
        btnCancel.setOnClickListener { dialog.dismiss() }

        optOn.setOnClickListener {
            prefs.edit()
                .putString(SettingsFragment.KEY_SYSTEM_STATUS, SettingsFragment.STATUS_ON)
                .putBoolean(SettingsFragment.KEY_DATA_SAVER, false)
                .apply()
            updateStatusUI()
            dialog.dismiss()
        }

        optOff.setOnClickListener {
            prefs.edit()
                .putString(SettingsFragment.KEY_SYSTEM_STATUS, SettingsFragment.STATUS_OFF)
                .apply()
            try {
                val stopVpnIntent = Intent(ctx, KuwagoVpnService::class.java).apply {
                    action = KuwagoVpnService.ACTION_STOP
                }
                ctx.startService(stopVpnIntent)
            } catch (e: Exception) {
                // Ignore if service cannot be started
            }
            updateStatusUI()
            dialog.dismiss()
        }

        optLocalLayer.setOnClickListener {
            prefs.edit()
                .putString(SettingsFragment.KEY_SYSTEM_STATUS, SettingsFragment.STATUS_DATA_SAVER)
                .putBoolean(SettingsFragment.KEY_DATA_SAVER, true)
                .apply()
            updateStatusUI()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun updateDetectionsList(results: List<DetectionResult>) {
        detectionsContainer.removeAllViews()

        // Show only the 3 most recent detections under "Recent Detections" to prevent home screen clutter
        val recentResults = results.take(3)

        for (result in recentResults) {
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

                val classificationName = result.getClassificationLabel()
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
