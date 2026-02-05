// data/models/PresenceStatus.kt
package com.ping.app.data.models

enum class PresenceStatus {
    ONLINE,       // Active in Ping app
    IN_APP,       // Using phone but not in Ping (other app)
    AWAY,         // Phone idle
    OFFLINE,      // Not connected
    DO_NOT_DISTURB // User set DND
}

data class PresenceInfo(
    val userId: String,
    val status: PresenceStatus,
    val lastSeen: Long?,
    val isTyping: Boolean,
    val typingInConversation: String?,
    val currentActivity: String? = null,
    val batteryLevel: Int? = null,
    val timestamp: Long = System.currentTimeMillis()
)
