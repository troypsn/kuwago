package com.example.mykotlinapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment

class SettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<View>(R.id.settings_row_realtime_scan).setOnClickListener {
            openSubPage(SettingsRealtimeScanFragment())
        }

        view.findViewById<View>(R.id.settings_row_auto_blacklist).setOnClickListener {
            openSubPage(SettingsAutoBlacklistFragment())
        }

        view.findViewById<View>(R.id.settings_row_deep_analysis).setOnClickListener {
            openSubPage(SettingsDeepAnalysisFragment())
        }

        view.findViewById<View>(R.id.settings_row_url_scan).setOnClickListener {
            openSubPage(SettingsUrlScanFragment())
        }

        view.findViewById<View>(R.id.settings_row_get_support).setOnClickListener {
            openSubPage(SettingsGetSupportFragment())
        }

        view.findViewById<View>(R.id.settings_row_train_ai).setOnClickListener {
            openSubPage(SettingsTrainAiFragment())
        }

        view.findViewById<View>(R.id.settings_row_ntc_report).setOnClickListener {
            openSubPage(SettingsNtcReportFragment())
        }

        view.findViewById<View>(R.id.settings_row_app_version).setOnClickListener {
            openSubPage(SettingsAppVersionFragment())
        }
    }

    private fun openSubPage(fragment: Fragment) {
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }
}
