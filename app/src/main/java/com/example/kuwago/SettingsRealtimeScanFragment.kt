package com.example.kuwago

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment

class SettingsRealtimeScanFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_settings_realtime_scan, container, false)

        view.findViewById<ImageView>(R.id.btn_back).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        // Wire the toggle to the shared preference
        val prefs = requireContext().getSharedPreferences(
            SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE
        )
        val switchInstant = view.findViewById<SwitchCompat>(R.id.switch_realtime_scan)
        switchInstant.isChecked = prefs.getBoolean(SettingsFragment.KEY_SCAN_INSTANTLY, false)
        switchInstant.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(SettingsFragment.KEY_SCAN_INSTANTLY, isChecked).apply()
        }

        return view
    }
}
