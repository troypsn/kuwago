package com.example.kuwago

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object NotificationHelper {

    fun createAllNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return

            // Channel 1 – ongoing scanning progress
            val scanningChannel = NotificationChannel(
                SettingsFragment.CHANNEL_SCANNING,
                context.getString(R.string.notif_channel_scanning_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notif_channel_scanning_desc)
                setShowBadge(false)
            }

            // Channel 2 – scan result alerts
            val resultChannel = NotificationChannel(
                SettingsFragment.CHANNEL_RESULT,
                context.getString(R.string.notif_channel_result_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notif_channel_result_desc)
                setShowBadge(true)
            }

            try { nm.deleteNotificationChannel("kuwago_result") } catch (_: Exception) {}
            nm.createNotificationChannel(scanningChannel)
            nm.createNotificationChannel(resultChannel)

            // Channel 3 – VPN block alert
            val vpnBlockChannel = NotificationChannel(
                SettingsFragment.CHANNEL_VPN_BLOCK,
                "URL Shield — Blocked Sites",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when Kuwago URL Shield blocks a phishing site."
                setShowBadge(true)
            }

            // Channel 4 – VPN ongoing status
            val vpnOngoingChannel = NotificationChannel(
                SettingsFragment.CHANNEL_VPN_ONGOING,
                "URL Shield — Active Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows while Kuwago URL Shield is active."
                setShowBadge(false)
            }

            nm.createNotificationChannel(vpnBlockChannel)
            nm.createNotificationChannel(vpnOngoingChannel)
        }
    }
}
