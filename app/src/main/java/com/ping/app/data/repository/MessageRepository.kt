// data/repository/MessageRepository.kt
package com.ping.app.data.repository

import com.ping.app.data.database.dao.AttachmentDao
import com.ping.app.data.database.dao.MessageDao
import com.ping.app.data.database.entities.AttachmentEntity
import com.ping.app.data.database.entities.MessageEntity
import com.ping.app.data.models.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageRepository @Inject constructor(
    private val messageDao: MessageDao,
    private val attachmentDao: AttachmentDao
) {
    
    private val json = Json { ignoreUnknownKeys = true }
    
    fun getMessages(conversationId: String): Flow<List<Message>> {
        return messageDao.getMessagesByConversation(conversationId).map { entities ->
            entities.map { it.toModel() }
        }
    }
    
    suspend fun getMessageById(messageId: String): Message? {
        return messageDao.getMessageById(messageId)?.toModel()
    }
    
    suspend fun getUnreadMessages(conversationId: String): List<Message> {
        return messageDao.getUnseenMessages(conversationId, "").map { it.toModel() }
    }
    
    suspend fun getFailedMessages(): List<Message> {
        return messageDao.getMessagesByStatus(MessageStatus.FAILED.name).map { it.toModel() }
    }
    
    fun getPinnedMessages(conversationId: String): Flow<List<Message>> {
        return messageDao.getPinnedMessages(conversationId).map { entities ->
            entities.map { it.toModel() }
        }
    }
    
    suspend fun searchMessages(query: String): List<Message> {
        return messageDao.searchMessages(query).map { it.toModel() }
    }
    
    suspend fun insertMessage(message: Message) {
        messageDao.insertMessage(message.toEntity())
    }
    
    suspend fun updateMessageStatus(messageId: String, status: MessageStatus, timestamp: Long? = null) {
        when (status) {
            MessageStatus.DELIVERED -> {
                messageDao.markAsDelivered(listOf(messageId), timestamp ?: System.currentTimeMillis())
            }
            MessageStatus.SEEN -> {
                messageDao.markAsSeen(listOf(messageId), timestamp ?: System.currentTimeMillis())
            }
            else -> {
                messageDao.updateMessageStatus(messageId, status.name)
            }
        }
    }
    
    suspend fun markMessagesAsSeen(messageIds: List<String>, timestamp: Long) {
        messageDao.markAsSeen(messageIds, timestamp)
    }
    
    suspend fun addReaction(messageId: String, userId: String, emoji: String) {
        val message = messageDao.getMessageById(messageId) ?: return
        val reactions = message.reactionsJson?.let { 
            json.decodeFromString<List<Reaction>>(it) 
        }?.toMutableList() ?: mutableListOf()
        
        reactions.add(Reaction(userId, emoji))
        
        messageDao.updateMessage(message.copy(
            reactionsJson = json.encodeToString(reactions)
        ))
    }
    
    suspend fun removeReaction(messageId: String, userId: String) {
        val message = messageDao.getMessageById(messageId) ?: return
        val reactions = message.reactionsJson?.let { 
            json.decodeFromString<List<Reaction>>(it) 
        }?.toMutableList() ?: return
        
        reactions.removeAll { it.userId == userId }
        
        messageDao.updateMessage(message.copy(
            reactionsJson = json.encodeToString(reactions)
        ))
    }
    
    suspend fun editMessage(messageId: String, newContent: String, timestamp: Long) {
        val message = messageDao.getMessageById(messageId) ?: return
        messageDao.updateMessage(message.copy(
            content = newContent,
            isEdited = true,
            editedAt = timestamp
        ))
    }
    
    suspend fun deleteMessage(messageId: String) {
        messageDao.softDeleteMessage(messageId)
    }
    
    suspend fun deleteMessageForEveryone(messageId: String) {
        messageDao.softDeleteMessage(messageId)
    }
    
    suspend fun pinMessage(messageId: String, isPinned: Boolean) {
        val message = messageDao.getMessageById(messageId) ?: return
        messageDao.updateMessage(message.copy(isPinned = isPinned))
    }
    
    // Attachment methods
    
    suspend fun insertAttachment(attachment: Attachment) {
        attachmentDao.insertAttachment(attachment.toEntity())
    }
    
    suspend fun updateAttachment(attachment: Attachment) {
        attachmentDao.updateAttachment(attachment.toEntity())
    }
    
    suspend fun getAttachmentsByMessage(messageId: String): List<Attachment> {
        return attachmentDao.getAttachmentsByMessage(messageId).map { it.toModel() }
    }
    
    fun observeAttachments(messageId: String): Flow<List<Attachment>> {
        return attachmentDao.observeAttachments(messageId).map { entities ->
            entities.map { it.toModel() }
        }
    }
    
    suspend fun updateDownloadProgress(attachmentId: String, status: DownloadStatus, progress: Int) {
        attachmentDao.updateDownloadProgress(attachmentId, status.name, progress)
    }
    
    suspend fun markDownloadComplete(attachmentId: String, localPath: String) {
        attachmentDao.markDownloadComplete(attachmentId, localPath)
    }
    
    // Entity to Model conversions
    
    private fun MessageEntity.toModel(): Message {
        val reactions = reactionsJson?.let { 
            json.decodeFromString<List<Reaction>>(it) 
        } ?: emptyList()
        
        return Message(
            id = id,
            conversationId = conversationId,
            senderId = senderId,
            receiverId = receiverId,
            content = content,
            encryptedContent = encryptedContent,
            type = MessageType.valueOf(type),
            status = MessageStatus.valueOf(status),
            replyToId = replyToId,
            forwardedFrom = forwardedFrom,
            reactions = reactions,
            isEdited = isEdited,
            isDeleted = isDeleted,
            isPinned = isPinned,
            expiresAt = expiresAt,
            createdAt = createdAt,
            deliveredAt = deliveredAt,
            seenAt = seenAt,
            editedAt = editedAt
        )
    }
    
    private fun Message.toEntity(): MessageEntity {
        return MessageEntity(
            id = id,
            conversationId = conversationId,
            senderId = senderId,
            receiverId = receiverId,
            content = content,
            encryptedContent = encryptedContent,
            type = type.name,
            status = status.name,
            replyToId = replyToId,
            forwardedFrom = forwardedFrom,
            reactionsJson = if (reactions.isNotEmpty()) json.encodeToString(reactions) else null,
            isEdited = isEdited,
            isDeleted = isDeleted,
            isPinned = isPinned,
            expiresAt = expiresAt,
            createdAt = createdAt,
            deliveredAt = deliveredAt,
            seenAt = seenAt,
            editedAt = editedAt,
            syncedAt = null
        )
    }
    
    private fun AttachmentEntity.toModel(): Attachment {
        return Attachment(
            id = id,
            messageId = messageId,
            fileName = fileName,
            fileSize = fileSize,
            mimeType = mimeType,
            localPath = localPath,
            thumbnailPath = thumbnailPath,
            duration = duration,
            width = width,
            height = height,
            downloadStatus = DownloadStatus.valueOf(downloadStatus),
            uploadProgress = uploadProgress,
            downloadProgress = downloadProgress,
            checksum = checksum,
            createdAt = createdAt
        )
    }
    
    private fun Attachment.toEntity(): AttachmentEntity {
        return AttachmentEntity(
            id = id,
            messageId = messageId,
            fileName = fileName,
            fileSize = fileSize,
            mimeType = mimeType,
            localPath = localPath,
            thumbnailPath = thumbnailPath,
            duration = duration,
            width = width,
            height = height,
            downloadStatus = downloadStatus.name,
            uploadProgress = uploadProgress,
            downloadProgress = downloadProgress,
            checksum = checksum,
            createdAt = createdAt
        )
    }
}
