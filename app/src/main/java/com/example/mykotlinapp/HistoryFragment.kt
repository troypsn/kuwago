package com.example.mykotlinapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
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
        }, { result, position ->
            scanItemWithCnn(result, position)
        })
        historyRecyclerView.adapter = smsAdapter

        setupListeners()
        return view
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

                    while (c.moveToNext()) {
                        val smsId = if (idCol != -1) c.getString(idCol) else java.util.UUID.randomUUID().toString()
                        val sender = if (addrCol != -1) c.getString(addrCol) ?: "Unknown" else "Unknown"
                        val body = if (bodyCol != -1) c.getString(bodyCol) ?: "" else ""
                        val date = if (dateCol != -1) c.getLong(dateCol) else System.currentTimeMillis()

                        // Check if we already have it scanned in the main repository
                        val cached = DetectionRepository.detections.value.orEmpty().find { it.message == body && it.sender == sender }
                        if (cached != null) {
                            list.add(cached.copy(id = smsId, timestamp = date))
                        } else {
                            // Classify locally on-the-fly
                            val classificationResult = LocalClassifier.classify(ctx, body)
                            list.add(classificationResult.copy(id = smsId, sender = sender, timestamp = date))
                        }
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
                historyRecyclerView.visibility = View.VISIBLE
                layoutEmptyState.visibility = View.GONE
            }
        }
    }

    private fun scanItemWithCnn(result: DetectionResult, position: Int) {
        val ctx = context ?: return

        // 1. Visually set item state to scanning
        smsList[position] = result.copy(isScanning = true)
        smsAdapter.notifyItemChanged(position)

        // 2. Perform scan asynchronously
        scope.launch {
            val finalResult = withContext(Dispatchers.Default) {
                // Call hybrid scan (executes local + remote CNN API)
                val scanResult = SmishingDetector.analyze(ctx, result.message)
                scanResult.copy(id = result.id, sender = result.sender, timestamp = result.timestamp)
            }

            // 3. Update list and notify adapter
            if (position < smsList.size && smsList[position].id == result.id) {
                smsList[position] = finalResult
                smsAdapter.notifyItemChanged(position)
                
                // Add/Update to main repository recent detections list
                DetectionRepository.addDetection(finalResult)
            }
        }
    }

    private fun showDetailsDialog(result: DetectionResult) {
        val confidencePercent = String.format(java.util.Locale.getDefault(), "%.1f%%", result.probability * 100)
        val classification = result.classification.name.lowercase().replaceFirstChar { it.uppercase() }
        val explanation = LocalClassifier.formatDetailsExplanation(result)

        AlertDialog.Builder(requireContext())
            .setTitle("Detection Details")
            .setMessage(
                "Sender: ${result.sender}\n\n" +
                "Classification: $classification\n" +
                "Confidence: $confidencePercent\n\n" +
                "$explanation\n\n" +
                "Message:\n\"${result.message}\""
            )
            .setPositiveButton("OK", null)
            .show()
    }
}

// Recycler Adapter for SMS List
class SmsHistoryAdapter(
    private val items: List<DetectionResult>,
    private val onItemClick: (DetectionResult) -> Unit,
    private val onCnnClick: (DetectionResult, Int) -> Unit
) : RecyclerView.Adapter<SmsHistoryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val senderText: TextView = view.findViewById(R.id.history_sender)
        val messageText: TextView = view.findViewById(R.id.history_message)
        val statusBadge: TextView = view.findViewById(R.id.history_status)
        val progressBar: ProgressBar = view.findViewById(R.id.history_progress)
        val cnnButton: ImageButton = view.findViewById(R.id.btn_cnn_scan)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_history_sms, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.senderText.text = item.sender
        holder.messageText.text = item.message

        if (item.isScanning) {
            holder.progressBar.visibility = View.VISIBLE
            holder.cnnButton.visibility = View.GONE
            holder.statusBadge.visibility = View.GONE
            holder.itemView.isClickable = false
        } else {
            holder.progressBar.visibility = View.GONE
            holder.statusBadge.visibility = View.VISIBLE
            holder.itemView.isClickable = true

            // Set classification text
            val classificationName = item.classification.name.lowercase().replaceFirstChar { it.uppercase() }
            holder.statusBadge.text = classificationName

            // Colors
            val context = holder.itemView.context
            val (bgColor, textColor) = when (item.classification) {
                Classification.SAFE -> Pair(R.color.detection_green_bg, R.color.detection_green_stroke)
                Classification.SUSPICIOUS -> Pair(R.color.detection_orange_bg, R.color.percentage_orange)
                Classification.SMISHING -> Pair(R.color.detection_red_bg, R.color.percentage_red)
            }
            holder.statusBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, bgColor))
            holder.statusBadge.setTextColor(ContextCompat.getColor(context, textColor))

            // Hide/Show CNN Scan button depending on whether it has been scanned
            if (item.cnnProb != null) {
                // Already scanned
                holder.cnnButton.visibility = View.GONE
            } else {
                // Not scanned yet
                holder.cnnButton.visibility = View.VISIBLE
                holder.cnnButton.setOnClickListener {
                    onCnnClick(item, position)
                }
            }

            holder.itemView.setOnClickListener {
                onItemClick(item)
            }
        }
    }

    override fun getItemCount() = items.size
}