package com.example.kuwago
import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
class OnboardingUrlShieldFragment : Fragment() {
 override fun onCreateView(i: LayoutInflater,c: ViewGroup?,s: Bundle?)=i.inflate(R.layout.fragment_onboarding_url_shield,c,false)
 override fun onViewCreated(v:View,s:Bundle?){v.findViewById<View>(R.id.btn_enable_shield).setOnClickListener{(activity as? OnboardingHost)?.requestVpnPermission()};v.findViewById<View>(R.id.btn_shield_later).setOnClickListener{(activity as? OnboardingHost)?.navigateToNextPage()}}
}
