// data/models/PresenceStatus.kt
package com.ping.app.data.models

import kotlinx.serialization.Serializable

@Serializable
enum class PresenceStatus {
    ONLINE,       // Active in Ping app
    IN_APP,       // Using phone but not in Ping (other app)
    AWAY,         // Phone idle
    OFFLINE,      // Not connected
    DO_NOT_DISTURB // User set DND
}

@Serializable
data class PresenceInfo(
    val userId: String,
    val status: PresenceStatus,
    val lastSeen: Long? = null,
    val isTyping: Boolean = false,
    val typingInConversation: String? = null,
    val currentActivity: String? = null,
    val batteryLevel: Int? = null,
    val timestamp: Long = System.currentTimeMillis()
)
