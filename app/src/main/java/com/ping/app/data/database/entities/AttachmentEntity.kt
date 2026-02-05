// data/database/entities/AttachmentEntity.kt
package com.ping.app.data.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "attachments",
    indices = [Index("messageId")]
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val localPath: String?,
    val thumbnailPath: String?,
    val duration: Long?,
    val width: Int?,
    val height: Int?,
    val downloadStatus: String,
    val uploadProgress: Int,
    val downloadProgress: Int,
    val checksum: String?,
    val createdAt: Long
)
