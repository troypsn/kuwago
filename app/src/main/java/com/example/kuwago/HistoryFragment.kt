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
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

class HistoryFragment : Fragment() {

    private lateinit var switchSmsPermission: SwitchCompat
    private lateinit var historyRecyclerView: RecyclerView
    private lateinit var layoutEmptyState: LinearLayout
    private lateinit var tvPermissionDesc: TextView
    private lateinit var tvEmptyTitle: TextView
    private lateinit var tvEmptyMessage: TextView

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    private val smsList = mutableListOf<DetectionResult>()
    private lateinit var smsAdapter: SmsHistoryAdapter

    private var isUserAction = true // Prevents request loops when toggling switch programmatically

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

        historyRecyclerView.layoutManager = LinearLayoutManager(context)
        smsAdapter = SmsHistoryAdapter(smsList, { result ->
            showDetailsDialog(result)
        })
        historyRecyclerView.adapter = smsAdapter

        setupListeners()
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        context?.let { DetectionRepository.loadIfNeeded(it) }
        DetectionRepository.detections.observe(viewLifecycleOwner) { liveList ->
            for (liveItem in liveList) {
                val index = smsList.indexOfFirst {
                    it.id == liveItem.id || (it.message == liveItem.message && it.sender == liveItem.sender)
                }
                if (index != -1) {
                    val current = smsList[index]
                    if (liveItem.cnnProb != current.cnnProb || liveItem.isScanning != current.isScanning || liveItem.classification != current.classification) {
                        smsList[index] = liveItem.copy(id = current.id, timestamp = current.timestamp)
                        smsAdapter.notifyItemChanged(index)
                    }
                }
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
                // Disable Integration
                clearSmsList()
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
            loadAndClassifySms()
        } else {
            switchSmsPermission.isChecked = false
            tvPermissionDesc.text = "Grant permission to analyze device SMS history"
            clearSmsList()
        }
        isUserAction = true
    }

    private fun clearSmsList() {
        smsList.clear()
        smsAdapter.notifyDataSetChanged()
        historyRecyclerView.visibility = View.GONE
        layoutEmptyState.visibility = View.VISIBLE
    }

    private fun loadAndClassifySms() {
        val ctx = context ?: return
        historyRecyclerView.visibility = View.VISIBLE
        layoutEmptyState.visibility = View.GONE

        // Run query and local classification asynchronously
        scope.launch {
            val results = withContext(Dispatchers.IO) {
                val list = mutableListOf<DetectionResult>()

                // 1. Prepend manual scans from the Scan screen (DetectionRepository)
                //    These use a "manual_" id prefix to avoid clashing with SMS inbox ids.
                val manualScans = DetectionRepository.detections.value.orEmpty()
                    .map { it.copy(id = "manual_${it.id}") }
                list.addAll(manualScans)

                // 2. Load SMS inbox entries (only if permission granted)
                val cursor: Cursor? = ctx.contentResolver.query(
                    Uri.parse("content://sms/inbox"),
                    arrayOf("_id", "address", "body", "date"),
                    null,
                    null,
                    "date DESC LIMIT 50" // Limit to last 50 for performance
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

                        // Skip if this SMS body was already added as a manual scan
                        if (manualScans.any { it.message == body && it.sender == sender }) continue

                        // Check if we already have it scanned in the main repository
                        val cached = DetectionRepository.detections.value.orEmpty().find { it.message == body && it.sender == sender }
                        if (cached != null) {
                            list.add(cached.copy(id = smsId, timestamp = date))
                        } else {
                            // Classify locally on-the-fly
                            val classificationResult = LocalClassifier.classify(ctx, body)
                            val finalRes = classificationResult.copy(id = smsId, sender = sender, timestamp = date)
                            list.add(finalRes)
                            newlyClassified.add(finalRes)
                        }
                    }
                    if (newlyClassified.isNotEmpty()) {
                        DetectionRepository.addDetections(newlyClassified)
                    }
                }
                list
            }

            smsList.clear()
            smsList.addAll(results)
            smsAdapter.notifyDataSetChanged()

            if (smsList.isEmpty()) {
                historyRecyclerView.visibility = View.GONE
                layoutEmptyState.visibility = View.VISIBLE
                tvEmptyTitle.text = "No Messages Found"
                tvEmptyMessage.text = "There are no SMS messages in your device inbox."
            } else {
                historyRecyclerView.visibility = View.GONE
                layoutEmptyState.visibility = View.GONE
                historyRecyclerView.visibility = View.VISIBLE
            }
        }
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

        // Format timestamp
        val sdf = java.text.SimpleDateFormat("h:mm a", java.util.Locale.US)
        holder.timeText.text = sdf.format(java.util.Date(item.timestamp))

        if (item.isScanning) {
            holder.progressBar.visibility = View.VISIBLE
            holder.statusBadge.visibility = View.GONE
            holder.itemView.isClickable = false
        } else {
            holder.progressBar.visibility = View.GONE
            holder.statusBadge.visibility = View.VISIBLE
            holder.itemView.isClickable = true

            // Classification badge text + color
            val classificationName = item.classification.name.lowercase().replaceFirstChar { it.uppercase() }
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

            holder.itemView.setOnClickListener {
                onItemClick(item)
            }
        }
    }

    /** Lights up a chip (white icon + text) if scanned, dims it if pending */
    private fun bindChip(chip: LinearLayout, scanned: Boolean) {
        val alpha = if (scanned) 1.0f else 0.35f
        chip.alpha = alpha
    }

    override fun getItemCount() = items.size
}
