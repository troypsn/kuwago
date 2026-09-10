package com.example.kuwago

interface OnboardingHost {
    fun navigateToNextPage()
    fun navigateToPreviousPage()
    fun skipOnboarding()
    fun finishOnboarding()
    fun requestSmsPermissions()
    fun requestNotificationPermission()
    fun requestVpnPermission()
}
