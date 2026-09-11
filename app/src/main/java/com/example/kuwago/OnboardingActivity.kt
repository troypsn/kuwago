package com.example.kuwago

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.viewpager2.widget.ViewPager2

class OnboardingActivity : AppCompatActivity(), OnboardingHost {
    private lateinit var pager: ViewPager2

    private val smsPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val vpnPrepareLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService()
            navigateToNextPage()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_onboarding)
        val root = findViewById<View>(R.id.onboarding_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        pager = findViewById(R.id.onboarding_pager)
        pager.adapter = OnboardingPagerAdapter(this)
        pager.isUserInputEnabled = false
        onBackPressedDispatcher.addCallback(this) {
            if (pager.currentItem == 0) finish() else navigateToPreviousPage()
        }
    }
    override fun navigateToNextPage() { if (pager.currentItem < 5) pager.currentItem++ }
    override fun navigateToPreviousPage() { if (pager.currentItem > 0) pager.currentItem-- }
    override fun skipOnboarding() = complete()
    override fun finishOnboarding() = complete()
    private fun complete() {
        getSharedPreferences("kuwago_settings", Context.MODE_PRIVATE).edit().putBoolean("hasCompletedOnboarding", true).apply()
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)); finish()
    }
    override fun requestSmsPermissions() = smsPermissionLauncher.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS))
    override fun requestNotificationPermission() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
    override fun requestVpnPermission() {
        try {
            VpnService.prepare(this)?.let(vpnPrepareLauncher::launch) ?: run {
                startVpnService()
                navigateToNextPage()
            }
        } catch (e: Exception) {
            android.util.Log.e("OnboardingActivity", "Failed to prepare VPN", e)
            navigateToNextPage()
        }
    }
    private fun startVpnService() {
        try {
            NotificationHelper.createAllNotificationChannels(this)
            ContextCompat.startForegroundService(this, Intent(this, KuwagoVpnService::class.java).setAction(KuwagoVpnService.ACTION_START))
        } catch (e: Exception) {
            android.util.Log.e("OnboardingActivity", "Failed to start VPN service", e)
        }
    }
}
