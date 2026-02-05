// services/PingMessagingService.kt
package com.ping.app.services

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ping.app.PingApplication
import com.ping.app.R
import com.ping.app.core.network.ConnectionManager
import com.ping.app.core.network.MessageRouter
import com.ping.app.core.network.P2PManager
import com.ping.app.core.network.PeerDiscovery
import com.ping.app.core.sync.SyncManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class PingMessagingService : Service() {
    
    @Inject lateinit var p2pManager: P2PManager
    @Inject lateinit var peerDiscovery: PeerDiscovery
    @Inject lateinit var connectionManager: ConnectionManager
    @Inject lateinit var messageRouter: MessageRouter
    @Inject lateinit var syncManager: SyncManager
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverPort: Int = 0
    
    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, createNotification())
        initializeP2P()
    }
    
    private fun initializeP2P() {
        serviceScope.launch {
            try {
                // Start P2P server
                serverPort = p2pManager.startServer()
                
                // Register for peer discovery
                peerDiscovery.registerService(
                    port = serverPort,
                    userId = connectionManager.getMyUserId()
                )
                
                // Start discovering peers
                peerDiscovery.startDiscovery()
                
                // Observe discovered peers
                peerDiscovery.discoveredPeers.collect { peer ->
                    // Try to connect to discovered peer
                    p2pManager.connectToPeer(peer.userId, peer.host, peer.port)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        // Start auto-sync
        syncManager.startAutoSync()
    }
    
    private fun createNotification() = NotificationCompat.Builder(this, PingApplication.CHANNEL_SERVICE)
        .setContentTitle("Ping")
        .setContentText("Connected and ready to receive messages")
        .setSmallIcon(R.drawable.ic_notification)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .build()
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        peerDiscovery.stopDiscovery()
        peerDiscovery.unregisterService()
        p2pManager.shutdown()
        syncManager.cleanup()
        messageRouter.cleanup()
    }
    
    companion object {
        private const val NOTIFICATION_ID = 1001
    }
}
