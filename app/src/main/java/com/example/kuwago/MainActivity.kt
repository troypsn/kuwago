package com.example.kuwago

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment

class MainActivity : AppCompatActivity() {

    private val SMS_PERMISSION_CODE = 101

    // Nav item containers
    private lateinit var navHome: LinearLayout
    private lateinit var navHistory: LinearLayout
    private lateinit var navScan: LinearLayout
    private lateinit var navBlacklist: LinearLayout
    private lateinit var navSettings: LinearLayout

    // Nav icons
    private lateinit var homeIcon: ImageView
    private lateinit var historyIcon: ImageView
    private lateinit var scanIcon: ImageView
    private lateinit var blacklistIcon: ImageView
    private lateinit var settingsIcon: ImageView

    // Nav labels
    private lateinit var homeText: TextView
    private lateinit var historyText: TextView
    private lateinit var blacklistText: TextView
    private lateinit var settingsText: TextView

    private var currentTab = "home"

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            insets
        }

        initViews()
        setupNavigation()
        checkSmsPermissions()
        createNotificationChannels()

        // Set default fragment
        if (savedInstanceState == null) {
            switchFragment(HomeFragment(), "home")
            updateNavUI("home")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        checkForPendingWarnings()
        checkNotificationPermission()
    }

    /**
     * Check if the background service flagged any suspicious senders.
     * If so, show the warning popup now that the user has opened the app.
     */
    private fun checkForPendingWarnings() {
        // 1. Check if we have extras from a notification tap
        val intentSender = intent.getStringExtra("EXTRA_SENDER")
        val intentMessage = intent.getStringExtra("EXTRA_MESSAGE")
        val intentConfidence = intent.getStringExtra("EXTRA_CONFIDENCE")

        if (intentSender != null && intentMessage != null) {
            // Clear these extras so we don't show the popup repeatedly on every onResume
            intent.removeExtra("EXTRA_SENDER")
            intent.removeExtra("EXTRA_MESSAGE")
            intent.removeExtra("EXTRA_CONFIDENCE")

            if (!BlacklistRepository.isBlacklisted(this, intentSender) &&
                !BlacklistRepository.isWarningAcknowledged(this, intentSender)
            ) {
                val overlayIntent = Intent(this, WarningOverlayActivity::class.java).apply {
                    putExtra("EXTRA_SENDER", intentSender)
                    putExtra("EXTRA_MESSAGE", intentMessage)
                    putExtra("EXTRA_CONFIDENCE", intentConfidence ?: "High Threat")
                }
                startActivity(overlayIntent)
                return
            }
        }

        // 2. Fallback to PendingWarningRepository (when app is opened normally)
        val pending = PendingWarningRepository.getPendingWarning(this) ?: return

        // Only show if the sender is still not blacklisted and not already acknowledged
        if (BlacklistRepository.isBlacklisted(this, pending.sender) ||
            BlacklistRepository.isWarningAcknowledged(this, pending.sender)
        ) {
            PendingWarningRepository.clearPendingWarning(this)
            return
        }

        // Clear it first so it doesn't re-trigger on next onResume
        PendingWarningRepository.clearPendingWarning(this)

        val overlayIntent = Intent(this, WarningOverlayActivity::class.java).apply {
            putExtra("EXTRA_SENDER", pending.sender)
            putExtra("EXTRA_MESSAGE", pending.message)
            putExtra("EXTRA_CONFIDENCE", pending.confidence)
        }
        startActivity(overlayIntent)
    }

    private fun checkSmsPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val ungranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (ungranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, ungranted.toTypedArray(), SMS_PERMISSION_CODE)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            // Channel 1 – ongoing scanning progress (low importance = no sound/heads-up)
            val scanningChannel = NotificationChannel(
                SettingsFragment.CHANNEL_SCANNING,
                getString(R.string.notif_channel_scanning_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_scanning_desc)
                setShowBadge(false)
            }

            // Channel 2 – scan result alerts (high importance = sound + heads-up popup)
            val resultChannel = NotificationChannel(
                SettingsFragment.CHANNEL_RESULT,
                getString(R.string.notif_channel_result_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notif_channel_result_desc)
                setShowBadge(true)
            }

            nm.deleteNotificationChannel("kuwago_result") // Clean up old channel
            nm.createNotificationChannel(scanningChannel)
            nm.createNotificationChannel(resultChannel)
        }
    }



    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Handled silently
    }

    fun isNotificationServiceEnabled(): Boolean {
        val pkgName = packageName
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (flat != null && flat.isNotEmpty()) {
            val names = flat.split(":")
            for (name in names) {
                val cn = android.content.ComponentName.unflattenFromString(name)
                if (cn != null && cn.packageName == pkgName) {
                    return true
                }
            }
        }
        return false
    }

    private fun checkNotificationPermission() {
        if (!isNotificationServiceEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("Notification Access Required")
                .setMessage("Kuwago needs Notification Access to intercept and block scam SMS messages before you see them. Please enable it in the next screen.")
                .setPositiveButton("Enable in Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                .setCancelable(false)
                .show()
        }
    }



    private fun initViews() {
        navHome = findViewById(R.id.nav_home)
        navHistory = findViewById(R.id.nav_history)
        navScan = findViewById(R.id.nav_scan)
        navBlacklist = findViewById(R.id.nav_blacklist)
        navSettings = findViewById(R.id.nav_settings)

        homeIcon = findViewById(R.id.nav_home_icon)
        historyIcon = findViewById(R.id.nav_history_icon)
        scanIcon = findViewById(R.id.nav_scan_icon)
        blacklistIcon = findViewById(R.id.nav_blacklist_icon)
        settingsIcon = findViewById(R.id.nav_settings_icon)

        homeText = findViewById(R.id.nav_home_text)
        historyText = findViewById(R.id.nav_history_text)
        blacklistText = findViewById(R.id.nav_blacklist_text)
        settingsText = findViewById(R.id.nav_settings_text)
    }

    private fun setupNavigation() {
        navHome.setOnClickListener {
            if (currentTab != "home") {
                switchFragment(HomeFragment(), "home")
                updateNavUI("home")
            }
        }

        navHistory.setOnClickListener {
            if (currentTab != "history") {
                switchFragment(HistoryFragment(), "history")
                updateNavUI("history")
            }
        }

        navScan.setOnClickListener {
            if (currentTab != "scan") {
                switchFragment(ScanFragment(), "scan")
                updateNavUI("scan")
            }
        }

        navBlacklist.setOnClickListener {
            if (currentTab != "blacklist") {
                switchFragment(BlacklistFragment(), "blacklist")
                updateNavUI("blacklist")
            }
        }

        navSettings.setOnClickListener {
            if (currentTab != "settings") {
                switchFragment(SettingsFragment(), "settings")
                updateNavUI("settings")
            }
        }
    }

    private fun switchFragment(fragment: Fragment, tag: String) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment, tag)
            .commit()
    }

    /** Called by ScanFragment after a scan completes to jump to the History tab. */
    fun navigateToHistory() {
        switchFragment(HistoryFragment(), "history")
        updateNavUI("history")
    }

    private fun updateNavUI(selectedTab: String) {
        currentTab = selectedTab

        val unselected = ContextCompat.getColor(this, R.color.nav_icon_unselected)
        val selected = ContextCompat.getColor(this, R.color.nav_icon_selected)

        // Reset all to unselected (gray)
        listOf(homeIcon, historyIcon, blacklistIcon, settingsIcon)
            .forEach { it.setColorFilter(unselected) }
        listOf(homeText, historyText, blacklistText, settingsText)
            .forEach { it.setTextColor(unselected) }

        // Highlight selected tab (white)
        when (selectedTab) {
            "home" -> {
                homeIcon.setColorFilter(selected)
                homeText.setTextColor(selected)
            }
            "history" -> {
                historyIcon.setColorFilter(selected)
                historyText.setTextColor(selected)
            }
            "scan" -> {
                scanIcon.setColorFilter(selected)
            }
            "blacklist" -> {
                blacklistIcon.setColorFilter(selected)
                blacklistText.setTextColor(selected)
            }
            "settings" -> {
                settingsIcon.setColorFilter(selected)
                settingsText.setTextColor(selected)
            }
        }
    }
}
