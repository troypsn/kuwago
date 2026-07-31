package com.example.kuwago

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.fragment.app.Fragment

class SettingsGetSupportFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_settings_get_support, container, false)
        view.findViewById<ImageView>(R.id.btn_back).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        return view
    }
}
