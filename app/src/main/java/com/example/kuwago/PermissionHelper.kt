package com.example.kuwago

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

object PermissionHelper {
    fun hasSmsPermissions(context: Context) = listOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)
        .all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    fun hasNotificationPermission(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun isNotificationServiceEnabled(context: Context): Boolean = Settings.Secure
        .getString(context.contentResolver, "enabled_notification_listeners")
        ?.split(":")?.any { ComponentName.unflattenFromString(it)?.packageName == context.packageName } == true

    fun isAllRequiredProtectionGranted(context: Context) = hasSmsPermissions(context) &&
        hasNotificationPermission(context) && isNotificationServiceEnabled(context)

    fun openNotificationListenerSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(context, SmsNotificationListener::class.java).flattenToString())
        else Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        context.startActivity(intent)
    }

    fun openAppSettings(context: Context) = context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)))
}
