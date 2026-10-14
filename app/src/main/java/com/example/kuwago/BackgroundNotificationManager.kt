package com.example.kuwago

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat

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
     * system protection is turned ON, and the app is not currently in the foreground.
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
     * Immediately builds and posts the background scanning notification bar.
     */
    fun show(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            // Ensure notification channel exists
            NotificationHelper.createAllNotificationChannels(context)

            val tapIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                context,
                NOTIF_ID_BACKGROUND_SCANNING,
                tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Collapsed (minimized) RemoteViews - compact single bar with Kuwago logo
            val collapsedViews = RemoteViews(context.packageName, R.layout.notification_scanning_collapsed).apply {
                setImageViewResource(R.id.iv_notif_logo_collapsed, R.drawable.kuwago_notif_logo)
                setTextViewText(R.id.tv_notif_title_collapsed, context.getString(R.string.notif_background_scanning_title))
                setTextViewText(R.id.tv_notif_subtext_collapsed, context.getString(R.string.notif_background_scanning_subtext))
                setTextViewText(R.id.tv_notif_badge_collapsed, context.getString(R.string.notif_background_scanning_active))
            }

            // Expanded RemoteViews - detailed card with Kuwago logo, description, and status
            val expandedViews = RemoteViews(context.packageName, R.layout.notification_scanning_expanded).apply {
                setImageViewResource(R.id.iv_notif_logo_expanded, R.drawable.kuwago_notif_logo)
                setTextViewText(R.id.tv_notif_title_expanded, context.getString(R.string.notif_background_scanning_title))
                setTextViewText(R.id.tv_notif_status_expanded, context.getString(R.string.notif_background_scanning_active))
                setTextViewText(R.id.tv_notif_body_expanded, context.getString(R.string.notif_background_scanning_body))
                setTextViewText(R.id.tv_notif_action_expanded, context.getString(R.string.notif_background_scanning_action))
            }

            val kuwagoBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.kuwagoicon)

            val builder = NotificationCompat.Builder(context, SettingsFragment.CHANNEL_SCANNING)
                .setSmallIcon(R.drawable.ic_kuwago_notif)
                .setLargeIcon(kuwagoBitmap)
                .setContentTitle(context.getString(R.string.notif_background_scanning_title))
                .setContentText(context.getString(R.string.notif_background_scanning_subtext))
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
            Log.d(TAG, "Ongoing background scanning notification posted.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post ongoing background scanning notification", e)
        }
    }

    /**
     * Dismisses the background scanning notification bar.
     */
    fun dismiss(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(NOTIF_ID_BACKGROUND_SCANNING)
            Log.d(TAG, "Ongoing background scanning notification dismissed.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dismiss background scanning notification", e)
        }
    }
}
