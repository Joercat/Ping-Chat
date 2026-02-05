// ui/components/OnlineIndicator.kt
package com.ping.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ping.app.data.models.PresenceStatus
import com.ping.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun OnlineIndicator(
    status: PresenceStatus,
    size: Dp = 12.dp,
    borderWidth: Dp = 2.dp,
    modifier: Modifier = Modifier
) {
    val color by animateColorAsState(
        targetValue = when (status) {
            PresenceStatus.ONLINE -> OnlineGreen
            PresenceStatus.IN_APP -> AwayYellow
            PresenceStatus.AWAY -> AwayYellow
            PresenceStatus.OFFLINE -> OfflineGray
            PresenceStatus.DO_NOT_DISTURB -> BusyRed
        },
        animationSpec = tween(300),
        label = "statusColor"
    )
    
    // Pulsing animation for online status
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (status == PresenceStatus.ONLINE) 0.5f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color.copy(alpha = pulseAlpha))
            .border(borderWidth, DarkBackground, CircleShape)
    )
}

@Composable
fun OnlineStatusBadge(
    status: PresenceStatus,
    lastSeen: Long?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        OnlineIndicator(status = status, size = 8.dp, borderWidth = 0.dp)
        
        Text(
            text = getStatusText(status, lastSeen),
            style = MaterialTheme.typography.labelSmall,
            color = when (status) {
                PresenceStatus.ONLINE -> OnlineGreen
                PresenceStatus.IN_APP -> AwayYellow
                else -> TextSecondary
            }
        )
    }
}

@Composable
fun UserPresenceRow(
    status: PresenceStatus,
    lastSeen: Long?,
    isTyping: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isTyping) {
            TypingIndicatorSmall()
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "typing...",
                style = MaterialTheme.typography.labelSmall,
                color = PrimaryPurple
            )
        } else {
            OnlineStatusBadge(status = status, lastSeen = lastSeen)
        }
    }
}

private fun getStatusText(status: PresenceStatus, lastSeen: Long?): String {
    return when (status) {
        PresenceStatus.ONLINE -> "Online"
        PresenceStatus.IN_APP -> "Active now"
        PresenceStatus.AWAY -> "Away"
        PresenceStatus.DO_NOT_DISTURB -> "Do not disturb"
        PresenceStatus.OFFLINE -> {
            lastSeen?.let { "Last seen ${formatLastSeen(it)}" } ?: "Offline"
        }
    }
}

private fun formatLastSeen(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    
    return when {
        diff < 60_000 -> "just now"
        diff < 3600_000 -> "${diff / 60_000}m ago"
        diff < 86400_000 -> "${diff / 3600_000}h ago"
        diff < 604800_000 -> "${diff / 86400_000}d ago"
        else -> {
            val formatter = SimpleDateFormat("MMM dd", Locale.getDefault())
            formatter.format(Date(timestamp))
        }
    }
}
