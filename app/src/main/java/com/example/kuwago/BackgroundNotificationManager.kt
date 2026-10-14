package com.example.kuwago

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.text.HtmlCompat

object BackgroundNotificationManager {

    const val NOTIF_ID_BACKGROUND_SCANNING = 8888
    private const val GROUP_BACKGROUND_SERVICE = "kuwago_background_service_group"
    private const val TAG = "BgNotifManager"

    @Volatile
    private var isAppInForeground = false

    /**
     * Updates the foreground state of the app.
     * When moving to background, posts the ongoing scanning notification if allowed.
     * When moving to foreground, dismisses the background notification bar.
     */
    fun setAppInForeground(context: Context, inForeground: Boolean) {
        isAppInForeground = inForeground
        if (inForeground) {
            dismiss(context)
        } else {
            showIfAllowed(context)
        }
    }

    fun isAppInForeground(): Boolean = isAppInForeground

    /**
     * Shows the ongoing background scanning notification if onboarding is complete,
     * system protection is turned ON (or Local Layer), and the app is not currently in the foreground.
     */
    fun showIfAllowed(context: Context) {
        val prefs = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val hasCompletedOnboarding = prefs.getBoolean("hasCompletedOnboarding", false)
        val systemStatus = prefs.getString(SettingsFragment.KEY_SYSTEM_STATUS, SettingsFragment.STATUS_ON)

        if (!hasCompletedOnboarding || systemStatus == SettingsFragment.STATUS_OFF) {
            dismiss(context)
            return
        }

        if (isAppInForeground) {
            return
        }

        show(context)
    }

    /**
     * Immediately builds and posts the background status notification bar.
     */
    fun show(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            // Ensure notification channel exists
            NotificationHelper.createAllNotificationChannels(context)

            val prefs = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
            val systemStatus = prefs.getString(SettingsFragment.KEY_SYSTEM_STATUS, SettingsFragment.STATUS_ON)

            val titleText = if (systemStatus == SettingsFragment.STATUS_OFF) {
                "Kuwago is inactive"
            } else {
                "Kuwago is active"
            }

            val dotColor = when (systemStatus) {
                SettingsFragment.STATUS_DATA_SAVER -> "#F07048"
                SettingsFragment.STATUS_OFF -> "#FF4D55"
                else -> "#26CE6B"
            }

            val statusLabel = when (systemStatus) {
                SettingsFragment.STATUS_DATA_SAVER -> "Status: Local Layer"
                SettingsFragment.STATUS_OFF -> "Status: Off"
                else -> "Status: On"
            }

            val statusHtml = "<font color='$dotColor'>●</font> $statusLabel"
            val statusFormatted = HtmlCompat.fromHtml(
                statusHtml,
                HtmlCompat.FROM_HTML_MODE_LEGACY
            )

            val bodyText = if (systemStatus == SettingsFragment.STATUS_DATA_SAVER) {
                "Local layer message protection is running in the background. Incoming messages are checked using on-device ML."
            } else {
                context.getString(R.string.notif_background_scanning_body)
            }

            val tapIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                context,
                NOTIF_ID_BACKGROUND_SCANNING,
                tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Collapsed (minimized) RemoteViews - clean single line layout, no background, no extra logo
            val collapsedViews = RemoteViews(context.packageName, R.layout.notification_scanning_collapsed).apply {
                setTextViewText(R.id.tv_notif_title_collapsed, titleText)
                setTextViewText(R.id.tv_notif_status_collapsed, statusFormatted)
            }

            // Expanded RemoteViews - clean layout, no background, no extra logo
            val expandedViews = RemoteViews(context.packageName, R.layout.notification_scanning_expanded).apply {
                setTextViewText(R.id.tv_notif_title_expanded, titleText)
                setTextViewText(R.id.tv_notif_status_expanded, statusFormatted)
                setTextViewText(R.id.tv_notif_body_expanded, bodyText)
            }

            val builder = NotificationCompat.Builder(context, SettingsFragment.CHANNEL_SCANNING)
                .setSmallIcon(R.drawable.ic_kuwago_notif)
                .setContentTitle(titleText)
                .setContentText(statusFormatted)
                .setCustomContentView(collapsedViews)
                .setCustomBigContentView(expandedViews)
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setOngoing(true)
                .setAutoCancel(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setGroup(GROUP_BACKGROUND_SERVICE)
                .setContentIntent(pi)

            nm.notify(NOTIF_ID_BACKGROUND_SCANNING, builder.build())
            Log.d(TAG, "Ongoing background status notification posted ($statusLabel).")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post ongoing background status notification", e)
        }
    }

    /**
     * Dismisses the background notification bar.
     */
    fun dismiss(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(NOTIF_ID_BACKGROUND_SCANNING)
            Log.d(TAG, "Ongoing background status notification dismissed.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dismiss background status notification", e)
        }
    }
}
