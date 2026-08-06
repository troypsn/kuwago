package com.example.kuwago

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScanFragment : Fragment() {

    private lateinit var etPhoneNumber: EditText
    private lateinit var etSenderName: EditText
    private lateinit var etMessageContent: EditText
    private lateinit var tvCharCounter: TextView
    private lateinit var btnFullAnalysis: Button
    private lateinit var scanLoadingOverlay: FrameLayout

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    companion object {
        private const val MAX_CHARS = 1600
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_scan, container, false)

        etPhoneNumber = view.findViewById(R.id.et_phone_number)
        etSenderName = view.findViewById(R.id.et_sender_name)
        etMessageContent = view.findViewById(R.id.et_message_content)
        tvCharCounter = view.findViewById(R.id.tv_char_counter)
        btnFullAnalysis = view.findViewById(R.id.btn_full_analysis)
        scanLoadingOverlay = view.findViewById(R.id.scan_loading_overlay)

        setupCharCounter()
        setupListeners()

        return view
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    private fun setupCharCounter() {
        etMessageContent.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val len = s?.length ?: 0
                tvCharCounter.text = "$len/$MAX_CHARS"
                // Tint counter red when near limit
                val color = if (len >= MAX_CHARS - 100) {
                    android.graphics.Color.parseColor("#FFD93B55")
                } else {
                    android.graphics.Color.parseColor("#FF888888")
                }
                tvCharCounter.setTextColor(color)
            }
        })
    }

    private fun setupListeners() {
        btnFullAnalysis.setOnClickListener {
            startScan()
        }
    }

    private fun startScan() {
        val ctx = context ?: return

        val messageText = etMessageContent.text.toString().trim()
        if (messageText.isEmpty()) {
            Toast.makeText(ctx, getString(R.string.scan_error_empty_message), Toast.LENGTH_SHORT).show()
            etMessageContent.requestFocus()
            return
        }

        // Build sender string: phone takes priority, then name, then "Unknown"
        val sender = buildSenderString()

        setLoadingState(true)

        scope.launch {
            // ── Phase 1: Local models (instant) ───────────────────────────────
            val localResult = try {
                withContext(Dispatchers.IO) {
                    LocalClassifier.classify(ctx, messageText).copy(sender = sender, message = messageText)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setLoadingState(false)
                    Toast.makeText(ctx, "Local scan failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                // Save local result immediately so History shows it right away
                DetectionRepository.addDetection(localResult)

                setLoadingState(false)

                val classLabel = localResult.classification.name
                    .lowercase()
                    .replaceFirstChar { it.uppercase() }
                val prob = (localResult.probability * 100).toInt()
                Toast.makeText(
                    ctx,
                    "Local result: $classLabel ($prob%) — Running deep analysis…",
                    Toast.LENGTH_LONG
                ).show()

                // Navigate to History immediately so the user sees the local result
                (requireActivity() as? MainActivity)?.navigateToHistory()
            }

            // ── Phase 2: CNN + URL deep analysis (background, non-blocking) ──
            // Runs after we've already navigated away; updates the existing entry
            // in the repository once the API responds, just like the History retry button.
            scope.launch {
                try {
                    val deepResult = withContext(Dispatchers.IO) {
                        SmishingDetector.analyze(ctx, messageText, sender)
                            .copy(id = localResult.id) // keep the same ID so updateDetection matches
                    }
                    withContext(Dispatchers.Main) {
                        DetectionRepository.updateDetection(deepResult)
                    }
                } catch (_: Exception) {
                    // CNN failed — local result stays; user can retry from History if needed
                }
            }
        }
    }

    /**
     * Build the sender string used for the scan.
     * - If a phone number is entered: format as +63XXXXXXXXXX
     * - Else if a name is entered: use the name
     * - Otherwise: "Unknown"
     */
    private fun buildSenderString(): String {
        val rawPhone = etPhoneNumber.text.toString().trim()
        if (rawPhone.isNotEmpty()) {
            // Strip spaces, dashes, and leading zeros
            val digits = rawPhone.replace(Regex("[\\s\\-()]"), "").trimStart('0')
            return "+63$digits"
        }

        val name = etSenderName.text.toString().trim()
        if (name.isNotEmpty()) return name

        return "Unknown"
    }

    private fun setLoadingState(isLoading: Boolean) {
        if (isLoading) {
            btnFullAnalysis.visibility = View.GONE
            scanLoadingOverlay.visibility = View.VISIBLE
            etPhoneNumber.isEnabled = false
            etSenderName.isEnabled = false
            etMessageContent.isEnabled = false
        } else {
            btnFullAnalysis.visibility = View.VISIBLE
            scanLoadingOverlay.visibility = View.GONE
            etPhoneNumber.isEnabled = true
            etSenderName.isEnabled = true
            etMessageContent.isEnabled = true
        }
    }
}
