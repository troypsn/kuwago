package com.example.kuwago

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment

class OnboardingGetStartedFragment : Fragment() {
    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?) = i.inflate(R.layout.fragment_onboarding_get_started, c, false)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) { view.findViewById<View>(R.id.btn_get_started).setOnClickListener { (activity as? OnboardingHost)?.navigateToNextPage() } }
}
