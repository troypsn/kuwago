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

        var isProgrammaticChange = false
        switchNtc.setOnCheckedChangeListener { _, isChecked ->
            if (isProgrammaticChange) return@setOnCheckedChangeListener
            if (!isChecked) {
                CustomDialogHelper.showConfirmDialog(
                    context = requireContext(),
                    title = "Warning",
                    message = "You are about to turn off Auto-Report to NTC.",
                    iconRes = R.drawable.ic_warning_triangle,
                    iconTint = android.graphics.Color.parseColor("#F07048"),
                    confirmText = "Turn Off",
                    confirmBg = R.drawable.bg_red_button,
                    cancelText = "Cancel",
                    cancelBg = R.drawable.bg_dark_button,
                    onConfirm = {
                        prefs.edit().putBoolean(SettingsFragment.KEY_AUTO_REPORT_NTC, false).apply()
                    },
                    onCancel = {
                        isProgrammaticChange = true
                        switchNtc.isChecked = true
                        isProgrammaticChange = false
                    },
                    onDismiss = {
                        if (prefs.getBoolean(SettingsFragment.KEY_AUTO_REPORT_NTC, false)) {
                            isProgrammaticChange = true
                            switchNtc.isChecked = true
                            isProgrammaticChange = false
                        }
                    }
                )
            } else {
                prefs.edit().putBoolean(SettingsFragment.KEY_AUTO_REPORT_NTC, true).apply()
            }
        }

        return view
    }
}
