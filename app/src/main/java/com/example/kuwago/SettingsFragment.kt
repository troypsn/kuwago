package com.example.kuwago

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment

class SettingsFragment : Fragment() {

    companion object {
        const val PREFS_NAME = "kuwago_settings"
        const val KEY_SCAN_OTHER_APPS  = "scan_other_messaging_apps"
        const val KEY_ENABLED_APP_PACKAGES = "enabled_scanned_app_packages"
        const val KEY_SCAN_INSTANTLY   = "scan_incoming_instantly"
        const val KEY_VPN_SHIELD_ENABLED = "vpn_shield_enabled"

        // Notification channel IDs
        const val CHANNEL_SCANNING    = "kuwago_scanning"
        const val CHANNEL_RESULT      = "kuwago_result_v2"
        /** High-priority channel — fires when VPN blocks a phishing host. */
        const val CHANNEL_VPN_BLOCK   = "kuwago_vpn_block"
        /** Low-priority channel — ongoing VPN-active status notification. */
        const val CHANNEL_VPN_ONGOING = "kuwago_vpn_ongoing"
    }

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

        view.findViewById<View>(R.id.settings_row_deep_analysis).setOnClickListener {
            openSubPage(SettingsDeepAnalysisFragment())
        }

        view.findViewById<View>(R.id.settings_row_url_scan).setOnClickListener {
            openSubPage(SettingsUrlScanFragment())
        }

        view.findViewById<View>(R.id.settings_row_vpn_shield).setOnClickListener {
            openSubPage(SettingsVpnShieldFragment())
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

        view.findViewById<View>(R.id.settings_row_scan_other_apps).setOnClickListener {
            openSubPage(SettingsAppSelectionFragment())
        }
    }

    private fun openSubPage(fragment: Fragment) {
        parentFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.fragment_enter,
                R.anim.fragment_exit,
                R.anim.fragment_pop_enter,
                R.anim.fragment_pop_exit
            )
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }
}
