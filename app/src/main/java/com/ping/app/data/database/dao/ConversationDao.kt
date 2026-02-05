// data/database/dao/ConversationDao.kt
package com.ping.app.data.database.dao

import androidx.room.*
import com.ping.app.data.database.entities.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    
    @Query("SELECT * FROM conversations ORDER BY isPinned DESC, lastMessageTimestamp DESC")
    fun getAllConversations(): Flow<List<ConversationEntity>>
    
    @Query("SELECT * FROM conversations WHERE isArchived = 0 ORDER BY isPinned DESC, lastMessageTimestamp DESC")
    fun getActiveConversations(): Flow<List<ConversationEntity>>
    
    @Query("SELECT * FROM conversations WHERE isArchived = 1 ORDER BY lastMessageTimestamp DESC")
    fun getArchivedConversations(): Flow<List<ConversationEntity>>
    
    @Query("SELECT * FROM conversations WHERE id = :conversationId")
    suspend fun getConversationById(conversationId: String): ConversationEntity?
    
    @Query("SELECT * FROM conversations WHERE id = :conversationId")
    fun observeConversation(conversationId: String): Flow<ConversationEntity?>
    
    @Query("SELECT * FROM conversations WHERE participantIdsJson LIKE '%' || :userId || '%' AND isGroup = 0 LIMIT 1")
    suspend fun findDirectConversation(userId: String): ConversationEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: ConversationEntity)
    
    @Update
    suspend fun updateConversation(conversation: ConversationEntity)
    
    @Query("UPDATE conversations SET unreadCount = unreadCount + 1 WHERE id = :conversationId")
    suspend fun incrementUnreadCount(conversationId: String)
    
    @Query("UPDATE conversations SET unreadCount = 0 WHERE id = :conversationId")
    suspend fun clearUnreadCount(conversationId: String)
    
    @Query("UPDATE conversations SET isPinned = :isPinned WHERE id = :conversationId")
    suspend fun setPinned(conversationId: String, isPinned: Boolean)
    
    @Query("UPDATE conversations SET isMuted = :isMuted, mutedUntil = :mutedUntil WHERE id = :conversationId")
    suspend fun setMuted(conversationId: String, isMuted: Boolean, mutedUntil: Long?)
    
    @Query("UPDATE conversations SET isArchived = :isArchived WHERE id = :conversationId")
    suspend fun setArchived(conversationId: String, isArchived: Boolean)
    
    @Delete
    suspend fun deleteConversation(conversation: ConversationEntity)
    
    @Query("SELECT SUM(unreadCount) FROM conversations")
    fun getTotalUnreadCount(): Flow<Int?>
}
