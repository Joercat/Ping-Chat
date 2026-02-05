package com.ping.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class PingApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // Messages channel
            val messagesChannel = NotificationChannel(
                CHANNEL_MESSAGES,
                "Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "New message notifications"
                enableVibration(true)
                enableLights(true)
            }

            // Service channel
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                "Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Ping connected"
            }

            // File transfer channel
            val transferChannel = NotificationChannel(
                CHANNEL_TRANSFER,
                "File Transfers",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "File transfer progress"
            }

            notificationManager.createNotificationChannels(
                listOf(messagesChannel, serviceChannel, transferChannel)
            )
        }
    }

    companion object {
        const val CHANNEL_MESSAGES = "ping_messages"
        const val CHANNEL_SERVICE = "ping_service"
        const val CHANNEL_TRANSFER = "ping_transfer"
    }
}
