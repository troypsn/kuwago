package com.example.kuwago

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment

class OnboardingPermissionsFragment : Fragment() {
    private lateinit var sms: Button; private lateinit var notifications: Button; private lateinit var listener: Button; private lateinit var continueButton: Button
    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?) = i.inflate(R.layout.fragment_onboarding_permissions, c, false)
    override fun onViewCreated(v: View, s: Bundle?) {
        sms = v.findViewById(R.id.btn_enable_sms); notifications = v.findViewById(R.id.btn_enable_notifications); listener = v.findViewById(R.id.btn_enable_listener); continueButton = v.findViewById(R.id.btn_permissions_continue)
        sms.setOnClickListener { (activity as? OnboardingHost)?.requestSmsPermissions() }
        notifications.setOnClickListener { (activity as? OnboardingHost)?.requestNotificationPermission() }
        listener.setOnClickListener { PermissionHelper.openNotificationListenerSettings(requireContext()) }
        v.findViewById<View>(R.id.btn_open_app_settings)?.setOnClickListener { PermissionHelper.openAppSettings(requireContext()) }
        continueButton.setOnClickListener { (activity as? OnboardingHost)?.navigateToNextPage() }
    }
    override fun onResume() { super.onResume(); if (!isAdded || !::sms.isInitialized) return; refresh() }
    private fun refresh() {
        val c = requireContext(); val smsOk = PermissionHelper.hasSmsPermissions(c); val notificationOk = PermissionHelper.hasNotificationPermission(c); val listenerOk = PermissionHelper.isNotificationServiceEnabled(c)
        updatePermissionButton(sms, smsOk)
        updatePermissionButton(notifications, notificationOk)
        updatePermissionButton(listener, listenerOk)
        
        val showNotifs = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        view?.findViewById<View>(R.id.row_notifications)?.visibility = if (showNotifs) View.VISIBLE else View.GONE
        
        continueButton.isEnabled = PermissionHelper.isAllRequiredProtectionGranted(c)
        view?.findViewById<View>(R.id.btn_open_app_settings)?.visibility = if (!smsOk) View.VISIBLE else View.GONE
    }
    private fun updatePermissionButton(button: Button, enabled: Boolean) {
        button.text = getString(if (enabled) R.string.onboarding_enabled else R.string.onboarding_enable)
        button.setTextColor(requireContext().getColor(if (enabled) R.color.text_secondary else R.color.black))
        button.setBackgroundResource(if (enabled) R.drawable.bg_onboarding_enabled else R.drawable.bg_onboarding_primary)
    }
}
