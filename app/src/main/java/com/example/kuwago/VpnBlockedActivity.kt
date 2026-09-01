package com.example.kuwago

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Full-screen warning activity shown when the Kuwago VPN blocks a network
 * connection to a hostname that was previously classified as SMISHING.
 *
 * Launched via a high-priority system notification from [KuwagoVpnService].
 * The user is told what was blocked, why, and is given the option to:
 *   - Dismiss (stay protected).
 *   - View the scan history entry in the app.
 *   - Disable the VPN shield for this session (with a warning).
 */
class VpnBlockedActivity : AppCompatActivity() {

    companion object {
        /** Intent extra key — the blocked hostname (e.g. "phishing-site.com"). */
        const val EXTRA_HOSTNAME = "extra_vpn_blocked_hostname"
        const val EXTRA_RISK_LEVEL = "extra_vpn_blocked_risk_level"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vpn_blocked)

        val hostname = intent.getStringExtra(EXTRA_HOSTNAME) ?: "unknown site"
        val riskLevelStr = intent.getStringExtra(EXTRA_RISK_LEVEL) ?: "SMISHING"

        val tvHostname   = findViewById<TextView>(R.id.vpn_blocked_hostname)
        val tvDetail     = findViewById<TextView>(R.id.vpn_blocked_detail)
        val btnDismiss   = findViewById<Button>(R.id.btn_vpn_blocked_dismiss)
        val btnViewHistory = findViewById<TextView>(R.id.btn_vpn_blocked_view_history)
        val btnDisableVpn  = findViewById<TextView>(R.id.btn_vpn_blocked_disable_shield)

        tvHostname.text = hostname
        val label = if (riskLevelStr.equals("SUSPICIOUS", ignoreCase = true)) "suspicious" else "phishing"
        tvDetail.text = "This domain ($hostname) was previously identified as a $label destination by Kuwago's scanner. The connection was blocked to protect your device and personal information."

        btnDismiss.setOnClickListener {
            finish()
        }

        btnViewHistory.setOnClickListener {
            // Open the app on the History tab so the user can see the scan result
            val mainIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("NAV_TAB", "history")
            }
            startActivity(mainIntent)
            finish()
        }

        btnDisableVpn.setOnClickListener {
            // Send the stop command to the VPN service; the user can re-enable
            // from Settings → URL Shield at any time.
            val stopIntent = Intent(this, KuwagoVpnService::class.java).apply {
                action = KuwagoVpnService.ACTION_STOP
            }
            startService(stopIntent)
            finish()
        }
    }
}
