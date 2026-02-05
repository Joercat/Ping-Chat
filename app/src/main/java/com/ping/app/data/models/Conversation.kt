// data/models/Conversation.kt
package com.ping.app.data.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.util.UUID

@Parcelize
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val participantIds: List<String>,
    val isGroup: Boolean = false,
    val groupName: String? = null,
    val groupAvatar: String? = null,
    val creatorId: String? = null,
    val lastMessage: Message? = null,
    val lastMessageTimestamp: Long? = null,
    val unreadCount: Int = 0,
    val isPinned: Boolean = false,
    val isMuted: Boolean = false,
    val mutedUntil: Long? = null,
    val isArchived: Boolean = false,
    val isBlocked: Boolean = false,
    val theme: ConversationTheme = ConversationTheme.DEFAULT,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) : Parcelable

@Parcelize
data class ConversationTheme(
    val primaryColor: String = "#6C63FF",
    val bubbleColor: String = "#6C63FF",
    val backgroundImage: String? = null
) : Parcelable {
    companion object {
        val DEFAULT = ConversationTheme()
    }
}
