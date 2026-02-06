// core/sync/SyncManager.kt
package com.ping.app.core.sync

import com.ping.app.core.network.P2PManager
import com.ping.app.data.models.Message
import com.ping.app.data.models.MessageStatus
import com.ping.app.data.repository.MessageRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncManager @Inject constructor(
    private val messageRepository: MessageRepository,
    private val p2pManager: P2PManager,
    private val conflictResolver: ConflictResolver
) {
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()
    
    private val pendingSync = mutableMapOf<String, MutableList<Message>>()
    
    fun queueForSync(userId: String, message: Message) {
        synchronized(pendingSync) {
            pendingSync.getOrPut(userId) { mutableListOf() }.add(message)
        }
    }
    
    suspend fun syncWithPeer(userId: String) {
        _syncStatus.value = SyncStatus.Syncing(userId)
        
        try {
            val pendingMessages = synchronized(pendingSync) {
                pendingSync[userId]?.toList() ?: emptyList()
            }
            
            for (message in pendingMessages) {
                val sent = p2pManager.sendMessage(userId, message)
                if (sent) {
                    messageRepository.updateMessageStatus(message.id, MessageStatus.SENT)
                }
            }
            
            synchronized(pendingSync) {
                pendingSync.remove(userId)
            }
            _syncStatus.value = SyncStatus.Completed(userId)
            
        } catch (e: Exception) {
            _syncStatus.value = SyncStatus.Failed(userId, e.message ?: "Unknown error")
        }
    }
    
    suspend fun retryFailedMessages() {
        try {
            val failedMessages = messageRepository.getFailedMessages()
            
            for (message in failedMessages) {
                if (p2pManager.isConnectedTo(message.receiverId)) {
                    val sent = p2pManager.sendMessage(message.receiverId, message)
                    if (sent) {
                        messageRepository.updateMessageStatus(message.id, MessageStatus.SENT)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    fun startAutoSync() {
        scope.launch {
            while (isActive) {
                delay(30000) // Every 30 seconds
                retryFailedMessages()
            }
        }
    }
    
    fun cleanup() {
        scope.cancel()
    }
}

sealed class SyncStatus {
    object Idle : SyncStatus()
    data class Syncing(val userId: String) : SyncStatus()
    data class Completed(val userId: String) : SyncStatus()
    data class Failed(val userId: String, val error: String) : SyncStatus()
}
