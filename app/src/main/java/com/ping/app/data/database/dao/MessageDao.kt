// data/database/dao/MessageDao.kt
package com.ping.app.data.database.dao

import androidx.room.*
import com.ping.app.data.database.entities.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt DESC")
    fun getMessagesByConversation(conversationId: String): Flow<List<MessageEntity>>
    
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getMessagesPaginated(conversationId: String, limit: Int, offset: Int): List<MessageEntity>
    
    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getMessageById(messageId: String): MessageEntity?
    
    @Query("SELECT * FROM messages WHERE id = :messageId")
    fun observeMessage(messageId: String): Flow<MessageEntity?>
    
    @Query("SELECT * FROM messages WHERE status = :status")
    suspend fun getMessagesByStatus(status: String): List<MessageEntity>
    
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND status != 'SEEN' AND senderId != :currentUserId")
    suspend fun getUnseenMessages(conversationId: String, currentUserId: String): List<MessageEntity>
    
    @Query("SELECT * FROM messages WHERE content LIKE '%' || :query || '%' ORDER BY createdAt DESC")
    suspend fun searchMessages(query: String): List<MessageEntity>
    
    @Query("SELECT * FROM messages WHERE isPinned = 1 AND conversationId = :conversationId")
    fun getPinnedMessages(conversationId: String): Flow<List<MessageEntity>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>)
    
    @Update
    suspend fun updateMessage(message: MessageEntity)
    
    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    suspend fun updateMessageStatus(messageId: String, status: String)
    
    @Query("UPDATE messages SET status = 'DELIVERED', deliveredAt = :timestamp WHERE id IN (:messageIds)")
    suspend fun markAsDelivered(messageIds: List<String>, timestamp: Long)
    
    @Query("UPDATE messages SET status = 'SEEN', seenAt = :timestamp WHERE id IN (:messageIds)")
    suspend fun markAsSeen(messageIds: List<String>, timestamp: Long)
    
    @Query("UPDATE messages SET isDeleted = 1 WHERE id = :messageId")
    suspend fun softDeleteMessage(messageId: String)
    
    @Delete
    suspend fun deleteMessage(message: MessageEntity)
    
    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteConversationMessages(conversationId: String)
    
    @Query("DELETE FROM messages WHERE expiresAt IS NOT NULL AND expiresAt < :currentTime")
    suspend fun deleteExpiredMessages(currentTime: Long)
}
