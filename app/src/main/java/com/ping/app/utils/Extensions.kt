// utils/Extensions.kt
package com.ping.app.utils

import java.text.SimpleDateFormat
import java.util.*

fun Long.toFormattedTime(): String {
    val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    return formatter.format(Date(this))
}

fun Long.toFormattedDate(): String {
    val now = System.currentTimeMillis()
    val diff = now - this
    
    return when {
        diff < 60_000 -> "Just now"
        diff < 3600_000 -> "${diff / 60_000} min ago"
        diff < 86400_000 -> "${diff / 3600_000} hours ago"
        diff < 172800_000 -> "Yesterday"
        diff < 604800_000 -> "${diff / 86400_000} days ago"
        else -> {
            val formatter = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
            formatter.format(Date(this))
        }
    }
}

fun Long.toRelativeTime(): String {
    val now = System.currentTimeMillis()
    val diff = now - this
    
    return when {
        diff < 60_000 -> "now"
        diff < 3600_000 -> "${diff / 60_000}m"
        diff < 86400_000 -> "${diff / 3600_000}h"
        diff < 604800_000 -> "${diff / 86400_000}d"
        else -> {
            val formatter = SimpleDateFormat("MMM dd", Locale.getDefault())
            formatter.format(Date(this))
        }
    }
}

fun String.isValidUsername(): Boolean {
    return this.matches(Regex("^[a-zA-Z0-9_]{3,20}$"))
}

fun ByteArray.toHexString(): String {
    return joinToString("") { "%02x".format(it) }
}
