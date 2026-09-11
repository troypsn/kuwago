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

class SettingsOnlineScanFragment : Fragment() {

    private val modeValues = listOf(
        SettingsFragment.MODE_AUTOMATIC,
        SettingsFragment.MODE_ON_APP,
        SettingsFragment.MODE_DISABLED
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_settings_online_scan, container, false)

        view.findViewById<ImageView>(R.id.btn_back).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        val prefs = requireContext().getSharedPreferences(
            SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE
        )

        val spinner = view.findViewById<Spinner>(R.id.spinner_online_scan_mode)
        val tvModeDesc = view.findViewById<TextView>(R.id.tv_mode_description)
        val switchDataSaver = view.findViewById<SwitchCompat>(R.id.switch_data_saver)

        // Setup Spinner
        val modeLabels = listOf(
            getString(R.string.settings_online_scan_mode_auto),
            getString(R.string.settings_online_scan_mode_on_app),
            getString(R.string.settings_online_scan_mode_disabled)
        )

        val adapter = ArrayAdapter(
            requireContext(),
            R.layout.item_spinner_mode,
            modeLabels
        ).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown_mode)
        }
        spinner.adapter = adapter

        // Restore saved mode
        val savedMode = prefs.getString(
            SettingsFragment.KEY_ONLINE_SCAN_MODE,
            SettingsFragment.MODE_AUTOMATIC
        ) ?: SettingsFragment.MODE_AUTOMATIC

        val initialPosition = modeValues.indexOf(savedMode).coerceAtLeast(0)
        spinner.setSelection(initialPosition)
        updateModeDescription(tvModeDesc, initialPosition)

        var isInitialSetup = true
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                updateModeDescription(tvModeDesc, position)
                if (!isInitialSetup) {
                    val selectedMode = modeValues.getOrElse(position) { SettingsFragment.MODE_AUTOMATIC }
                    prefs.edit().putString(SettingsFragment.KEY_ONLINE_SCAN_MODE, selectedMode).apply()
                }
                isInitialSetup = false
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Setup Data Saver Switch
        switchDataSaver.isChecked = prefs.getBoolean(SettingsFragment.KEY_DATA_SAVER, false)
        switchDataSaver.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(SettingsFragment.KEY_DATA_SAVER, isChecked).apply()
        }

        return view
    }

    private fun updateModeDescription(tv: TextView, position: Int) {
        val descRes = when (position) {
            1 -> R.string.settings_online_scan_mode_on_app_desc
            2 -> R.string.settings_online_scan_mode_disabled_desc
            else -> R.string.settings_online_scan_mode_auto_desc
        }
        tv.setText(descRes)
    }
}
