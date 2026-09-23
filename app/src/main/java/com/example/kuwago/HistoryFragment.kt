package com.example.kuwago

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class HistoryFragment : Fragment() {

    private lateinit var switchSmsPermission: SwitchCompat
    private lateinit var historyRecyclerView: RecyclerView
    private lateinit var layoutEmptyState: LinearLayout
    private lateinit var tvPermissionDesc: TextView
    private lateinit var tvEmptyTitle: TextView
    private lateinit var tvEmptyMessage: TextView

    // Search + filter
    private lateinit var searchBox: EditText
    private lateinit var btnFilter: ImageView
    private lateinit var filterChipScroll: View
    private lateinit var chipAll: TextView
    private lateinit var chipHarmful: TextView
    private lateinit var chipSuspicious: TextView
    private lateinit var chipSafe: TextView
    private lateinit var chipNewest: TextView
    private lateinit var chipOldest: TextView
    private var filterChipsVisible = false
    private var activeFilterChip: TextView? = null
    private var activeSortChip: TextView? = null

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    // allSmsList holds the full unfiltered load; smsList is what the adapter sees
    private val allSmsList = mutableListOf<DetectionResult>()
    private val smsList = mutableListOf<DetectionResult>()
    private lateinit var smsAdapter: SmsHistoryAdapter

    private var isUserAction = true

    companion object {
        private const val REQUEST_CODE_SMS = 1001
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_history, container, false)

        switchSmsPermission = view.findViewById(R.id.switch_sms_permission)
        historyRecyclerView = view.findViewById(R.id.history_recycler_view)
        layoutEmptyState = view.findViewById(R.id.layout_empty_state)
        tvPermissionDesc = view.findViewById(R.id.tv_permission_desc)
        tvEmptyTitle = view.findViewById(R.id.tv_empty_title)
        tvEmptyMessage = view.findViewById(R.id.tv_empty_message)

        // Search + filter views
        searchBox = view.findViewById(R.id.history_search)
        btnFilter = view.findViewById(R.id.history_btn_filter)
        filterChipScroll = view.findViewById(R.id.history_filter_chip_scroll)
        chipAll = view.findViewById(R.id.history_chip_all)
        chipHarmful = view.findViewById(R.id.history_chip_harmful)
        chipSuspicious = view.findViewById(R.id.history_chip_suspicious)
        chipSafe = view.findViewById(R.id.history_chip_safe)
        chipNewest = view.findViewById(R.id.history_chip_newest)
        chipOldest = view.findViewById(R.id.history_chip_oldest)

        historyRecyclerView.layoutManager = LinearLayoutManager(context)
        smsAdapter = SmsHistoryAdapter(smsList, { result ->
            showDetailsDialog(result)
        })
        historyRecyclerView.adapter = smsAdapter

        setupListeners()
        setupSearchAndFilter()
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        context?.let { DetectionRepository.loadIfNeeded(it) }

        view.findViewById<View>(R.id.btn_refresh_history)?.setOnClickListener {
            loadAndClassifySms()
        }

        DetectionRepository.detections.observe(viewLifecycleOwner) { liveList ->
            if (liveList.isEmpty()) return@observe

            var hasNewItems = false
            for (liveItem in liveList.asReversed()) {
                val index = allSmsList.indexOfFirst {
                    it.id == liveItem.id || (it.message == liveItem.message && it.sender == liveItem.sender)
                }
                if (index != -1) {
                    val current = allSmsList[index]
                    if (liveItem.cnnProb != current.cnnProb ||
                        liveItem.isScanning != current.isScanning ||
                        liveItem.classification != current.classification ||
                        liveItem.probability != current.probability ||
                        liveItem.urlScore != current.urlScore
                    ) {
                        allSmsList[index] = liveItem.copy(id = current.id, timestamp = current.timestamp)
                        hasNewItems = true
                    }
                } else {
                    allSmsList.add(0, liveItem)
                    hasNewItems = true
                }
            }

            if (hasNewItems) {
                applyFilters()
                if (smsList.isNotEmpty()) {
                    historyRecyclerView.scrollToPosition(0)
                }
            }
            if (smsList.isNotEmpty()) {
                historyRecyclerView.visibility = View.VISIBLE
                layoutEmptyState.visibility = View.GONE
            }
        }
    }

    override fun onResume() {
        super.onResume()
        syncPermissionState()
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    private fun setupListeners() {
        switchSmsPermission.setOnCheckedChangeListener { _, isChecked ->
            if (!isUserAction) return@setOnCheckedChangeListener

            if (isChecked) {
                // Request Permission
                if (hasSmsPermission()) {
                    loadAndClassifySms()
                } else {
                    requestSmsPermission()
                }
            } else {
                // Confirm turning off SMS inbox integration
                AlertDialog.Builder(requireContext())
                    .setMessage("Are you sure you want to turn off \"SMS Inbox Integration\"?")
                    .setPositiveButton("Turn Off") { _, _ ->
                        tvPermissionDesc.text = "Grant permission to analyze device SMS history"
                        loadAndClassifySms()
                    }
                    .setNegativeButton("Cancel") { dialog, _ ->
                        isUserAction = false
                        switchSmsPermission.isChecked = true
                        isUserAction = true
                        dialog.dismiss()
                    }
                    .setOnCancelListener {
                        isUserAction = false
                        switchSmsPermission.isChecked = true
                        isUserAction = true
                    }
                    .show()
            }
        }
    }

    private fun hasSmsPermission(): Boolean {
        val context = context ?: return false
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestSmsPermission() {
        requestPermissions(arrayOf(Manifest.permission.READ_SMS), REQUEST_CODE_SMS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_SMS) {
            isUserAction = false
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                switchSmsPermission.isChecked = true
                tvPermissionDesc.text = "SMS Inbox scan active"
                loadAndClassifySms()
            } else {
                switchSmsPermission.isChecked = false
                // Check if permanently denied
                if (!shouldShowRequestPermissionRationale(Manifest.permission.READ_SMS)) {
                    showSettingsGuideDialog()
                }
            }
            isUserAction = true
        }
    }

    private fun showSettingsGuideDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Permission Restricted")
            .setMessage(
                "SMS Inbox integration is restricted by system permissions.\n\n" +
                "To enable it:\n" +
                "1. Click 'Go to Settings' below.\n" +
                "2. Choose 'Permissions'.\n" +
                "3. Enable 'SMS' access."
            )
            .setPositiveButton("Go to Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", requireContext().packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun syncPermissionState() {
        isUserAction = false
        if (hasSmsPermission()) {
            switchSmsPermission.isChecked = true
            tvPermissionDesc.text = "SMS Inbox scan active"
        } else {
            switchSmsPermission.isChecked = false
            tvPermissionDesc.text = "Grant permission to analyze device SMS history"
        }
        loadAndClassifySms()
        isUserAction = true
    }

    private fun clearSmsList() {
        allSmsList.clear()
        smsList.clear()
        smsAdapter.notifyDataSetChanged()
        historyRecyclerView.visibility = View.GONE
        layoutEmptyState.visibility = View.VISIBLE
    }

    private fun loadAndClassifySms() {
        val ctx = context ?: return
        historyRecyclerView.visibility = View.VISIBLE
        layoutEmptyState.visibility = View.GONE

        scope.launch {
            val results = withContext(Dispatchers.IO) {
                val list = mutableListOf<DetectionResult>()

                // 1. Fetch all scanned/intercepted detections directly from Room DB
                val db = com.example.kuwago.db.SmsLocalRepository.getDatabase(ctx)
                val dbSmsList = db.smsDao().getAllSmsList()
                val dbDetections = dbSmsList.map { sms ->
                    val analysis = db.analysisDao().getAnalysisResultBySmsId(sms.smsId)
                    val urls = db.analysisDao().getUrlAnalysesBySmsId(sms.smsId)
                    val decision = db.analysisDao().getFinalDecisionBySmsId(sms.smsId)

                    val classification = decision?.riskLevel?.let {
                        try { Classification.valueOf(it) } catch (_: Exception) { Classification.SAFE }
                    } ?: Classification.SAFE

                    val prob = decision?.finalScore ?: analysis?.mlConfidence ?: 0f
                    val hasUrl = urls.isNotEmpty() || LocalClassifier.hasUrl(sms.messageContent)
                    val firstUrlEntity = urls.firstOrNull()
                    val firstUrl = firstUrlEntity?.extractedUrl ?: LocalClassifier.extractUrl(sms.messageContent)
                    val hasDlRun = analysis?.dlConfidence != null
                    val isMalicious = firstUrlEntity?.isMalicious == 1
                    val urlScore = firstUrlEntity?.urlScore ?: if (hasUrl && hasDlRun) (if (isMalicious) 1.0f else 0.0f) else null
                    val urlVerdict = firstUrlEntity?.urlVerdict ?: if (hasUrl && hasDlRun) (if (isMalicious) "malicious" else "clean") else null

                    val rawResult = DetectionResult(
                        id = sms.smsId,
                        sender = sms.senderNumber,
                        message = sms.messageContent,
                        classification = classification,
                        probability = prob,
                        isScanning = sms.isProcessed == 0,
                        timestamp = sms.receivedTimestamp,
                        cnnScore = analysis?.dlConfidence,
                        cnnVerdict = analysis?.dlPrediction,
                        urlFound = hasUrl,
                        extractedUrl = firstUrl,
                        urlScore = urlScore,
                        urlVerdict = urlVerdict,
                        localVerdict = analysis?.mlPrediction,
                        rfProb = analysis?.mlConfidence ?: 0f,
                        xgbProb = analysis?.mlConfidence ?: 0f
                    )
                    rawResult.copy(
                        probability = rawResult.calculateEnsembleScore(),
                        classification = rawResult.getEffectiveClassification()
                    )
                }.filter { !it.id.startsWith("synced_") && it.sender != "Kuwago Database" }

                // Merge with in-memory detections
                val inMemoryDetections = DetectionRepository.detections.value.orEmpty()
                val combinedRepo = (inMemoryDetections + dbDetections).distinctBy { it.id }
                list.addAll(combinedRepo)

                // 2. Load SMS inbox entries (only if permission granted)
                if (hasSmsPermission()) {
                    val cursor: Cursor? = ctx.contentResolver.query(
                        Uri.parse("content://sms/inbox"),
                        arrayOf("_id", "address", "body", "date"),
                        null,
                        null,
                        "date DESC LIMIT 50"
                    )

                    cursor?.use { c ->
                        val idCol = c.getColumnIndex("_id")
                        val addrCol = c.getColumnIndex("address")
                        val bodyCol = c.getColumnIndex("body")
                        val dateCol = c.getColumnIndex("date")

                        val newlyClassified = mutableListOf<DetectionResult>()
                        while (c.moveToNext()) {
                            val smsId = if (idCol != -1) c.getString(idCol) else java.util.UUID.randomUUID().toString()
                            val sender = if (addrCol != -1) c.getString(addrCol) ?: "Unknown" else "Unknown"
                            val body = if (bodyCol != -1) c.getString(bodyCol) ?: "" else ""
                            val date = if (dateCol != -1) c.getLong(dateCol) else System.currentTimeMillis()

                            if (list.any { it.message == body && it.sender == sender }) continue

                            val classificationResult = LocalClassifier.classify(ctx, body)
                            val finalRes = classificationResult.copy(id = smsId, sender = sender, timestamp = date)
                            list.add(finalRes)
                            newlyClassified.add(finalRes)
                        }
                        if (newlyClassified.isNotEmpty()) {
                            DetectionRepository.addDetections(ctx, newlyClassified)
                        }
                    }
                }
                list.sortedByDescending { it.timestamp }
            }

            allSmsList.clear()
            allSmsList.addAll(results)
            applyFilters()

            if (smsList.isEmpty()) {
                historyRecyclerView.visibility = View.GONE
                layoutEmptyState.visibility = View.VISIBLE
                tvEmptyTitle.text = "No Messages Found"
                tvEmptyMessage.text = if (hasSmsPermission()) {
                    "There are no SMS messages in your device inbox."
                } else {
                    "No scanned messages found. Enable SMS Inbox Integration or receive messages to see history."
                }
            } else {
                layoutEmptyState.visibility = View.GONE
                historyRecyclerView.visibility = View.VISIBLE
            }
        }
    }

    // -------------------------------------------------------------------------
    // Search + Filter
    // -------------------------------------------------------------------------

    private fun setupSearchAndFilter() {
        // Filter toggle
        btnFilter.setOnClickListener {
            filterChipsVisible = !filterChipsVisible
            filterChipScroll.visibility = if (filterChipsVisible) View.VISIBLE else View.GONE
        }

        // Search watcher
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { applyFilters() }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Category chips (All / Harmful / Suspicious / Safe)
        val categoryChips = listOf(chipAll, chipHarmful, chipSuspicious, chipSafe)
        categoryChips.forEach { chip ->
            chip.setOnClickListener {
                if (activeFilterChip == chip) {
                    setChipSelected(chip, false)
                    activeFilterChip = null
                    setChipSelected(chipAll, true)
                    activeFilterChip = chipAll
                } else {
                    activeFilterChip?.let { setChipSelected(it, false) }
                    setChipSelected(chip, true)
                    activeFilterChip = chip
                }
                applyFilters()
            }
        }

        // Sort chips
        val sortChips = listOf(chipNewest, chipOldest)
        sortChips.forEach { chip ->
            chip.setOnClickListener {
                if (activeSortChip == chip) {
                    setChipSelected(chip, false)
                    activeSortChip = null
                } else {
                    activeSortChip?.let { setChipSelected(it, false) }
                    setChipSelected(chip, true)
                    activeSortChip = chip
                }
                applyFilters()
            }
        }

        // Default: All selected
        setChipSelected(chipAll, true)
        activeFilterChip = chipAll
    }

    private fun applyFilters() {
        val query = searchBox.text.toString().trim().lowercase(Locale.getDefault())

        var result = allSmsList.toList()

        // Text search — match sender or message body
        if (query.isNotEmpty()) {
            result = result.filter {
                it.sender.lowercase(Locale.getDefault()).contains(query) ||
                it.message.lowercase(Locale.getDefault()).contains(query)
            }
        }

        // Category filter
        result = when (activeFilterChip) {
            chipHarmful    -> result.filter { it.getEffectiveClassification() == Classification.SMISHING }
            chipSuspicious -> result.filter { it.getEffectiveClassification() == Classification.SUSPICIOUS }
            chipSafe       -> result.filter { it.getEffectiveClassification() == Classification.SAFE }
            else           -> result
        }

        // Sort
        result = when (activeSortChip) {
            chipNewest -> result.sortedByDescending { it.timestamp }
            chipOldest -> result.sortedBy { it.timestamp }
            else       -> result.sortedByDescending { it.timestamp } // default newest first
        }

        smsList.clear()
        smsList.addAll(result)
        smsAdapter.notifyDataSetChanged()

        if (smsList.isEmpty() && allSmsList.isNotEmpty()) {
            // Has data but filtered to nothing
            historyRecyclerView.visibility = View.GONE
            layoutEmptyState.visibility = View.VISIBLE
            tvEmptyTitle.text = "No Results"
            tvEmptyMessage.text = "No messages match your search or filter."
        } else if (smsList.isNotEmpty()) {
            historyRecyclerView.visibility = View.VISIBLE
            layoutEmptyState.visibility = View.GONE
        }
    }

    private fun setChipSelected(chip: TextView, selected: Boolean) {
        chip.setBackgroundResource(
            if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected
        )
        chip.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (selected) R.color.text_primary else R.color.text_secondary
            )
        )
    }

    private fun showDetailsDialog(result: DetectionResult) {
        val modal = AnalysisDetailsBottomSheetFragment.newInstance(result)
        modal.onBlacklistUpdatedListener = {
            loadAndClassifySms()
        }
        modal.onResultUpdatedListener = { updatedResult ->
            loadAndClassifySms()
        }
        modal.show(parentFragmentManager, "AnalysisDetailsBottomSheetFragment")
    }
}

// Recycler Adapter for SMS List
class SmsHistoryAdapter(
    private val items: List<DetectionResult>,
    private val onItemClick: (DetectionResult) -> Unit
) : RecyclerView.Adapter<SmsHistoryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val senderText: TextView = view.findViewById(R.id.history_sender)
        val messageText: TextView = view.findViewById(R.id.history_message)
        val statusBadge: TextView = view.findViewById(R.id.history_status)
        val progressBar: ProgressBar = view.findViewById(R.id.history_progress)
        val timeText: TextView = view.findViewById(R.id.history_time)
        val chipMl: LinearLayout = view.findViewById(R.id.chip_ml)
        val chipDl: LinearLayout = view.findViewById(R.id.chip_dl)
        val chipUrl: LinearLayout = view.findViewById(R.id.chip_url)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_history_sms, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context

        holder.senderText.text = item.sender
        holder.messageText.text = item.message

        // Format timestamp — sanitize to guard against epoch-seconds vs epoch-ms mismatch
        val sdf = java.text.SimpleDateFormat("h:mm a", java.util.Locale.US)
        holder.timeText.text = sdf.format(java.util.Date(sanitizeTimestamp(item.timestamp)))

        holder.itemView.isClickable = true
        holder.itemView.setOnClickListener {
            onItemClick(item)
        }

        if (item.isScanning) {
            holder.progressBar.visibility = View.VISIBLE
            holder.statusBadge.visibility = View.VISIBLE
            holder.statusBadge.text = "Scanning…"
            holder.statusBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#334155"))
            holder.statusBadge.setTextColor(Color.parseColor("#94A3B8"))

            bindChip(holder.chipMl, scanned = false)
            bindChip(holder.chipDl, scanned = false)
            bindChip(holder.chipUrl, scanned = false)
        } else {
            holder.progressBar.visibility = View.GONE
            holder.statusBadge.visibility = View.VISIBLE

            // Classification badge text + color
            val classificationName = item.getClassificationLabel()
            holder.statusBadge.text = classificationName
            val (bgColor, textColor) = when (item.classification) {
                Classification.SAFE -> Pair(R.color.detection_green_bg, R.color.detection_green_stroke)
                Classification.SUSPICIOUS -> Pair(R.color.detection_orange_bg, R.color.percentage_orange)
                Classification.SMISHING -> Pair(R.color.detection_red_bg, R.color.percentage_red)
            }
            holder.statusBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, bgColor))
            holder.statusBadge.setTextColor(ContextCompat.getColor(context, textColor))

            // ML chip — always scanned (local model always runs)
            bindChip(holder.chipMl, scanned = true)

            // DL chip — scanned if cnnProb or cnnScore is present
            val dlScanned = item.cnnProb != null || item.cnnScore != null
            bindChip(holder.chipDl, scanned = dlScanned)

            // URL chip — relevant only if a URL was found and scanned
            val urlScanned = item.urlFound && item.urlScore != null
            bindChip(holder.chipUrl, scanned = urlScanned)
        }
    }

    /** Lights up a chip (white icon + text) if scanned, dims it if pending */
    private fun bindChip(chip: LinearLayout, scanned: Boolean) {
        val alpha = if (scanned) 1.0f else 0.35f
        chip.alpha = alpha
    }

    override fun getItemCount() = items.size

    /**
     * Guards against epoch-seconds vs epoch-ms mismatch (e.g. causes "2070" dates).
     * Valid current-era ms timestamps are between Jan 1, 2000 and Jan 1, 2100.
     */
    private fun sanitizeTimestamp(timestamp: Long): Long {
        val minValidMs = 946684800000L  // Jan 1, 2000 in ms
        val maxValidMs = 4102444800000L // Jan 1, 2100 in ms
        return when {
            timestamp in minValidMs..maxValidMs -> timestamp
            timestamp * 1000L in minValidMs..maxValidMs -> timestamp * 1000L
            else -> System.currentTimeMillis()
        }
    }
}
