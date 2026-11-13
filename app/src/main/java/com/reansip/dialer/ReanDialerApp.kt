package com.reansip.dialer

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.appcompat.app.AppCompatDelegate

class ReanDialerApp : Application() {

    companion object {
        const val CHANNEL_SERVICE = "rean_service"
        const val CHANNEL_CALLS = "rean_calls"
        const val NOTIF_ID_SERVICE = 1001
        const val NOTIF_ID_INCOMING = 1002
    }

    override fun onCreate() {
        super.onCreate()
        // The app ships with a designed dark theme — enforce it everywhere.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                getString(R.string.channel_service_name),
                NotificationManager.IMPORTANCE_LOW
            )
        )

        val calls = NotificationChannel(
            CHANNEL_CALLS,
            getString(R.string.channel_calls_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.channel_calls_desc)
            setSound(null, null) // ringtone is played manually so we can stop it precisely
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(calls)
    }
}
