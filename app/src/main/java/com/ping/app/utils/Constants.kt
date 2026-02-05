// utils/Constants.kt
package com.ping.app.utils

object Constants {
    const val MAX_FILE_SIZE = 10 * 1024 * 1024L // 10MB
    const val TYPING_TIMEOUT = 3000L // 3 seconds
    const val PRESENCE_UPDATE_INTERVAL = 5000L // 5 seconds
    const val MESSAGE_SYNC_INTERVAL = 30000L // 30 seconds
    const val CONNECTION_TIMEOUT = 10000L // 10 seconds
    
    const val DEFAULT_P2P_PORT = 9876
    const val MAX_RECONNECT_ATTEMPTS = 5
    const val RECONNECT_DELAY = 2000L
    
    const val DATABASE_NAME = "ping_database"
    const val PREFERENCES_NAME = "ping_preferences"
}
