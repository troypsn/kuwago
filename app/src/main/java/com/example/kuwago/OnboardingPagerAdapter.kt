package com.example.kuwago

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class OnboardingPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount() = 6
    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> OnboardingGetStartedFragment(); 1 -> OnboardingPermissionsFragment()
        2 -> OnboardingSecurityFragment(); 3 -> OnboardingDetectionFragment()
        4 -> OnboardingUrlShieldFragment(); else -> OnboardingAppSelectionFragment()
    }
}
