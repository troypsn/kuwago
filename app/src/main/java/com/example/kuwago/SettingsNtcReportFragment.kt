package com.example.kuwago

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment

class SettingsNtcReportFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_settings_ntc_report, container, false)
        view.findViewById<ImageView>(R.id.btn_back).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        val prefs = requireContext().getSharedPreferences(
            SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE
        )
        val switchNtc = view.findViewById<SwitchCompat>(R.id.switch_ntc_report)
        switchNtc.isChecked = prefs.getBoolean(SettingsFragment.KEY_AUTO_REPORT_NTC, false)

        switchNtc.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(SettingsFragment.KEY_AUTO_REPORT_NTC, isChecked).apply()
        }

        return view
    }
}
