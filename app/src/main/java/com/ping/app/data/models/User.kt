// data/models/User.kt
package com.ping.app.data.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.util.UUID

@Parcelize
data class User(
    val id: String = UUID.randomUUID().toString(),
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val publicKey: String,
    val deviceId: String,
    val ipAddress: String? = null,
    val port: Int? = null,
    val status: String? = null,
    val presence: PresenceStatus = PresenceStatus.OFFLINE,
    val lastSeen: Long? = null,
    val isTyping: Boolean = false,
    val typingInConversation: String? = null,
    val isBlocked: Boolean = false,
    val isFavorite: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) : Parcelable
