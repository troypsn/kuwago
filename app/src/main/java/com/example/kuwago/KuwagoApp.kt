package com.example.kuwago

import android.app.Application

class KuwagoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createAllNotificationChannels(this)
    }
}
