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
        switchTrainAi.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("help_train_ai", isChecked).apply()
        }

        return view
    }
}
