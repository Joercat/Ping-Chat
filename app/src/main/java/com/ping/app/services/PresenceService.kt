// services/PresenceService.kt
package com.ping.app.services

import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import com.ping.app.core.network.ConnectionManager
import com.ping.app.core.network.P2PManager
import com.ping.app.data.models.PresenceInfo
import com.ping.app.data.models.PresenceStatus
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class PresenceService : Service() {
    
    @Inject lateinit var p2pManager: P2PManager
    @Inject lateinit var connectionManager: ConnectionManager
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentPresence = PresenceStatus.ONLINE
    
    override fun onCreate() {
        super.onCreate()
        startPresenceMonitoring()
    }
    
    private fun startPresenceMonitoring() {
        serviceScope.launch {
            while (isActive) {
                val newPresence = detectPresenceStatus()
                
                if (newPresence != currentPresence) {
                    currentPresence = newPresence
                    broadcastPresence(newPresence)
                }
                
                delay(5000) // Check every 5 seconds
            }
        }
    }
    
    private fun detectPresenceStatus(): PresenceStatus {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        
        // Check if screen is on
        if (!powerManager.isInteractive) {
            return PresenceStatus.OFFLINE
        }
        
        // Check if user is in our app
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        usageStatsManager?.let { manager ->
            val endTime = System.currentTimeMillis()
            val startTime = endTime - 5000
            
            val events = manager.queryEvents(startTime, endTime)
            val event = UsageEvents.Event()
            
            var lastForegroundApp: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    lastForegroundApp = event.packageName
                }
            }
            
            return when (lastForegroundApp) {
                packageName -> PresenceStatus.ONLINE
                null -> PresenceStatus.AWAY
                else -> PresenceStatus.IN_APP
            }
        }
        
        return PresenceStatus.ONLINE
    }
    
    private fun broadcastPresence(status: PresenceStatus) {
        val presence = PresenceInfo(
            userId = connectionManager.getMyUserId(),
            status = status,
            lastSeen = if (status == PresenceStatus.OFFLINE) System.currentTimeMillis() else null,
            isTyping = false,
            typingInConversation = null
        )
        
        p2pManager.broadcastPresence(presence)
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        
        // Broadcast offline status
        broadcastPresence(PresenceStatus.OFFLINE)
    }
}
