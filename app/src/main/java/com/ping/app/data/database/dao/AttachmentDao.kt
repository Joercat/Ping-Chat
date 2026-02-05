// data/database/dao/AttachmentDao.kt
package com.ping.app.data.database.dao

import androidx.room.*
import com.ping.app.data.database.entities.AttachmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AttachmentDao {
    
    @Query("SELECT * FROM attachments WHERE messageId = :messageId")
    suspend fun getAttachmentsByMessage(messageId: String): List<AttachmentEntity>
    
    @Query("SELECT * FROM attachments WHERE messageId = :messageId")
    fun observeAttachments(messageId: String): Flow<List<AttachmentEntity>>
    
    @Query("SELECT * FROM attachments WHERE id = :attachmentId")
    suspend fun getAttachmentById(attachmentId: String): AttachmentEntity?
    
    @Query("SELECT * FROM attachments WHERE downloadStatus = 'PENDING' OR downloadStatus = 'DOWNLOADING'")
    suspend fun getPendingDownloads(): List<AttachmentEntity>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttachment(attachment: AttachmentEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttachments(attachments: List<AttachmentEntity>)
    
    @Update
    suspend fun updateAttachment(attachment: AttachmentEntity)
    
    @Query("UPDATE attachments SET downloadStatus = :status, downloadProgress = :progress WHERE id = :attachmentId")
    suspend fun updateDownloadProgress(attachmentId: String, status: String, progress: Int)
    
    @Query("UPDATE attachments SET localPath = :localPath, downloadStatus = 'COMPLETED' WHERE id = :attachmentId")
    suspend fun markDownloadComplete(attachmentId: String, localPath: String)
    
    @Delete
    suspend fun deleteAttachment(attachment: AttachmentEntity)
    
    @Query("DELETE FROM attachments WHERE messageId = :messageId")
    suspend fun deleteAttachmentsByMessage(messageId: String)
}
