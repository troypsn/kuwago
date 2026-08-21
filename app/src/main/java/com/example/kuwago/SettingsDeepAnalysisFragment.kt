package com.example.kuwago

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment

class SettingsDeepAnalysisFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_settings_deep_analysis, container, false)
        
        view.findViewById<ImageView>(R.id.btn_back).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        val prefs = requireContext().getSharedPreferences(
            SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE
        )
        val switchDeepAnalysis = view.findViewById<SwitchCompat>(R.id.switch_deep_analysis)
        switchDeepAnalysis.isChecked = prefs.getBoolean("deep_analysis", true)
        switchDeepAnalysis.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("deep_analysis", isChecked).apply()
        }

        return view
    }
}
