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
        var isProgrammaticChange = false

        switchUrlScan.setOnCheckedChangeListener { _, isChecked ->
            if (isProgrammaticChange) return@setOnCheckedChangeListener
            if (!isChecked) {
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setMessage("Are you sure you want to turn off \"URL Scan\"?")
                    .setPositiveButton("Turn Off") { _, _ ->
                        prefs.edit().putBoolean("url_scan", false).apply()
                    }
                    .setNegativeButton("Cancel") { dialog, _ ->
                        isProgrammaticChange = true
                        switchUrlScan.isChecked = true
                        isProgrammaticChange = false
                        dialog.dismiss()
                    }
                    .setOnCancelListener {
                        isProgrammaticChange = true
                        switchUrlScan.isChecked = true
                        isProgrammaticChange = false
                    }
                    .show()
            } else {
                prefs.edit().putBoolean("url_scan", true).apply()
            }
        }

        return view
    }
}
