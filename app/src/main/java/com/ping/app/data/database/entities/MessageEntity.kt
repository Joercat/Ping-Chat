// data/database/entities/MessageEntity.kt
package com.ping.app.data.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    indices = [
        Index("conversationId"),
        Index("senderId"),
        Index("createdAt"),
        Index("status")
    ]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val senderId: String,
    val receiverId: String,
    val content: String,
    val encryptedContent: ByteArray?,
    val type: String,
    val status: String,
    val replyToId: String?,
    val forwardedFrom: String?,
    val reactionsJson: String?,
    val isEdited: Boolean,
    val isDeleted: Boolean,
    val isPinned: Boolean,
    val expiresAt: Long?,
    val createdAt: Long,
    val deliveredAt: Long?,
    val seenAt: Long?,
    val editedAt: Long?,
    val syncedAt: Long?
)
