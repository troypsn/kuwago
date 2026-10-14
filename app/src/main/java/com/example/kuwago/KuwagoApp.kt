package com.example.kuwago

import android.app.Activity
import android.app.Application
import android.os.Bundle

class KuwagoApp : Application() {

    private var startedActivityCount = 0

    override fun onCreate() {
        super.onCreate()
        DetectionRepository.loadIfNeeded(this)
        NotificationHelper.createAllNotificationChannels(this)

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivityCount++
                if (startedActivityCount == 1) {
                    BackgroundNotificationManager.setAppInForeground(this@KuwagoApp, true)
                }
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivityCount--
                if (startedActivityCount <= 0) {
                    startedActivityCount = 0
                    BackgroundNotificationManager.setAppInForeground(this@KuwagoApp, false)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}

