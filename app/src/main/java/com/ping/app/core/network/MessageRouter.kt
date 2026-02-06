// core/network/MessageRouter.kt
package com.ping.app.core.network

import com.ping.app.core.encryption.CryptoManager
import com.ping.app.core.encryption.EncryptedData
import com.ping.app.core.encryption.KeyExchange
import com.ping.app.data.models.*
import com.ping.app.data.repository.MessageRepository
import com.ping.app.data.repository.UserRepository
import com.ping.app.services.NotificationService
import com.ping.app.utils.FileUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageRouter @Inject constructor(
    private val p2pManager: P2PManager,
    private val cryptoManager: CryptoManager,
    private val keyExchange: KeyExchange,
    private val messageRepository: MessageRepository,
    private val userRepository: UserRepository,
    private val fileUtils: FileUtils,
    private val notificationService: NotificationService
) {
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    
    private val _typingUsers = MutableStateFlow<Map<String, TypingState>>(emptyMap())
    val typingUsers: StateFlow<Map<String, TypingState>> = _typingUsers.asStateFlow()
    
    private val _userPresence = MutableStateFlow<Map<String, PresenceInfo>>(emptyMap())
    val userPresence: StateFlow<Map<String, PresenceInfo>> = _userPresence.asStateFlow()
    
    init {
        observeIncomingPackets()
    }
    
    private fun observeIncomingPackets() {
        scope.launch {
            p2pManager.incomingMessages.collect { packet ->
                handlePacket(packet)
            }
        }
    }
    
    private suspend fun handlePacket(packet: P2PPacket) {
        try {
            when (packet) {
                is P2PPacket.EncryptedMessage -> handleEncryptedMessage(packet)
                is P2PPacket.FileTransfer -> handleFileTransfer(packet)
                is P2PPacket.TypingIndicator -> handleTypingIndicator(packet)
                is P2PPacket.PresenceUpdate -> handlePresenceUpdate(packet)
                is P2PPacket.MessageStatusUpdate -> handleMessageStatusUpdate(packet)
                is P2PPacket.ReadReceipt -> handleReadReceipt(packet)
                is P2PPacket.Reaction -> handleReaction(packet)
                is P2PPacket.MessageEdit -> handleMessageEdit(packet)
                is P2PPacket.MessageDelete -> handleMessageDelete(packet)
                is P2PPacket.Handshake -> { /* Handled in P2PManager */ }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private suspend fun handleEncryptedMessage(packet: P2PPacket.EncryptedMessage) {
        val sessionKey = keyExchange.getSessionKey(packet.senderId) ?: return
        
        try {
            val encryptedData = EncryptedData.fromByteArray(packet.encryptedData)
            val decryptedBytes = cryptoManager.decryptMessage(encryptedData, sessionKey)
            val transportMessage = json.decodeFromString<TransportMessage>(String(decryptedBytes))
            
            val message = Message(
                id = transportMessage.id,
                conversationId = transportMessage.conversationId,
                senderId = transportMessage.senderId,
                receiverId = transportMessage.receiverId,
                content = transportMessage.content,
                type = MessageType.valueOf(transportMessage.type),
                status = MessageStatus.DELIVERED,
                replyToId = transportMessage.replyToId,
                createdAt = transportMessage.createdAt,
                deliveredAt = System.currentTimeMillis()
            )
            
            // Save to local database
            messageRepository.insertMessage(message)
            
            // Send delivery confirmation
            p2pManager.sendMessageStatus(packet.senderId, message.id, MessageStatus.DELIVERED)
            
            // Show notification
            notificationService.showMessageNotification(message)
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private suspend fun handleFileTransfer(packet: P2PPacket.FileTransfer) {
        val senderId = getSenderFromMessage(packet.messageId) ?: return
        val sessionKey = keyExchange.getSessionKey(senderId) ?: return
        
        try {
            val encryptedData = EncryptedData.fromByteArray(packet.encryptedData)
            val decryptedData = cryptoManager.decryptFile(encryptedData, sessionKey)
            val calculatedChecksum = cryptoManager.calculateChecksum(decryptedData)
            
            if (calculatedChecksum != packet.checksum) {
                return
            }
            
            val localPath = fileUtils.saveReceivedFile(
                fileName = packet.fileName,
                mimeType = packet.mimeType,
                data = decryptedData
            )
            
            val attachment = Attachment(
                id = packet.attachmentId,
                messageId = packet.messageId,
                fileName = packet.fileName,
                fileSize = packet.fileSize,
                mimeType = packet.mimeType,
                localPath = localPath,
                downloadStatus = DownloadStatus.COMPLETED,
                downloadProgress = 100,
                checksum = packet.checksum
            )
            
            messageRepository.updateAttachment(attachment)
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private suspend fun getSenderFromMessage(messageId: String): String? {
        return messageRepository.getMessageById(messageId)?.senderId
    }
    
    private fun handleTypingIndicator(packet: P2PPacket.TypingIndicator) {
        val typingState = TypingState(
            userId = packet.userId,
            conversationId = packet.conversationId,
            isTyping = packet.isTyping,
            timestamp = System.currentTimeMillis()
        )
        
        _typingUsers.value = if (packet.isTyping) {
            _typingUsers.value + (packet.conversationId to typingState)
        } else {
            _typingUsers.value - packet.conversationId
        }
        
        if (packet.isTyping) {
            scope.launch {
                delay(5000)
                _typingUsers.value = _typingUsers.value.filterValues { 
                    System.currentTimeMillis() - it.timestamp < 5000 
                }
            }
        }
    }
    
    private fun handlePresenceUpdate(packet: P2PPacket.PresenceUpdate) {
        _userPresence.value = _userPresence.value + (packet.presence.userId to packet.presence)
        
        scope.launch {
            userRepository.updatePresence(
                packet.presence.userId,
                packet.presence.status,
                packet.presence.lastSeen
            )
        }
    }
    
    private suspend fun handleMessageStatusUpdate(packet: P2PPacket.MessageStatusUpdate) {
        val status = MessageStatus.valueOf(packet.status)
        messageRepository.updateMessageStatus(packet.messageId, status, packet.timestamp)
    }
    
    private suspend fun handleReadReceipt(packet: P2PPacket.ReadReceipt) {
        messageRepository.markMessagesAsSeen(packet.messageIds, packet.timestamp)
    }
    
    private suspend fun handleReaction(packet: P2PPacket.Reaction) {
        if (packet.isRemoved) {
            messageRepository.removeReaction(packet.messageId, packet.senderId)
        } else {
            messageRepository.addReaction(packet.messageId, packet.senderId, packet.emoji)
        }
    }
    
    private suspend fun handleMessageEdit(packet: P2PPacket.MessageEdit) {
        messageRepository.editMessage(packet.messageId, packet.newContent, packet.timestamp)
    }
    
    private suspend fun handleMessageDelete(packet: P2PPacket.MessageDelete) {
        if (packet.forEveryone) {
            messageRepository.deleteMessageForEveryone(packet.messageId)
        }
    }
    
    // Public methods to send messages
    
    suspend fun sendMessage(message: Message): Result<Message> {
        return try {
            val savedMessage = message.copy(status = MessageStatus.SENDING)
            messageRepository.insertMessage(savedMessage)
            
            val sent = p2pManager.sendMessage(message.receiverId, message)
            
            if (sent) {
                val sentMessage = message.copy(status = MessageStatus.SENT)
                messageRepository.updateMessageStatus(message.id, MessageStatus.SENT)
                Result.success(sentMessage)
            } else {
                messageRepository.updateMessageStatus(message.id, MessageStatus.FAILED)
                Result.failure(Exception("Failed to send message"))
            }
        } catch (e: Exception) {
            messageRepository.updateMessageStatus(message.id, MessageStatus.FAILED)
            Result.failure(e)
        }
    }
    
    suspend fun sendMessageWithAttachment(
        message: Message,
        attachment: Attachment,
        fileData: ByteArray
    ): Result<Message> {
        return try {
            messageRepository.insertMessage(message)
            messageRepository.insertAttachment(attachment)
            
            val messageSent = p2pManager.sendMessage(message.receiverId, message)
            
            if (messageSent) {
                val fileSent = p2pManager.sendFile(message.receiverId, attachment, fileData)
                
                if (fileSent) {
                    messageRepository.updateMessageStatus(message.id, MessageStatus.SENT)
                    Result.success(message.copy(status = MessageStatus.SENT))
                } else {
                    messageRepository.updateMessageStatus(message.id, MessageStatus.FAILED)
                    Result.failure(Exception("Failed to send file"))
                }
            } else {
                messageRepository.updateMessageStatus(message.id, MessageStatus.FAILED)
                Result.failure(Exception("Failed to send message"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    fun sendTypingIndicator(conversationId: String, receiverId: String, isTyping: Boolean) {
        scope.launch {
            p2pManager.sendTypingIndicator(receiverId, conversationId, isTyping)
        }
    }
    
    /**
     * FIXED: Complete implementation for sending reactions
     */
    suspend fun sendReaction(messageId: String, receiverId: String, userId: String, emoji: String) {
        // Save locally
        messageRepository.addReaction(messageId, userId, emoji)
        
        // Send to peer
        scope.launch {
            try {
                val packet = P2PPacket.Reaction(
                    messageId = messageId,
                    senderId = userId,
                    emoji = emoji,
                    isRemoved = false
                )
                p2pManager.sendReactionPacket(receiverId, packet)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    /**
     * Remove a reaction
     */
    suspend fun removeReaction(messageId: String, receiverId: String, userId: String) {
        // Remove locally
        messageRepository.removeReaction(messageId, userId)
        
        // Send removal to peer
        scope.launch {
            try {
                val packet = P2PPacket.Reaction(
                    messageId = messageId,
                    senderId = userId,
                    emoji = "",
                    isRemoved = true
                )
                p2pManager.sendReactionPacket(receiverId, packet)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    suspend fun markAsRead(conversationId: String, receiverId: String) {
        val currentUserId = p2pManager.getMyUserId()
        val unreadMessages = messageRepository.getUnreadMessages(conversationId, currentUserId)
        val messageIds = unreadMessages.map { it.id }
        
        if (messageIds.isNotEmpty()) {
            messageRepository.markMessagesAsSeen(messageIds, System.currentTimeMillis())
            p2pManager.sendReadReceipt(receiverId, messageIds)
        }
    }
    
    /**
     * Send message edit to peer
     */
    suspend fun sendMessageEdit(messageId: String, receiverId: String, newContent: String) {
        val timestamp = System.currentTimeMillis()
        
        // Update locally
        messageRepository.editMessage(messageId, newContent, timestamp)
        
        // Send to peer
        scope.launch {
            try {
                val packet = P2PPacket.MessageEdit(
                    messageId = messageId,
                    newContent = newContent,
                    timestamp = timestamp
                )
                p2pManager.sendEditPacket(receiverId, packet)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    /**
     * Send message deletion to peer
     */
    suspend fun sendMessageDelete(messageId: String, receiverId: String, forEveryone: Boolean) {
        // Delete locally
        if (forEveryone) {
            messageRepository.deleteMessageForEveryone(messageId)
        } else {
            messageRepository.deleteMessage(messageId)
        }
        
        // Send to peer if deleting for everyone
        if (forEveryone) {
            scope.launch {
                try {
                    val packet = P2PPacket.MessageDelete(
                        messageId = messageId,
                        forEveryone = true
                    )
                    p2pManager.sendDeletePacket(receiverId, packet)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
    
    fun cleanup() {
        scope.cancel()
    }
}

data class TypingState(
    val userId: String,
    val conversationId: String,
    val isTyping: Boolean,
    val timestamp: Long
)
