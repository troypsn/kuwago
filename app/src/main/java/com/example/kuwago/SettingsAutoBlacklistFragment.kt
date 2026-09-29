package com.example.kuwago

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment

class SettingsAutoBlacklistFragment : Fragment() {

    private val thresholdValues = listOf(
        SettingsFragment.THRESHOLD_SUSPICIOUS_AND_ABOVE,
        SettingsFragment.THRESHOLD_HARMFUL
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_settings_auto_blacklist, container, false)

        view.findViewById<ImageView>(R.id.btn_back).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        val prefs = requireContext().getSharedPreferences(
            SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE
        )

        val switchAutoBlacklist = view.findViewById<SwitchCompat>(R.id.switch_auto_blacklist)
        val spinner = view.findViewById<Spinner>(R.id.spinner_block_classification)
        val tvDesc = view.findViewById<TextView>(R.id.tv_block_classification_desc)

        // Setup Switch
        val isEnabled = prefs.getBoolean(SettingsFragment.KEY_AUTO_BLACKLIST_ENABLED, false) ||
                prefs.getBoolean("auto_blacklist", false)
        switchAutoBlacklist.isChecked = isEnabled

        switchAutoBlacklist.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit()
                .putBoolean(SettingsFragment.KEY_AUTO_BLACKLIST_ENABLED, isChecked)
                .putBoolean("auto_blacklist", isChecked)
                .apply()
        }

        // Setup Spinner
        val thresholdLabels = listOf(
            getString(R.string.settings_auto_blacklist_opt_suspicious_above),
            getString(R.string.settings_auto_blacklist_opt_harmful)
        )

        val adapter = ArrayAdapter(
            requireContext(),
            R.layout.item_spinner_auto_blacklist,
            thresholdLabels
        ).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown_auto_blacklist)
        }
        spinner.adapter = adapter

        // Restore saved threshold
        val savedThreshold = prefs.getString(
            SettingsFragment.KEY_AUTO_BLACKLIST_THRESHOLD,
            SettingsFragment.THRESHOLD_SUSPICIOUS_AND_ABOVE
        ) ?: SettingsFragment.THRESHOLD_SUSPICIOUS_AND_ABOVE

        val initialPosition = thresholdValues.indexOf(savedThreshold).coerceAtLeast(0)
        spinner.setSelection(initialPosition)
        updateThresholdDescription(tvDesc, initialPosition)

        var isInitialSetup = true
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                updateThresholdDescription(tvDesc, position)
                if (!isInitialSetup) {
                    val selected = thresholdValues.getOrElse(position) { SettingsFragment.THRESHOLD_SUSPICIOUS_AND_ABOVE }
                    prefs.edit().putString(SettingsFragment.KEY_AUTO_BLACKLIST_THRESHOLD, selected).apply()
                }
                isInitialSetup = false
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        return view
    }

    private fun updateThresholdDescription(tv: TextView, position: Int) {
        val descRes = when (position) {
            1 -> R.string.settings_auto_blacklist_desc_harmful
            else -> R.string.settings_auto_blacklist_desc_suspicious_above
        }
        tv.setText(descRes)
    }
}
