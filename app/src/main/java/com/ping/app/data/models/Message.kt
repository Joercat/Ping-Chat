// data/models/Message.kt
package com.ping.app.data.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.util.UUID

@Parcelize
data class Message(
    val id: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val senderId: String,
    val receiverId: String,
    val content: String,
    val encryptedContent: ByteArray? = null,
    val type: MessageType = MessageType.TEXT,
    val status: MessageStatus = MessageStatus.SENDING,
    val replyToId: String? = null,
    val forwardedFrom: String? = null,
    val attachments: List<Attachment> = emptyList(),
    val reactions: List<Reaction> = emptyList(),
    val isEdited: Boolean = false,
    val isDeleted: Boolean = false,
    val isPinned: Boolean = false,
    val expiresAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val deliveredAt: Long? = null,
    val seenAt: Long? = null,
    val editedAt: Long? = null
) : Parcelable {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Message
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

enum class MessageType {
    TEXT,
    IMAGE,
    VIDEO,
    AUDIO,
    FILE,
    VOICE_NOTE,
    LOCATION,
    CONTACT,
    STICKER,
    GIF,
    SYSTEM
}

enum class MessageStatus {
    SENDING,
    SENT,
    DELIVERED,
    SEEN,
    FAILED
}

@Parcelize
data class Reaction(
    val userId: String,
    val emoji: String,
    val timestamp: Long = System.currentTimeMillis()
) : Parcelable
