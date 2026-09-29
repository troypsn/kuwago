package com.example.kuwago

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat

object CustomDialogHelper {

    fun isNetworkAvailable(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }

    fun showNoInternetDialog(
        context: Context,
        customMessage: String? = null,
        onDismiss: (() -> Unit)? = null
    ): Dialog {
        val message = customMessage
            ?: "No Wi-Fi or cellular data connection is available. Please check your internet connection and try again."
        return showInfoDialog(
            context = context,
            title = "No Connection",
            message = message,
            iconRes = R.drawable.ic_warning_triangle,
            iconTint = Color.parseColor("#F07048"),
            buttonText = "OK",
            buttonBg = R.drawable.bg_dark_button,
            onDismiss = onDismiss
        )
    }

    fun showInfoDialog(
        context: Context,
        title: String,
        message: String,
        @DrawableRes iconRes: Int = R.drawable.ic_warning_triangle,
        iconTint: Int? = null,
        buttonText: String = "OK",
        @DrawableRes buttonBg: Int = R.drawable.bg_dark_button,
        onOk: (() -> Unit)? = null,
        onDismiss: (() -> Unit)? = null,
        cancelable: Boolean = true
    ): Dialog {
        return showDialog(
            context = context,
            title = title,
            message = message,
            iconRes = iconRes,
            iconTint = iconTint,
            primaryButtonText = buttonText,
            primaryButtonBg = buttonBg,
            onPrimaryClick = onOk,
            secondaryButtonText = null,
            onDismiss = onDismiss,
            cancelable = cancelable
        )
    }

    fun showConfirmDialog(
        context: Context,
        title: String,
        message: String,
        @DrawableRes iconRes: Int = R.drawable.ic_warning_triangle,
        iconTint: Int? = null,
        confirmText: String = "Confirm",
        @DrawableRes confirmBg: Int = R.drawable.bg_red_button,
        cancelText: String = "Cancel",
        @DrawableRes cancelBg: Int = R.drawable.bg_dark_button,
        onConfirm: (() -> Unit)? = null,
        onCancel: (() -> Unit)? = null,
        onDismiss: (() -> Unit)? = null,
        cancelable: Boolean = true
    ): Dialog {
        return showDialog(
            context = context,
            title = title,
            message = message,
            iconRes = iconRes,
            iconTint = iconTint,
            primaryButtonText = confirmText,
            primaryButtonBg = confirmBg,
            onPrimaryClick = onConfirm,
            secondaryButtonText = cancelText,
            secondaryButtonBg = cancelBg,
            onSecondaryClick = onCancel,
            onDismiss = onDismiss,
            cancelable = cancelable
        )
    }

    fun showDialog(
        context: Context,
        title: String,
        message: String,
        @DrawableRes iconRes: Int = R.drawable.ic_warning_triangle,
        iconTint: Int? = null,
        primaryButtonText: String? = "OK",
        @DrawableRes primaryButtonBg: Int = R.drawable.bg_dark_button,
        primaryButtonTextColor: Int = Color.WHITE,
        onPrimaryClick: (() -> Unit)? = null,
        secondaryButtonText: String? = null,
        @DrawableRes secondaryButtonBg: Int = R.drawable.bg_dark_button,
        secondaryButtonTextColor: Int = Color.WHITE,
        onSecondaryClick: (() -> Unit)? = null,
        onDismiss: (() -> Unit)? = null,
        cancelable: Boolean = true
    ): Dialog {
        val dialog = Dialog(context)
        dialog.setContentView(R.layout.dialog_custom_modal)
        dialog.setCancelable(cancelable)

        dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val displayMetrics = context.resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val horizontalMarginPx = (24 * displayMetrics.density).toInt()
            val maxDialogWidthPx = (360 * displayMetrics.density).toInt()
            val targetWidth = (screenWidth - (horizontalMarginPx * 2)).coerceAtMost(maxDialogWidthPx)
            window.setLayout(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val tvTitle = dialog.findViewById<TextView>(R.id.dialog_title)
        val btnClose = dialog.findViewById<ImageView>(R.id.btn_close_dialog)
        val ivIcon = dialog.findViewById<ImageView>(R.id.dialog_icon)
        val tvMessage = dialog.findViewById<TextView>(R.id.dialog_message)
        val btnPrimary = dialog.findViewById<Button>(R.id.btn_primary)
        val btnSecondary = dialog.findViewById<Button>(R.id.btn_secondary)

        tvTitle.text = title
        tvMessage.text = message

        ivIcon.setImageResource(iconRes)
        if (iconTint != null) {
            ivIcon.setColorFilter(iconTint)
        } else {
            ivIcon.clearColorFilter()
        }

        btnClose.setOnClickListener {
            onSecondaryClick?.invoke()
            dialog.dismiss()
        }

        if (!primaryButtonText.isNullOrBlank()) {
            btnPrimary.visibility = View.VISIBLE
            btnPrimary.text = primaryButtonText
            btnPrimary.setBackgroundResource(primaryButtonBg)
            btnPrimary.setTextColor(primaryButtonTextColor)
            btnPrimary.setOnClickListener {
                onPrimaryClick?.invoke()
                dialog.dismiss()
            }
        } else {
            btnPrimary.visibility = View.GONE
        }

        if (!secondaryButtonText.isNullOrBlank()) {
            btnSecondary.visibility = View.VISIBLE
            btnSecondary.text = secondaryButtonText
            btnSecondary.setBackgroundResource(secondaryButtonBg)
            btnSecondary.setTextColor(secondaryButtonTextColor)
            btnSecondary.setOnClickListener {
                onSecondaryClick?.invoke()
                dialog.dismiss()
            }
        } else {
            btnSecondary.visibility = View.GONE
        }

        if (onDismiss != null) {
            dialog.setOnDismissListener {
                onDismiss.invoke()
            }
        }

        dialog.show()
        return dialog
    }
}
