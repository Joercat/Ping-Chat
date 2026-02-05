// data/repository/ConversationRepository.kt
package com.ping.app.data.repository

import com.ping.app.data.database.dao.ConversationDao
import com.ping.app.data.database.entities.ConversationEntity
import com.ping.app.data.models.Conversation
import com.ping.app.data.models.ConversationTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationRepository @Inject constructor(
    private val conversationDao: ConversationDao
) {
    
    private val json = Json { ignoreUnknownKeys = true }
    
    fun getConversations(): Flow<List<Conversation>> {
        return conversationDao.getActiveConversations().map { entities ->
            entities.map { it.toModel() }
        }
    }
    
    fun getArchivedConversations(): Flow<List<Conversation>> {
        return conversationDao.getArchivedConversations().map { entities ->
            entities.map { it.toModel() }
        }
    }
    
    suspend fun getConversationById(conversationId: String): Conversation? {
        return conversationDao.getConversationById(conversationId)?.toModel()
    }
    
    fun observeConversation(conversationId: String): Flow<Conversation?> {
        return conversationDao.observeConversation(conversationId).map { it?.toModel() }
    }
    
    suspend fun findOrCreateDirectConversation(userId: String, currentUserId: String): Conversation {
        val existing = conversationDao.findDirectConversation(userId)
        if (existing != null) {
            return existing.toModel()
        }
        
        val newConversation = Conversation(
            participantIds = listOf(currentUserId, userId),
            isGroup = false
        )
        conversationDao.insertConversation(newConversation.toEntity())
        return newConversation
    }
    
    suspend fun createGroupConversation(
        name: String,
        participantIds: List<String>,
        creatorId: String
    ): Conversation {
        val conversation = Conversation(
            participantIds = participantIds,
            isGroup = true,
            groupName = name,
            creatorId = creatorId
        )
        conversationDao.insertConversation(conversation.toEntity())
        return conversation
    }
    
    suspend fun updateConversation(conversation: Conversation) {
        conversationDao.updateConversation(conversation.toEntity())
    }
    
    suspend fun incrementUnreadCount(conversationId: String) {
        conversationDao.incrementUnreadCount(conversationId)
    }
    
    suspend fun clearUnreadCount(conversationId: String) {
        conversationDao.clearUnreadCount(conversationId)
    }
    
    suspend fun setPinned(conversationId: String, isPinned: Boolean) {
        conversationDao.setPinned(conversationId, isPinned)
    }
    
    suspend fun setMuted(conversationId: String, isMuted: Boolean, mutedUntil: Long? = null) {
        conversationDao.setMuted(conversationId, isMuted, mutedUntil)
    }
    
    suspend fun setArchived(conversationId: String, isArchived: Boolean) {
        conversationDao.setArchived(conversationId, isArchived)
    }
    
    fun getTotalUnreadCount(): Flow<Int> {
        return conversationDao.getTotalUnreadCount().map { it ?: 0 }
    }
    
    private fun ConversationEntity.toModel(): Conversation {
        return Conversation(
            id = id,
            participantIds = json.decodeFromString(participantIdsJson),
            isGroup = isGroup,
            groupName = groupName,
            groupAvatar = groupAvatar,
            creatorId = creatorId,
            lastMessageTimestamp = lastMessageTimestamp,
            unreadCount = unreadCount,
            isPinned = isPinned,
            isMuted = isMuted,
            mutedUntil = mutedUntil,
            isArchived = isArchived,
            isBlocked = isBlocked,
            theme = themeJson?.let { json.decodeFromString(it) } ?: ConversationTheme.DEFAULT,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }
    
    private fun Conversation.toEntity(): ConversationEntity {
        return ConversationEntity(
            id = id,
            participantIdsJson = json.encodeToString(participantIds),
            isGroup = isGroup,
            groupName = groupName,
            groupAvatar = groupAvatar,
            creatorId = creatorId,
            lastMessageId = lastMessage?.id,
            lastMessageTimestamp = lastMessageTimestamp,
            unreadCount = unreadCount,
            isPinned = isPinned,
            isMuted = isMuted,
            mutedUntil = mutedUntil,
            isArchived = isArchived,
            isBlocked = isBlocked,
            themeJson = json.encodeToString(theme),
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }
}
