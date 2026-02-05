// data/database/entities/ConversationEntity.kt
package com.ping.app.data.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val participantIdsJson: String,
    val isGroup: Boolean,
    val groupName: String?,
    val groupAvatar: String?,
    val creatorId: String?,
    val lastMessageId: String?,
    val lastMessageTimestamp: Long?,
    val unreadCount: Int,
    val isPinned: Boolean,
    val isMuted: Boolean,
    val mutedUntil: Long?,
    val isArchived: Boolean,
    val isBlocked: Boolean,
    val themeJson: String?,
    val createdAt: Long,
    val updatedAt: Long
)
