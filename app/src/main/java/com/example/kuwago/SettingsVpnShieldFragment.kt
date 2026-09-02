package com.example.kuwago

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Settings sub-page for the Kuwago URL Shield (VPN-based enforcement).
 *
 * Follows the exact same pattern as [SettingsRealtimeScanFragment] and
 * [SettingsDeepAnalysisFragment]:
 *   - Back arrow returns to [SettingsFragment].
 *   - A descriptive body explains what the feature does.
 *   - A toggle switch enables or disables the feature.
 *
 * VPN permission request flow:
 *   1. User flips the switch to ON.
 *   2. Fragment calls [VpnService.prepare()].
 *      - If null → permission already granted; start service immediately.
 *      - If non-null Intent → launch the system VPN consent dialog.
 *   3. On RESULT_OK → start [KuwagoVpnService].
 *   4. On RESULT_CANCELED → revert switch to OFF.
 */
class SettingsVpnShieldFragment : Fragment() {

    private lateinit var switchVpnShield: SwitchCompat
    private lateinit var tvStatus: TextView

    private val TAG = "VpnShieldSettings"
    private var isProgrammaticChange = false

    // ActivityResultLauncher for the Android VPN permission dialog
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Log.i(TAG, "VPN permission granted by user")
            startVpnService()
            setSwitchCheckedProgrammatically(true)
        } else {
            Log.i(TAG, "VPN permission denied by user")
            setSwitchCheckedProgrammatically(false)
            updateStatusText(false)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_settings_vpn_shield, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        switchVpnShield = view.findViewById(R.id.switch_vpn_shield)
        tvStatus        = view.findViewById(R.id.tv_vpn_shield_status)

        // Back navigation
        view.findViewById<View>(R.id.btn_back_vpn_shield)?.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        // Reflect actual running state
        val isActive = isVpnCurrentlyActive()
        setSwitchCheckedProgrammatically(isActive)
        updateStatusText(isActive)

        switchVpnShield.setOnCheckedChangeListener { _, isChecked ->
            if (isProgrammaticChange) return@setOnCheckedChangeListener
            if (isChecked) {
                requestVpnPermission()
            } else {
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setMessage("Are you sure you want to turn off \"URL Shield\"?")
                    .setPositiveButton("Turn Off") { _, _ ->
                        stopVpnService()
                        updateStatusText(false)
                    }
                    .setNegativeButton("Cancel") { dialog, _ ->
                        setSwitchCheckedProgrammatically(true)
                        dialog.dismiss()
                    }
                    .setOnCancelListener {
                        setSwitchCheckedProgrammatically(true)
                    }
                    .show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-sync switch state in case the VPN was killed externally
        val active = isVpnCurrentlyActive()
        setSwitchCheckedProgrammatically(active)
        updateStatusText(active)
    }

    private fun setSwitchCheckedProgrammatically(checked: Boolean) {
        isProgrammaticChange = true
        switchVpnShield.isChecked = checked
        isProgrammaticChange = false
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun requestVpnPermission() {
        val permissionIntent = VpnService.prepare(requireContext())
        if (permissionIntent != null) {
            // System needs to show a consent dialog first
            vpnPermissionLauncher.launch(permissionIntent)
        } else {
            // Permission already granted; start the service immediately
            startVpnService()
        }
    }

    private fun startVpnService() {
        val ctx = requireContext().applicationContext
        val intent = Intent(ctx, KuwagoVpnService::class.java).apply {
            action = KuwagoVpnService.ACTION_START
        }
        ctx.startService(intent)
        updateStatusText(true)

        // Asynchronously sync URL analysis database from the last 3 months
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            com.example.kuwago.db.SmsLocalRepository.syncUrlReputationsFromBackend(ctx)
        }
    }

    private fun stopVpnService() {
        val ctx = requireContext()
        val intent = Intent(ctx, KuwagoVpnService::class.java).apply {
            action = KuwagoVpnService.ACTION_STOP
        }
        ctx.startService(intent)
    }

    private fun isVpnCurrentlyActive(): Boolean {
        return requireContext()
            .getSharedPreferences(KuwagoVpnService.PREFS_VPN, Context.MODE_PRIVATE)
            .getBoolean(KuwagoVpnService.KEY_VPN_ACTIVE, false)
    }

    private fun updateStatusText(active: Boolean) {
        tvStatus.text = if (active) {
            getString(R.string.vpn_shield_status_active)
        } else {
            getString(R.string.vpn_shield_status_inactive)
        }
        tvStatus.setTextColor(
            if (active) 0xFF4CAF50.toInt() else 0xFF808080.toInt()
        )
    }
}

