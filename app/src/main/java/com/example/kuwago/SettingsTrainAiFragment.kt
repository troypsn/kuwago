package com.example.kuwago

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment

class SettingsTrainAiFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_settings_train_ai, container, false)
        
        view.findViewById<ImageView>(R.id.btn_back).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        val prefs = requireContext().getSharedPreferences(
            SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE
        )
        val switchTrainAi = view.findViewById<SwitchCompat>(R.id.switch_train_ai)
        switchTrainAi.isChecked = prefs.getBoolean("help_train_ai", false)
        var isProgrammaticChange = false

        switchTrainAi.setOnCheckedChangeListener { _, isChecked ->
            if (isProgrammaticChange) return@setOnCheckedChangeListener
            if (!isChecked) {
                CustomDialogHelper.showConfirmDialog(
                    context = requireContext(),
                    title = "Turn off Train AI?",
                    message = "Are you sure you want to turn off \"Help Train AI\"?",
                    iconRes = R.drawable.ic_warning_triangle,
                    iconTint = android.graphics.Color.parseColor("#F07048"),
                    confirmText = "Turn Off",
                    confirmBg = R.drawable.bg_red_button,
                    cancelText = "Cancel",
                    cancelBg = R.drawable.bg_dark_button,
                    onConfirm = {
                        prefs.edit().putBoolean("help_train_ai", false).apply()
                    },
                    onCancel = {
                        isProgrammaticChange = true
                        switchTrainAi.isChecked = true
                        isProgrammaticChange = false
                    },
                    onDismiss = {
                        if (prefs.getBoolean("help_train_ai", false)) {
                            isProgrammaticChange = true
                            switchTrainAi.isChecked = true
                            isProgrammaticChange = false
                        }
                    }
                )
            } else {
                prefs.edit().putBoolean("help_train_ai", true).apply()
            }
        }

        return view
    }
}
