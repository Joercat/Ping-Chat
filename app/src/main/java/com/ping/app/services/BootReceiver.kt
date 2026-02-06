// services/BootReceiver.kt
package com.ping.app.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.ping.app.core.encryption.SecureStorage
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Starts services when device boots.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    
    @Inject
    lateinit var secureStorage: SecureStorage
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_QUICKBOOT_POWERON ||
            intent.action == "android.intent.action.LOCKED_BOOT_COMPLETED"
        ) {
            // Only start services if user is logged in
            try {
                if (secureStorage.getUserId() != null) {
                    startServices(context)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    private fun startServices(context: Context) {
        try {
            val messagingIntent = Intent(context, PingMessagingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(messagingIntent)
            } else {
                context.startService(messagingIntent)
            }
            
            val presenceIntent = Intent(context, PresenceService::class.java)
            context.startService(presenceIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
