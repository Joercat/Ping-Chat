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
        pendingSync.getOrPut(userId) { mutableListOf() }.add(message)
    }
    
    suspend fun syncWithPeer(userId: String) {
        _syncStatus.value = SyncStatus.Syncing(userId)
        
        try {
            val pendingMessages = pendingSync[userId] ?: emptyList()
            
            for (message in pendingMessages) {
                val sent = p2pManager.sendMessage(userId, message)
                if (sent) {
                    messageRepository.updateMessageStatus(message.id, MessageStatus.SENT)
                }
            }
            
            pendingSync.remove(userId)
            _syncStatus.value = SyncStatus.Completed(userId)
            
        } catch (e: Exception) {
            _syncStatus.value = SyncStatus.Failed(userId, e.message ?: "Unknown error")
        }
    }
    
    suspend fun retryFailedMessages() {
        val failedMessages = messageRepository.getFailedMessages()
        
        for (message in failedMessages) {
            val sent = p2pManager.sendMessage(message.receiverId, message)
            if (sent) {
                messageRepository.updateMessageStatus(message.id, MessageStatus.SENT)
            }
        }
    }
    
    fun startAutoSync() {
        scope.launch {
            while (isActive) {
                delay(15000) // Every 15 seconds
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
