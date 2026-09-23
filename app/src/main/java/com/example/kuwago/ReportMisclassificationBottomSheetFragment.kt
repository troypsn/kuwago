package com.example.kuwago

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.kuwago.network.MisclassificationReportRequest
import com.example.kuwago.network.RetrofitClient
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReportMisclassificationBottomSheetFragment : BottomSheetDialogFragment() {

    private var detectionResult: DetectionResult? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Selected report type: "false_positive" or "false_negative" or null
    private var selectedReportType: String? = null

    companion object {
        private const val ARG_RESULT = "arg_detection_result"

        fun newInstance(result: DetectionResult): ReportMisclassificationBottomSheetFragment {
            val fragment = ReportMisclassificationBottomSheetFragment()
            val args = Bundle()
            args.putSerializable(ARG_RESULT, result)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        detectionResult = arguments?.getSerializable(ARG_RESULT) as? DetectionResult
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_report_misclassification, container, false)
    }

    override fun onStart() {
        super.onStart()
        dialog?.let { d ->
            val bottomSheet = d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let { sheet ->
                val behavior = BottomSheetBehavior.from(sheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                sheet.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
                sheet.background = ColorDrawable(Color.TRANSPARENT)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val result = detectionResult ?: return

        setupFormScreen(view, result)
        setupSuccessScreen(view, result)
    }

    private fun setupFormScreen(view: View, result: DetectionResult) {
        val btnBack = view.findViewById<ImageView>(R.id.btn_back_report)
        btnBack.setOnClickListener { dismiss() }

        // Populate message card
        populateMessageCard(
            view = view,
            senderViewId = R.id.tv_report_sender,
            badgeViewId = R.id.tv_report_badge,
            messageViewId = R.id.tv_report_message_preview,
            timestampViewId = R.id.tv_report_timestamp,
            chipMlId = R.id.report_chip_ml,
            chipDlId = R.id.report_chip_dl,
            chipUrlId = R.id.report_chip_url,
            result = result
        )

        // Issue type selection
        val optionFalsePositive = view.findViewById<LinearLayout>(R.id.option_false_positive)
        val optionFalseNegative = view.findViewById<LinearLayout>(R.id.option_false_negative)
        val tvError = view.findViewById<TextView>(R.id.tv_report_error)

        // Set initial unselected appearance
        setOptionUnselected(optionFalsePositive)
        setOptionUnselected(optionFalseNegative)

        // Pre-select based on original verdict
        val originalVerdict = result.getClassificationLabel()
        if (originalVerdict == "Harmful" || originalVerdict == "Suspicious") {
            // App said harmful/suspicious → user thinks it's a false positive (was actually safe)
            selectedReportType = "false_positive"
            setOptionSelected(optionFalsePositive)
        } else {
            // App said safe → user thinks it's a false negative (was actually harmful)
            selectedReportType = "false_negative"
            setOptionSelected(optionFalseNegative)
        }

        optionFalsePositive.setOnClickListener {
            selectedReportType = "false_positive"
            setOptionSelected(optionFalsePositive)
            setOptionUnselected(optionFalseNegative)
            tvError.visibility = View.GONE
        }

        optionFalseNegative.setOnClickListener {
            selectedReportType = "false_negative"
            setOptionSelected(optionFalseNegative)
            setOptionUnselected(optionFalsePositive)
            tvError.visibility = View.GONE
        }

        val etComment = view.findViewById<EditText>(R.id.et_user_comment)
        val btnSubmit = view.findViewById<Button>(R.id.btn_submit_report)

        btnSubmit.setOnClickListener {
            val reportType = selectedReportType
            if (reportType == null) {
                tvError.visibility = View.VISIBLE
                return@setOnClickListener
            }
            tvError.visibility = View.GONE
            submitReport(view, result, reportType, etComment.text.toString().trim())
        }
    }

    private fun setupSuccessScreen(view: View, result: DetectionResult) {
        val btnBackSuccess = view.findViewById<ImageView>(R.id.btn_back_success)
        btnBackSuccess.setOnClickListener { dismiss() }

        val btnDone = view.findViewById<Button>(R.id.btn_done)
        btnDone.setOnClickListener { dismiss() }
    }

    private fun populateMessageCard(
        view: View,
        senderViewId: Int,
        badgeViewId: Int,
        messageViewId: Int,
        timestampViewId: Int,
        chipMlId: Int,
        chipDlId: Int,
        chipUrlId: Int,
        result: DetectionResult
    ) {
        val tvSender = view.findViewById<TextView>(senderViewId)
        val tvBadge = view.findViewById<TextView>(badgeViewId)
        val tvMessage = view.findViewById<TextView>(messageViewId)
        val tvTimestamp = view.findViewById<TextView>(timestampViewId)
        val chipMl = view.findViewById<LinearLayout>(chipMlId)
        val chipDl = view.findViewById<LinearLayout>(chipDlId)
        val chipUrl = view.findViewById<LinearLayout>(chipUrlId)

        tvSender.text = result.sender
        tvMessage.text = result.message

        // Badge — show percentage
        val score = result.calculateEnsembleScore()
        val pct = (score * 100).toInt()
        tvBadge.text = "$pct%"
        val (bgColor, textColor) = when (result.getEffectiveClassification()) {
            Classification.SMISHING -> Pair(R.color.detection_red_bg, R.color.percentage_red)
            Classification.SUSPICIOUS -> Pair(R.color.detection_orange_bg, R.color.percentage_orange)
            Classification.SAFE -> Pair(R.color.detection_green_bg, R.color.detection_green_stroke)
        }
        tvBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), bgColor))
        tvBadge.setTextColor(ContextCompat.getColor(requireContext(), textColor))

        // Timestamp — sanity-check for the 2070 bug
        val safeTimestamp = sanitizeTimestamp(result.timestamp)
        val sdf = SimpleDateFormat("M/d/yy", Locale.US)
        tvTimestamp.text = sdf.format(Date(safeTimestamp))

        // Chip visibility
        val dlScanned = result.cnnProb != null || result.cnnScore != null
        val urlScanned = result.urlFound && result.urlScore != null
        chipMl.alpha = 1.0f
        chipDl.alpha = if (dlScanned) 1.0f else 0.35f
        chipUrl.alpha = if (urlScanned) 1.0f else 0.35f
    }

    private fun submitReport(view: View, result: DetectionResult, reportType: String, userComment: String) {
        val ctx = context ?: return
        val btnSubmit = view.findViewById<Button>(R.id.btn_submit_report)
        btnSubmit.isEnabled = false
        btnSubmit.text = "Submitting…"

        // Determine user verdict (opposite of original for misclassification reports)
        val userVerdict = when (reportType) {
            "false_positive" -> "Safe"    // App said harmful, user says safe
            "false_negative" -> "Harmful" // App said safe, user says harmful
            else -> "Unknown"
        }

        val originalVerdict = result.getClassificationLabel()
        val originalScore = result.calculateEnsembleScore()

        // Get device ID (anonymized)
        val deviceId = try {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
                ?: "anon-device-uuid"
        } catch (e: Exception) {
            "anon-device-uuid"
        }

        // Get app version
        val appVersion = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }

        val request = MisclassificationReportRequest(
            message = result.message,
            sender = result.sender,
            hasUrl = result.urlFound,
            extractedUrl = result.extractedUrl,
            originalVerdict = originalVerdict,
            originalScore = originalScore,
            userVerdict = userVerdict,
            reportType = reportType,
            userComment = userComment.ifEmpty {
                if (reportType == "false_negative") {
                    "This message is PHISHING but was flagged as low-risk."
                } else {
                    "This message is legitimate but was flagged as harmful."
                }
            },
            appVersion = appVersion,
            deviceId = deviceId
        )

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    RetrofitClient.instance.reportMisclassification(request)
                }
                // Success: show thank you screen
                showSuccessScreen(view, result, request.userComment)
            } catch (e: Exception) {
                // Even on network failure, show success (best-effort telemetry)
                // so we don't frustrate users with error states for optional feedback
                showSuccessScreen(view, result, request.userComment)
            } finally {
                btnSubmit.isEnabled = true
                btnSubmit.text = "Submit Report"
            }
        }
    }

    private fun showSuccessScreen(view: View, result: DetectionResult, issueText: String) {
        val screenForm = view.findViewById<LinearLayout>(R.id.screen_report_form)
        val screenSuccess = view.findViewById<LinearLayout>(R.id.screen_report_success)

        // Populate success screen message card
        populateMessageCard(
            view = view,
            senderViewId = R.id.tv_success_sender,
            badgeViewId = R.id.tv_success_badge,
            messageViewId = R.id.tv_success_message_preview,
            timestampViewId = R.id.tv_success_timestamp,
            chipMlId = R.id.success_chip_ml,
            chipDlId = R.id.success_chip_dl,
            chipUrlId = R.id.success_chip_url,
            result = result
        )

        // Populate issue text
        val tvIssue = view.findViewById<TextView>(R.id.tv_success_issue)
        tvIssue.text = issueText

        // Animate transition
        screenForm.visibility = View.GONE
        screenSuccess.visibility = View.VISIBLE
    }

    private fun setOptionSelected(layout: LinearLayout) {
        layout.setBackgroundResource(R.drawable.bg_option_selected)
        for (i in 0 until layout.childCount) {
            val child = layout.getChildAt(i)
            if (child is TextView) {
                if (i == 0) child.setTextColor(Color.parseColor("#26CE6B"))
                else child.setTextColor(Color.parseColor("#4CAF80"))
            }
        }
    }

    private fun setOptionUnselected(layout: LinearLayout) {
        layout.setBackgroundResource(R.drawable.bg_layer_card)
        for (i in 0 until layout.childCount) {
            val child = layout.getChildAt(i)
            if (child is TextView) {
                if (i == 0) child.setTextColor(Color.parseColor("#CCCCCC"))
                else child.setTextColor(Color.parseColor("#888888"))
            }
        }
    }

    /**
     * Sanity-checks a timestamp to guard against the epoch-seconds vs epoch-ms mismatch
     * bug that causes dates to appear as year 2070 or similar far-future/past dates.
     * A valid "current era" millisecond timestamp should be > Jan 1, 2000 and < Jan 1, 2100.
     */
    private fun sanitizeTimestamp(timestamp: Long): Long {
        val minValidMs = 946684800000L  // Jan 1, 2000 in ms
        val maxValidMs = 4102444800000L // Jan 1, 2100 in ms
        return when {
            timestamp in minValidMs..maxValidMs -> timestamp
            // Looks like it might be in seconds (e.g. ~1.7×10^9 for current era)
            timestamp * 1000L in minValidMs..maxValidMs -> timestamp * 1000L
            // Fallback to current time if timestamp is completely invalid
            else -> System.currentTimeMillis()
        }
    }
}
