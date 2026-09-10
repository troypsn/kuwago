package com.example.kuwago
import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
class OnboardingSecurityFragment : Fragment() {
 override fun onCreateView(i: LayoutInflater,c: ViewGroup?,s: Bundle?)=i.inflate(R.layout.fragment_onboarding_security,c,false)
 override fun onViewCreated(v:View,s:Bundle?){ v.findViewById<View>(R.id.btn_security_continue).setOnClickListener{(activity as? OnboardingHost)?.navigateToNextPage()} }
}
