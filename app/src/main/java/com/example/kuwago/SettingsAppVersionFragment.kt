package com.example.kuwago

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment

class SettingsAppVersionFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_settings_app_version, container, false)
        view.findViewById<ImageView>(R.id.btn_back).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        view.findViewById<View>(R.id.tv_view_terms)?.setOnClickListener {
            TermsBottomSheetFragment().show(parentFragmentManager, "TermsBottomSheet")
        }

        // Developer Mode Toggle
        val prefs = requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val switchDevMode = view.findViewById<SwitchCompat>(R.id.switch_developer_mode)
        switchDevMode?.isChecked = prefs.getBoolean(SettingsFragment.KEY_DEVELOPER_MODE, false)

        var isProgrammaticDevChange = false
        switchDevMode?.setOnCheckedChangeListener { _, isChecked ->
            if (isProgrammaticDevChange) return@setOnCheckedChangeListener
            prefs.edit().putBoolean(SettingsFragment.KEY_DEVELOPER_MODE, isChecked).apply()
            if (isChecked) {
                CustomDialogHelper.showInfoDialog(
                    context = requireContext(),
                    title = "Developer Mode",
                    message = "Turning this on will show you extra details specifically made for developers.",
                    iconRes = R.drawable.ic_brain,
                    buttonText = "Got it"
                )
            }
        }

        view.findViewById<View>(R.id.settings_row_developer_mode)?.setOnClickListener {
            switchDevMode?.toggle()
        }

        return view
    }
}
