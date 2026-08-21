package com.example.kuwago

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment

class SettingsUrlScanFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_settings_url_scan, container, false)
        
        view.findViewById<ImageView>(R.id.btn_back).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        val prefs = requireContext().getSharedPreferences(
            SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE
        )
        val switchUrlScan = view.findViewById<SwitchCompat>(R.id.switch_url_scan)
        switchUrlScan.isChecked = prefs.getBoolean("url_scan", true)
        switchUrlScan.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("url_scan", isChecked).apply()
        }

        return view
    }
}
