package com.example.mykotlinapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment

class MainActivity : AppCompatActivity() {

    private val SMS_PERMISSION_CODE = 101

    private lateinit var navHome: View
    private lateinit var navHistory: View
    private lateinit var navConfigure: View

    private lateinit var homeIndicator: View
    private lateinit var historyIndicator: View
    private lateinit var configureIndicator: View

    private lateinit var homeIcon: ImageView
    private lateinit var historyIcon: ImageView
    private lateinit var configureIcon: ImageView

    private lateinit var historyText: TextView
    private lateinit var configureText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        // Handle the splash screen transition.
        installSplashScreen()

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
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
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS), SMS_PERMISSION_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == SMS_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted
            }
        }
    }

    private fun initViews() {
        navHome = findViewById(R.id.nav_home)
        navHistory = findViewById(R.id.nav_history)
        navConfigure = findViewById(R.id.nav_configure)

        homeIndicator = findViewById(R.id.nav_home_indicator)
        historyIndicator = findViewById(R.id.nav_history_indicator)
        configureIndicator = findViewById(R.id.nav_configure_indicator)

        homeIcon = findViewById(R.id.nav_home_icon)
        historyIcon = findViewById(R.id.nav_history_icon)
        configureIcon = findViewById(R.id.nav_configure_icon)

        historyText = findViewById(R.id.nav_history_text)
        configureText = findViewById(R.id.nav_configure_text)
    }

    private fun setupNavigation() {
        navHome.setOnClickListener {
            switchFragment(HomeFragment(), "home")
            updateNavUI("home")
        }

        navHistory.setOnClickListener {
            switchFragment(HistoryFragment(), "history")
            updateNavUI("history")
        }

        navConfigure.setOnClickListener {
            switchFragment(ConfigureFragment(), "configure")
            updateNavUI("configure")
        }
    }

    private fun switchFragment(fragment: Fragment, tag: String) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment, tag)
            .commit()
    }

    private fun updateNavUI(selectedTab: String) {
        // Reset all
        homeIndicator.visibility = View.GONE
        historyIndicator.visibility = View.GONE
        configureIndicator.visibility = View.GONE

        homeIcon.setColorFilter(ContextCompat.getColor(this, R.color.nav_icon_unselected))
        historyIcon.setColorFilter(ContextCompat.getColor(this, R.color.nav_icon_unselected))
        configureIcon.setColorFilter(ContextCompat.getColor(this, R.color.nav_icon_unselected))

        historyText.setTextColor(ContextCompat.getColor(this, R.color.nav_icon_unselected))
        configureText.setTextColor(ContextCompat.getColor(this, R.color.nav_icon_unselected))

        // Set selected
        when (selectedTab) {
            "home" -> {
                homeIndicator.visibility = View.VISIBLE
                homeIcon.setColorFilter(ContextCompat.getColor(this, R.color.black))
            }
            "history" -> {
                historyIndicator.visibility = View.VISIBLE
                historyIcon.setColorFilter(ContextCompat.getColor(this, R.color.black))
                historyText.setTextColor(ContextCompat.getColor(this, R.color.black))
            }
            "configure" -> {
                configureIndicator.visibility = View.VISIBLE
                configureIcon.setColorFilter(ContextCompat.getColor(this, R.color.black))
                configureText.setTextColor(ContextCompat.getColor(this, R.color.black))
            }
        }
    }
}
