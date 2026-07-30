package com.example.mykotlinapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment

class SettingsAutoBlacklistFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_settings_auto_blacklist, container, false)
        view.findViewById<ImageView>(R.id.btn_back).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        val switchAutoBlacklist = view.findViewById<SwitchCompat>(R.id.switch_auto_blacklist)
        val ctx = requireContext()
        switchAutoBlacklist.isChecked = BlacklistRepository.isAutoBlacklistEnabled(ctx)

        switchAutoBlacklist.setOnCheckedChangeListener { _, isChecked ->
            BlacklistRepository.setAutoBlacklistEnabled(ctx, isChecked)
            val msg = if (isChecked) "Auto-blacklisting enabled" else "Auto-blacklisting disabled"
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }

        return view
    }
}
