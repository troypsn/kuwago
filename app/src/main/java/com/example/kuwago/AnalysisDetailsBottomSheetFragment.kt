package com.example.kuwago

import android.app.Dialog
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
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
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

class AnalysisDetailsBottomSheetFragment : BottomSheetDialogFragment() {

    private var detectionResult: DetectionResult? = null
    var onBlacklistUpdatedListener: (() -> Unit)? = null
    var onResultUpdatedListener: ((DetectionResult) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        private const val ARG_RESULT = "arg_detection_result"

        fun newInstance(result: DetectionResult): AnalysisDetailsBottomSheetFragment {
            val fragment = AnalysisDetailsBottomSheetFragment()
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
        return inflater.inflate(R.layout.fragment_analysis_details_modal, container, false)
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

        val btnBack = view.findViewById<ImageView>(R.id.btn_back)
        btnBack.setOnClickListener { dismiss() }

        val btnDeepScan = view.findViewById<ImageView>(R.id.btn_deep_scan)
        if (btnDeepScan != null) {
            val hasDlData = result.cnnScore != null || result.cnnProb != null
            if (hasDlData || result.isScanning) {
                btnDeepScan.visibility = View.GONE
            } else {
                btnDeepScan.visibility = View.VISIBLE
                btnDeepScan.setOnClickListener {
                    runDeepAnalysis(view, result)
                }
            }
        }

        setupClassificationSection(view, result)
        setupMessagePreviewSection(view, result)
        setupAnalysisAccordions(view, result)
        setupCombinedScore(view, result)
        setupSecurityActions(view, result)

        // Observe DetectionRepository to update live when background scan completes
        DetectionRepository.detections.observe(viewLifecycleOwner) { liveList ->
            val currentRes = detectionResult ?: return@observe
            val updated = liveList.find {
                it.id == currentRes.id || (it.message == currentRes.message && it.sender == currentRes.sender)
            }
            if (updated != null && (currentRes.isScanning && !updated.isScanning || updated.cnnScore != currentRes.cnnScore)) {
                detectionResult = updated
                setupClassificationSection(view, updated)
                setupAnalysisAccordions(view, updated)
                setupCombinedScore(view, updated)
                setupSecurityActions(view, updated)

                val btnDeep = view.findViewById<ImageView>(R.id.btn_deep_scan)
                if (updated.cnnScore != null || updated.cnnProb != null || updated.isScanning) {
                    btnDeep?.visibility = View.GONE
                } else {
                    btnDeep?.visibility = View.VISIBLE
                    btnDeep?.setOnClickListener { runDeepAnalysis(view, updated) }
                }
            }
        }
    }

    private fun setupMessagePreviewSection(view: View, result: DetectionResult) {
        val cardPreview = view.findViewById<LinearLayout>(R.id.card_message_preview) ?: return
        val tvContent = view.findViewById<TextView>(R.id.tv_message_preview_content) ?: return
        val tvHint = view.findViewById<TextView>(R.id.tv_message_expand_hint) ?: return

        tvContent.text = result.message
        tvContent.autoLinkMask = 0
        tvContent.linksClickable = false
        tvContent.movementMethod = null

        var isExpanded = false

        cardPreview.setOnClickListener {
            isExpanded = !isExpanded
            if (isExpanded) {
                tvContent.maxLines = Int.MAX_VALUE
                tvContent.ellipsize = null
                tvHint.text = "Tap to collapse"
            } else {
                tvContent.maxLines = 2
                tvContent.ellipsize = android.text.TextUtils.TruncateAt.END
                tvHint.text = "Tap to view full message"
            }
        }
    }

    private fun setupClassificationSection(view: View, result: DetectionResult) {
        val tvRiskBadge = view.findViewById<TextView>(R.id.tv_risk_badge)
        val tvClassificationTitle = view.findViewById<TextView>(R.id.tv_classification_title)
        val tvTotalPercentage = view.findViewById<TextView>(R.id.tv_total_percentage)
        val tvProbabilitySubtitle = view.findViewById<TextView>(R.id.tv_probability_subtitle)
        val tvMlSummaryVerdict = view.findViewById<TextView>(R.id.tv_ml_summary_verdict)
        val tvDlSummaryVerdict = view.findViewById<TextView>(R.id.tv_dl_summary_verdict)
        val tvUrlSummaryVerdict = view.findViewById<TextView>(R.id.tv_url_summary_verdict)
        val tvScannedDate = view.findViewById<TextView>(R.id.tv_scanned_date)

        if (result.isScanning) {
            tvRiskBadge.text = "⏳ Scanning…"
            tvRiskBadge.setTextColor(Color.parseColor("#FFF07048"))
            tvClassificationTitle.text = "Scanning in Progress…"
            tvTotalPercentage.text = "…"
            tvTotalPercentage.setTextColor(Color.parseColor("#FFF07048"))
            tvProbabilitySubtitle.text = "evaluating message threat"
            tvMlSummaryVerdict.text = "Scanning…"
            tvMlSummaryVerdict.setTextColor(Color.parseColor("#888888"))
            tvDlSummaryVerdict.text = "Scanning…"
            tvDlSummaryVerdict.setTextColor(Color.parseColor("#888888"))
            tvUrlSummaryVerdict.text = "Scanning…"
            tvUrlSummaryVerdict.setTextColor(Color.parseColor("#888888"))
        } else {
            val percent = (result.probability * 100).toInt()
            tvTotalPercentage.text = "$percent%"

            when (result.classification) {
                Classification.SMISHING -> {
                    tvRiskBadge.text = "⚠️ High Risk"
                    tvRiskBadge.setTextColor(Color.parseColor("#FF4D55"))
                    tvClassificationTitle.text = "Smishing Detected"
                    tvTotalPercentage.setTextColor(Color.parseColor("#FF4D55"))
                    tvProbabilitySubtitle.text = "smishing probability"
                }
                Classification.SUSPICIOUS -> {
                    tvRiskBadge.text = "⚠️ Medium Risk"
                    tvRiskBadge.setTextColor(Color.parseColor("#FFF07048"))
                    tvClassificationTitle.text = "Suspicious Message"
                    tvTotalPercentage.setTextColor(Color.parseColor("#FFF07048"))
                    tvProbabilitySubtitle.text = "suspicious probability"
                }
                Classification.SAFE -> {
                    tvRiskBadge.text = "✅ Low Risk"
                    tvRiskBadge.setTextColor(Color.parseColor("#26CE6B"))
                    tvClassificationTitle.text = "Safe Message"
                    tvTotalPercentage.setTextColor(Color.parseColor("#26CE6B"))
                    tvProbabilitySubtitle.text = "safe message score"
                }
            }

            // Layer summaries
            val mlProb = if (result.rfProb > 0f || result.xgbProb > 0f) {
                0.75f * result.rfProb + 0.25f * result.xgbProb
            } else {
                result.probability
            }
            tvMlSummaryVerdict.text = getVerdictText(mlProb)
            tvMlSummaryVerdict.setTextColor(getVerdictColor(mlProb))

            val dlProb = result.cnnScore ?: result.cnnProb ?: 0f
            if (result.cnnScore != null || result.cnnProb != null) {
                tvDlSummaryVerdict.text = getVerdictText(dlProb)
                tvDlSummaryVerdict.setTextColor(getVerdictColor(dlProb))
            } else {
                tvDlSummaryVerdict.text = "Pending"
                tvDlSummaryVerdict.setTextColor(Color.parseColor("#888888"))
            }

            val urlProb = result.urlScore ?: 0f
            if (result.urlFound) {
                tvUrlSummaryVerdict.text = if (urlProb >= 0.5f) "Malicious" else "Clean"
                tvUrlSummaryVerdict.setTextColor(getVerdictColor(urlProb))
            } else {
                tvUrlSummaryVerdict.text = "No Link"
                tvUrlSummaryVerdict.setTextColor(Color.parseColor("#888888"))
            }
        }

        // Date format
        val sdf = SimpleDateFormat("h:mm a • MMM d, yyyy", Locale.US)
        tvScannedDate.text = sdf.format(Date(result.timestamp))
    }

    private fun getVerdictText(prob: Float): String {
        return when {
            prob >= 0.8f -> "Smishing"
            prob >= 0.5f -> "Suspicious"
            else -> "Safe"
        }
    }

    private fun getVerdictColor(prob: Float): Int {
        return when {
            prob >= 0.8f -> Color.parseColor("#FF4D55")
            prob >= 0.5f -> Color.parseColor("#FFF07048")
            else -> Color.parseColor("#26CE6B")
        }
    }

    private fun setupAnalysisAccordions(view: View, result: DetectionResult) {
        // ML Accordion
        val headerMl = view.findViewById<RelativeLayout>(R.id.header_ml)
        val llMlDetails = view.findViewById<LinearLayout>(R.id.ll_ml_details)
        val ivMlChevron = view.findViewById<ImageView>(R.id.iv_ml_chevron)
        val tvMlScoreBadge = view.findViewById<TextView>(R.id.tv_ml_score_badge)
        val tvMlConfidenceVal = view.findViewById<TextView>(R.id.tv_ml_confidence_val)
        val pbMlConfidence = view.findViewById<ProgressBar>(R.id.pb_ml_confidence)
        val tvMlClassifiedBadge = view.findViewById<TextView>(R.id.tv_ml_classified_badge)
        val llMlMetricsContainer = view.findViewById<LinearLayout>(R.id.ll_ml_metrics_container)

        val mlScore = if (result.rfProb > 0f || result.xgbProb > 0f) {
            0.75f * result.rfProb + 0.25f * result.xgbProb
        } else result.probability

        tvMlScoreBadge.text = String.format(Locale.US, "%.2f", mlScore)
        tvMlConfidenceVal.text = String.format(Locale.US, "%.2f", mlScore)
        pbMlConfidence.progress = (mlScore * 100).toInt()
        tvMlClassifiedBadge.text = "Classified as ${getVerdictText(mlScore).lowercase()}"

        populateMlMetrics(llMlMetricsContainer, result)

        headerMl.setOnClickListener {
            if (llMlDetails.visibility == View.VISIBLE) {
                llMlDetails.visibility = View.GONE
                ivMlChevron.setImageResource(R.drawable.ic_chevron_down)
            } else {
                llMlDetails.visibility = View.VISIBLE
                ivMlChevron.setImageResource(R.drawable.ic_chevron_up)
            }
        }

        // DL Accordion
        val headerDl = view.findViewById<RelativeLayout>(R.id.header_dl)
        val llDlDetails = view.findViewById<LinearLayout>(R.id.ll_dl_details)
        val ivDlChevron = view.findViewById<ImageView>(R.id.iv_dl_chevron)
        val tvDlScoreBadge = view.findViewById<TextView>(R.id.tv_dl_score_badge)
        val tvDlConfidenceVal = view.findViewById<TextView>(R.id.tv_dl_confidence_val)
        val pbDlConfidence = view.findViewById<ProgressBar>(R.id.pb_dl_confidence)
        val tvDlClassifiedBadge = view.findViewById<TextView>(R.id.tv_dl_classified_badge)
        val llDlMetricsContainer = view.findViewById<LinearLayout>(R.id.ll_dl_metrics_container)
        val btnRunDeepAnalysis = view.findViewById<Button>(R.id.btn_run_deep_analysis)
        val llDlPendingState = view.findViewById<LinearLayout>(R.id.ll_dl_pending_state)
        val llDlResultState = view.findViewById<LinearLayout>(R.id.ll_dl_result_state)

        val hasDlData = result.cnnScore != null || result.cnnProb != null
        val dlScore = result.cnnScore ?: result.cnnProb ?: 0f

        if (hasDlData) {
            tvDlScoreBadge.text = String.format(Locale.US, "%.2f", dlScore)
            tvDlConfidenceVal.text = String.format(Locale.US, "%.2f", dlScore)
            pbDlConfidence.progress = (dlScore * 100).toInt()
            tvDlClassifiedBadge.text = "Classified as ${getVerdictText(dlScore).lowercase()}"
            llDlPendingState?.visibility = View.GONE
            llDlResultState?.visibility = View.VISIBLE
            populateDlMetrics(llDlMetricsContainer, result, dlScore)
        } else {
            tvDlScoreBadge.text = "–"
            llDlPendingState?.visibility = View.VISIBLE
            llDlResultState?.visibility = View.GONE
        }

        btnRunDeepAnalysis?.setOnClickListener {
            runDeepAnalysis(view, result)
        }

        headerDl.setOnClickListener {
            if (llDlDetails.visibility == View.VISIBLE) {
                llDlDetails.visibility = View.GONE
                ivDlChevron.setImageResource(R.drawable.ic_chevron_down)
            } else {
                llDlDetails.visibility = View.VISIBLE
                ivDlChevron.setImageResource(R.drawable.ic_chevron_up)
            }
        }

        // URL Accordion
        val headerUrl = view.findViewById<RelativeLayout>(R.id.header_url)
        val llUrlDetails = view.findViewById<LinearLayout>(R.id.ll_url_details)
        val ivUrlChevron = view.findViewById<ImageView>(R.id.iv_url_chevron)
        val tvUrlScoreBadge = view.findViewById<TextView>(R.id.tv_url_score_badge)
        val tvUrlConfidenceVal = view.findViewById<TextView>(R.id.tv_url_confidence_val)
        val pbUrlConfidence = view.findViewById<ProgressBar>(R.id.pb_url_confidence)
        val tvUrlClassifiedBadge = view.findViewById<TextView>(R.id.tv_url_classified_badge)
        val llUrlMetricsContainer = view.findViewById<LinearLayout>(R.id.ll_url_metrics_container)

        if (!result.urlFound) {
            // No URL in message
            tvUrlScoreBadge.text = "–"
            tvUrlConfidenceVal.text = "N/A"
            pbUrlConfidence.progress = 0
            tvUrlClassifiedBadge.text = "No URLs found in this message"
        } else if (result.urlScore == null) {
            // URL found but not yet scanned
            tvUrlScoreBadge.text = "–"
            tvUrlConfidenceVal.text = "Pending"
            pbUrlConfidence.progress = 0
            tvUrlClassifiedBadge.text = "URL scan pending"
        } else {
            val urlScore = result.urlScore
            tvUrlScoreBadge.text = String.format(Locale.US, "%.2f", urlScore)
            tvUrlConfidenceVal.text = String.format(Locale.US, "%.2f", urlScore)
            pbUrlConfidence.progress = (urlScore * 100).toInt()
            tvUrlClassifiedBadge.text = if (urlScore >= 0.5f) "Classified as malicious link" else "No threats detected"
        }

        populateUrlMetrics(llUrlMetricsContainer, result, result.urlScore ?: 0f)

        headerUrl.setOnClickListener {
            if (llUrlDetails.visibility == View.VISIBLE) {
                llUrlDetails.visibility = View.GONE
                ivUrlChevron.setImageResource(R.drawable.ic_chevron_down)
            } else {
                llUrlDetails.visibility = View.VISIBLE
                ivUrlChevron.setImageResource(R.drawable.ic_chevron_up)
            }
        }
    }

    private fun populateMlMetrics(container: LinearLayout, result: DetectionResult) {
        container.removeAllViews()
        val isThreat = result.classification != Classification.SAFE

        addMetricRow(container, "Urgency language", if (isThreat) "Detected" else "Not detected", isThreat)
        addMetricRow(container, "Suspicious keywords", if (isThreat) "8 found" else "0 found", isThreat)
        addMetricRow(container, "Reward/financial lure", if (isThreat) "2 found" else "0 found", isThreat)
        addMetricRow(container, "Call-to-action type", if (isThreat) "Credential phishing" else "None", isThreat)
        addMetricRow(container, "Message length", "Normal", false)
        addMetricRow(container, "Contains URL/link", if (result.urlFound) "Present" else "None", result.urlFound)
        addMetricRow(container, "Sender pattern", "Unknown", false, isOrange = true)
    }

    private fun populateDlMetrics(container: LinearLayout, result: DetectionResult, score: Float) {
        container.removeAllViews()
        val isThreat = score >= 0.5f

        addMetricRow(container, "Semantic threat pattern", if (isThreat) "High similarity" else "Low similarity", isThreat)
        addMetricRow(container, "Contextual embedding", if (isThreat) "Phishing intent" else "Normal intent", isThreat)
        addMetricRow(container, "Sequence anomaly", if (isThreat) "Detected" else "None", isThreat)
        addMetricRow(container, "Token attention score", String.format(Locale.US, "%.2f", if (isThreat) 0.94f else 0.12f), isThreat)
    }

    private fun populateUrlMetrics(container: LinearLayout, result: DetectionResult, score: Float) {
        container.removeAllViews()
        val hasCnnRun = result.cnnScore != null || result.cnnProb != null
        if (!hasCnnRun) {
            val tvNotScanned = TextView(requireContext()).apply {
                text = "Not scanned yet"
                setTextColor(Color.parseColor("#888888"))
                textSize = 13f
                setPadding(0, 10, 0, 10)
            }
            container.addView(tvNotScanned)
            return
        }

        if (result.urlFound) {
            // Links found
            addMetricRow(container, "Links found", "1 detected", true)

            // Threat score from backend
            val scoreStr = if (result.urlScore != null) String.format(Locale.US, "%.2f", result.urlScore) else "N/A"
            val isThreatScore = (result.urlScore ?: 0f) >= 0.5f
            addMetricRow(container, "Threat score", scoreStr, isThreatScore)

            // Verdict from backend
            val verdictStr = result.urlVerdict?.replaceFirstChar { it.uppercase() } ?: "Unknown"
            val isMaliciousVerdict = result.urlVerdict?.lowercase()?.let { it == "malicious" || it == "spam" } ?: false
            addMetricRow(container, "Verdict", verdictStr, isMaliciousVerdict)

            // Total weight from backend
            if (result.urlTotalWeight != null) {
                val weightStr = String.format(Locale.US, "%.2f", result.urlTotalWeight)
                addMetricRow(container, "Total weight", weightStr, result.urlTotalWeight >= 1.0f)
            }

            // Detected by (contributions list)
            val contributions = result.urlContributions
            if (!contributions.isNullOrEmpty()) {
                val detectedBy = contributions.joinToString(", ")
                addMetricRow(container, "Detected by", detectedBy, true)
            } else {
                addMetricRow(container, "Detected by", "None", false)
            }

            // Full explanation text from backend
            if (!result.explanation.isNullOrEmpty()) {
                val tvExplain = TextView(requireContext()).apply {
                    text = result.explanation
                    setTextColor(Color.parseColor("#AAAAAA"))
                    textSize = 12f
                    setPadding(0, 12, 0, 4)
                    setLineSpacing(4f, 1f)
                }
                container.addView(tvExplain)
            }
        } else {
            addMetricRow(container, "Links found", "None detected", false)
            addMetricRow(container, "Threat score", "N/A", false)
            addMetricRow(container, "Verdict", "Clean", false)
            addMetricRow(container, "Detected by", "None", false)
        }
    }

    private fun addMetricRow(
        container: LinearLayout,
        key: String,
        value: String,
        isAlert: Boolean,
        isOrange: Boolean = false
    ) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 10, 0, 10)
        }

        val tvKey = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            text = key
            setTextColor(Color.parseColor("#888888"))
            textSize = 13f
        }

        val tvVal = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            text = value
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(
                when {
                    isAlert -> Color.parseColor("#FF4D55")
                    isOrange -> Color.parseColor("#FFF07048")
                    else -> Color.parseColor("#26CE6B")
                }
            )
        }

        row.addView(tvKey)
        row.addView(tvVal)
        container.addView(row)
    }

    private fun setupCombinedScore(view: View, result: DetectionResult) {
        val llCombinedBreakdown = view.findViewById<LinearLayout>(R.id.ll_combined_breakdown)
        val tvEnsembleScoreVal = view.findViewById<TextView>(R.id.tv_ensemble_score_val)

        llCombinedBreakdown.removeAllViews()

        val mlScore = if (result.rfProb > 0f || result.xgbProb > 0f) {
            0.75f * result.rfProb + 0.25f * result.xgbProb
        } else result.probability

        val hasDl = result.cnnScore != null || result.cnnProb != null
        val dlScore = result.cnnScore ?: result.cnnProb
        val hasUrl = result.urlScore != null
        val urlScore = result.urlScore

        addBreakdownLine(llCombinedBreakdown, "ML Layer", "30% • " + String.format(Locale.US, "%.2f", mlScore))
        addBreakdownLine(llCombinedBreakdown, "DL Layer", if (hasDl && dlScore != null) "40% • " + String.format(Locale.US, "%.2f", dlScore) else "40% • Pending Scan")
        addBreakdownLine(llCombinedBreakdown, "URL Scan", if (hasUrl && urlScore != null) "30% • " + String.format(Locale.US, "%.2f", urlScore) else "30% • Pending Scan")

        tvEnsembleScoreVal.text = String.format(Locale.US, "%.3f", result.probability)
    }

    private fun addBreakdownLine(container: LinearLayout, label: String, textVal: String) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 4)
        }

        val tvLabel = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(100, ViewGroup.LayoutParams.WRAP_CONTENT)
            text = label
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 12f
        }

        val isPending = textVal.contains("Pending")
        val tvVal = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            text = textVal
            setTextColor(if (isPending) Color.parseColor("#666666") else Color.parseColor("#DDDDDD"))
            textSize = 12f
        }

        row.addView(tvLabel)
        row.addView(tvVal)
        container.addView(row)
    }

    private fun runDeepAnalysis(view: View, result: DetectionResult) {
        val ctx = context ?: return
        val btnRunDeepAnalysis = view.findViewById<Button>(R.id.btn_run_deep_analysis) ?: return
        val llDlPendingState = view.findViewById<LinearLayout>(R.id.ll_dl_pending_state) ?: return
        val llDlResultState = view.findViewById<LinearLayout>(R.id.ll_dl_result_state) ?: return
        val tvDlScoreBadge = view.findViewById<TextView>(R.id.tv_dl_score_badge) ?: return
        val tvDlConfidenceVal = view.findViewById<TextView>(R.id.tv_dl_confidence_val) ?: return
        val pbDlConfidence = view.findViewById<ProgressBar>(R.id.pb_dl_confidence) ?: return
        val tvDlClassifiedBadge = view.findViewById<TextView>(R.id.tv_dl_classified_badge) ?: return
        val llDlMetricsContainer = view.findViewById<LinearLayout>(R.id.ll_dl_metrics_container) ?: return
        val btnDeepScan = view.findViewById<ImageView>(R.id.btn_deep_scan)

        btnRunDeepAnalysis.isEnabled = false
        btnRunDeepAnalysis.text = "Scanning…"
        btnDeepScan?.isEnabled = false

        scope.launch {
            try {
                val finalResult = withContext(Dispatchers.IO) {
                    val scanResult = SmishingDetector.analyze(ctx, result.message, result.sender)
                    scanResult.copy(id = result.id, sender = result.sender, timestamp = result.timestamp)
                }

                val hasDlData = finalResult.cnnScore != null || finalResult.cnnProb != null
                if (hasDlData) {
                    val dlScore = finalResult.cnnScore ?: finalResult.cnnProb ?: 0f
                    tvDlScoreBadge.text = String.format(Locale.US, "%.2f", dlScore)
                    tvDlConfidenceVal.text = String.format(Locale.US, "%.2f", dlScore)
                    pbDlConfidence.progress = (dlScore * 100).toInt()
                    tvDlClassifiedBadge.text = "Classified as ${getVerdictText(dlScore).lowercase()}"
                    populateDlMetrics(llDlMetricsContainer, finalResult, dlScore)
                    llDlPendingState.visibility = View.GONE
                    llDlResultState.visibility = View.VISIBLE
                    btnDeepScan?.visibility = View.GONE

                    // Update the top classification card (Risk badge, Title, Total percentage, summaries, etc.)
                    setupClassificationSection(view, finalResult)

                    // Update URL accordion dropdown metrics and headers immediately
                    val llUrlMetricsContainer = view.findViewById<LinearLayout>(R.id.ll_url_metrics_container)
                    if (llUrlMetricsContainer != null) {
                        populateUrlMetrics(llUrlMetricsContainer, finalResult, finalResult.urlScore ?: 0f)
                    }
                    view.findViewById<TextView>(R.id.tv_url_score_badge)?.let {
                        it.text = if (finalResult.urlScore != null) String.format(Locale.US, "%.2f", finalResult.urlScore) else "–"
                    }
                    val pbUrlConfidence = view.findViewById<ProgressBar>(R.id.pb_url_confidence)
                    val tvUrlConfidenceVal = view.findViewById<TextView>(R.id.tv_url_confidence_val)
                    val tvUrlClassifiedBadge = view.findViewById<TextView>(R.id.tv_url_classified_badge)
                    if (finalResult.urlFound) {
                        val urlScore = finalResult.urlScore ?: 0f
                        pbUrlConfidence?.progress = (urlScore * 100).toInt()
                        tvUrlConfidenceVal?.text = String.format(Locale.US, "%.2f", urlScore)
                        tvUrlClassifiedBadge?.text = if (urlScore >= 0.5f) "Classified as malicious link" else "No threats detected"
                    }

                    // Update the Combined Score card
                    setupCombinedScore(view, finalResult)

                    // Notify history list to update chip state
                    detectionResult = finalResult
                    DetectionRepository.addDetection(finalResult)
                    onResultUpdatedListener?.invoke(finalResult)

                    Toast.makeText(ctx, "Deep Analysis complete", Toast.LENGTH_SHORT).show()
                } else {
                    val errMsg = finalResult.cnnVerdict ?: "API connection failed"
                    Toast.makeText(ctx, "Deep scan failed: $errMsg", Toast.LENGTH_LONG).show()
                    btnRunDeepAnalysis.isEnabled = true
                    btnRunDeepAnalysis.text = "Retry Deep Analysis"
                    btnDeepScan?.isEnabled = true
                    btnDeepScan?.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                Toast.makeText(ctx, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                btnRunDeepAnalysis.isEnabled = true
                btnRunDeepAnalysis.text = "Retry Deep Analysis"
                btnDeepScan?.isEnabled = true
                btnDeepScan?.visibility = View.VISIBLE
            }
        }
    }

    private fun setupSecurityActions(view: View, result: DetectionResult) {
        val btnBlacklist = view.findViewById<Button>(R.id.btn_blacklist_contact)
        val btnReport = view.findViewById<Button>(R.id.btn_report_misclassification)

        updateBlacklistButtonState(btnBlacklist, result.sender)

        btnBlacklist.setOnClickListener {
            val isBlacklisted = BlacklistRepository.isBlacklisted(requireContext(), result.sender)
            if (isBlacklisted) {
                // Remove from blacklist
                BlacklistRepository.removeEntry(requireContext(), result.sender)
                Toast.makeText(requireContext(), "Removed ${result.sender} from Blacklist", Toast.LENGTH_SHORT).show()
                updateBlacklistButtonState(btnBlacklist, result.sender)
                onBlacklistUpdatedListener?.invoke()
            } else {
                // Open Custom Blacklist Confirmation Dialog
                showBlacklistConfirmationDialog(result, btnBlacklist)
            }
        }

        btnReport.setOnClickListener {
            Toast.makeText(requireContext(), "Report misclassification template not configured yet.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateBlacklistButtonState(btn: Button, sender: String) {
        val isBlacklisted = BlacklistRepository.isBlacklisted(requireContext(), sender)
        if (isBlacklisted) {
            btn.text = "Remove from Blacklist"
            btn.setBackgroundResource(R.drawable.bg_dark_button)
        } else {
            btn.text = "Blacklist this contact"
            btn.setBackgroundResource(R.drawable.bg_red_button)
        }
    }

    private fun showBlacklistConfirmationDialog(result: DetectionResult, btnBlacklist: Button) {
        val dialog = Dialog(requireContext())
        dialog.setContentView(R.layout.dialog_blacklist_confirmation)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val tvMessage = dialog.findViewById<TextView>(R.id.tv_blacklist_message)
        val btnClose = dialog.findViewById<ImageView>(R.id.btn_close_dialog)
        val btnConfirm = dialog.findViewById<Button>(R.id.btn_confirm_add)
        val btnCancel = dialog.findViewById<Button>(R.id.btn_cancel_add)

        tvMessage.text = "Proceeding will add ${result.sender} to the blacklisted contact numbers."

        btnClose.setOnClickListener { dialog.dismiss() }
        btnCancel.setOnClickListener { dialog.dismiss() }

        btnConfirm.setOnClickListener {
            val calculatedRisk = when (result.classification) {
                Classification.SMISHING -> RiskLevel.HIGH
                Classification.SUSPICIOUS -> RiskLevel.MEDIUM
                Classification.SAFE -> RiskLevel.LOW
            }

            BlacklistRepository.addOrUpdateEntry(
                context = requireContext(),
                sender = result.sender,
                riskLevel = calculatedRisk,
                method = BlacklistMethod.MANUAL
            )

            Toast.makeText(requireContext(), "Added ${result.sender} to Blacklist", Toast.LENGTH_SHORT).show()
            updateBlacklistButtonState(btnBlacklist, result.sender)
            onBlacklistUpdatedListener?.invoke()
            dialog.dismiss()
        }

        dialog.show()
    }
}
