package com.example.mykotlinapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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

        // Set default fragment
        if (savedInstanceState == null) {
            switchFragment(HomeFragment(), "home")
            updateNavUI("home")
        }
    }

    private fun checkSmsPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECEIVE_SMS)) {
                showNotificationAccessDialog()
            } else {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS), SMS_PERMISSION_CODE)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == SMS_PERMISSION_CODE) {
            if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                showNotificationAccessDialog()
            }
        }
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

    private fun showNotificationAccessDialog() {
        if (!isNotificationServiceEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("Enhanced Protection")
                .setMessage("SMS permissions were denied. To continue protecting you from smishing, please enable Notification Access so we can scan messages as they arrive.")
                .setPositiveButton("Enable in Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                .setNegativeButton("Maybe Later", null)
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
