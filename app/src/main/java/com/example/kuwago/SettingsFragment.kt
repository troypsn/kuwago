package com.example.kuwago

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment

class SettingsFragment : Fragment() {

    companion object {
        const val PREFS_NAME = "kuwago_settings"
        const val KEY_LIGHT_MODE = "light_mode"
        const val KEY_THEME_MODE = "theme_mode"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        const val THEME_SYSTEM = "system"

        fun getSavedThemeMode(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_THEME_MODE, null) ?: run {
                if (prefs.contains(KEY_LIGHT_MODE)) {
                    if (prefs.getBoolean(KEY_LIGHT_MODE, false)) THEME_LIGHT else THEME_DARK
                } else {
                    THEME_SYSTEM
                }
            }
        }

        fun applySavedTheme(context: Context) {
            applyThemeMode(getSavedThemeMode(context))
        }

        fun applyThemeMode(mode: String) {
            val nightMode = when (mode) {
                THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            if (AppCompatDelegate.getDefaultNightMode() != nightMode) {
                AppCompatDelegate.setDefaultNightMode(nightMode)
            }
        }

        const val KEY_SCAN_OTHER_APPS  = "scan_other_messaging_apps"
        const val KEY_ENABLED_APP_PACKAGES = "enabled_scanned_app_packages"
        const val KEY_SCAN_INSTANTLY   = "scan_incoming_instantly"
        const val KEY_VPN_SHIELD_ENABLED = "vpn_shield_enabled"

        // Online Scan Consolidated Settings
        const val KEY_ONLINE_SCAN_MODE = "online_scan_mode"
        const val KEY_DATA_SAVER       = "data_saver"
        const val MODE_AUTOMATIC       = "automatic"
        const val MODE_ON_APP          = "on_app"
        const val MODE_DISABLED        = "disabled"

        // Auto-blacklist Setting Keys
        const val KEY_AUTO_BLACKLIST_ENABLED   = "auto_blacklist_enabled"
        const val KEY_AUTO_BLACKLIST_THRESHOLD = "auto_blacklist_threshold"
        const val THRESHOLD_SUSPICIOUS_AND_ABOVE = "suspicious_and_above"
        const val THRESHOLD_HARMFUL             = "harmful"

        // NTC Auto-report Setting
        const val KEY_AUTO_REPORT_NTC  = "auto_report_ntc"

        // Developer Mode Setting
        const val KEY_DEVELOPER_MODE   = "developer_mode"

        // System Status Setting Keys
        const val KEY_SYSTEM_STATUS    = "system_status"
        const val STATUS_ON            = "on"
        const val STATUS_OFF           = "off"
        const val STATUS_DATA_SAVER    = "data_saver"

        // Legacy keys kept for backwards compatibility
        const val KEY_URL_SCAN         = "url_scan"
        const val KEY_DEEP_ANALYSIS    = "deep_analysis"

        // Notification channel IDs
        const val CHANNEL_SCANNING    = "kuwago_scanning"
        const val CHANNEL_RESULT      = "kuwago_result_v2"
        /** High-priority channel — fires when VPN blocks a phishing host. */
        const val CHANNEL_VPN_BLOCK   = "kuwago_vpn_block"
        /** Low-priority channel — ongoing VPN-active status notification. */
        const val CHANNEL_VPN_ONGOING = "kuwago_vpn_ongoing"
    }

    private val themeModeValues = listOf(
        THEME_LIGHT,
        THEME_DARK,
        THEME_SYSTEM
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val spinnerThemeMode = view.findViewById<Spinner>(R.id.spinner_theme_mode)
        val rowLightMode = view.findViewById<View>(R.id.settings_row_light_mode)

        val themeLabels = listOf(
            getString(R.string.settings_theme_light),
            getString(R.string.settings_theme_dark),
            getString(R.string.settings_theme_system)
        )

        val adapter = ArrayAdapter(
            requireContext(),
            R.layout.item_spinner_mode,
            themeLabels
        ).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown_mode)
        }
        spinnerThemeMode?.adapter = adapter

        val savedThemeMode = getSavedThemeMode(requireContext())
        val initialPosition = themeModeValues.indexOf(savedThemeMode).coerceAtLeast(0)
        spinnerThemeMode?.setSelection(initialPosition, false)

        spinnerThemeMode?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                val selectedMode = themeModeValues.getOrElse(position) { THEME_SYSTEM }
                if (getSavedThemeMode(requireContext()) != selectedMode) {
                    prefs.edit()
                        .putString(KEY_THEME_MODE, selectedMode)
                        .putBoolean(KEY_LIGHT_MODE, selectedMode == THEME_LIGHT)
                        .apply()
                    applyThemeMode(selectedMode)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        rowLightMode?.setOnClickListener {
            spinnerThemeMode?.performClick()
        }

        view.findViewById<View>(R.id.settings_row_realtime_scan).setOnClickListener {
            openSubPage(SettingsRealtimeScanFragment())
        }

        view.findViewById<View>(R.id.settings_row_online_scan).setOnClickListener {
            openSubPage(SettingsOnlineScanFragment())
        }

        view.findViewById<View>(R.id.settings_row_auto_blacklist)?.setOnClickListener {
            openSubPage(SettingsAutoBlacklistFragment())
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

        view.findViewById<View>(R.id.settings_row_terms).setOnClickListener {
            TermsBottomSheetFragment().show(parentFragmentManager, "TermsBottomSheet")
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
