// ui/components/MessageBubble.kt
package com.ping.app.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ping.app.data.models.*
import com.ping.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun MessageBubble(
    message: Message,
    isFromMe: Boolean,
    showAvatar: Boolean = true,
    onLongPress: () -> Unit = {},
    onReplyClick: () -> Unit = {},
    onImageClick: (Attachment) -> Unit = {},
    onReactionClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showReactionPicker by remember { mutableStateOf(false) }
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = if (isFromMe) Alignment.End else Alignment.Start
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = if (isFromMe) Arrangement.End else Arrangement.Start
        ) {
            // Avatar for received messages
            if (!isFromMe && showAvatar) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(DarkSurfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            } else if (!isFromMe) {
                Spacer(modifier = Modifier.width(40.dp))
            }
            
            // Message content
            Column(
                horizontalAlignment = if (isFromMe) Alignment.End else Alignment.Start,
                modifier = Modifier.weight(1f, fill = false)
            ) {
                // Reply preview
                message.replyToId?.let { replyId ->
                    ReplyPreview(
                        replyToId = replyId,
                        onClick = onReplyClick
                    )
                }
                
                // Main bubble
                Box(
                    modifier = Modifier
                        .clip(
                            RoundedCornerShape(
                                topStart = 20.dp,
                                topEnd = 20.dp,
                                bottomStart = if (isFromMe) 20.dp else 6.dp,
                                bottomEnd = if (isFromMe) 6.dp else 20.dp
                            )
                        )
                        .background(if (isFromMe) SentBubble else ReceivedBubble)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onLongPress = { onLongPress() }
                            )
                        }
                        .padding(2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(
                            start = 12.dp,
                            end = 12.dp,
                            top = 8.dp,
                            bottom = 6.dp
                        )
                    ) {
                        // Attachments
                        if (message.attachments.isNotEmpty()) {
                            AttachmentContent(
                                attachments = message.attachments,
                                onImageClick = onImageClick
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        
                        // Text content
                        if (message.content.isNotEmpty() && !message.isDeleted) {
                            Text(
                                text = message.content,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White
                            )
                        } else if (message.isDeleted) {
                            Text(
                                text = "🚫 This message was deleted",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                                ),
                                color = TextSecondary
                            )
                        }
                        
                        // Time and status
                        Row(
                            modifier = Modifier
                                .align(Alignment.End)
                                .padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (message.isEdited) {
                                Text(
                                    text = "edited",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                            
                            Text(
                                text = formatTime(message.createdAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isFromMe) Color.White.copy(alpha = 0.7f) else TextSecondary
                            )
                            
                            if (isFromMe) {
                                MessageStatusIcon(status = message.status)
                            }
                        }
                    }
                }
                
                // Reactions
                if (message.reactions.isNotEmpty()) {
                    ReactionsRow(
                        reactions = message.reactions,
                        onClick = onReactionClick
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachmentContent(
    attachments: List<Attachment>,
    onImageClick: (Attachment) -> Unit
) {
    attachments.forEach { attachment ->
        when {
            attachment.isImage -> {
                ImageAttachment(
                    attachment = attachment,
                    onClick = { onImageClick(attachment) }
                )
            }
            attachment.isVideo -> {
                VideoAttachment(
                    attachment = attachment,
                    onClick = { onImageClick(attachment) }
                )
            }
            else -> {
                FileAttachment(attachment = attachment)
            }
        }
    }
}

@Composable
private fun ImageAttachment(
    attachment: Attachment,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .widthIn(max = 250.dp)
            .heightIn(max = 300.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = attachment.localPath,
            contentDescription = attachment.fileName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth()
        )
        
        if (attachment.downloadStatus != DownloadStatus.COMPLETED) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    progress = { attachment.downloadProgress / 100f },
                    color = PrimaryPurple
                )
            }
        }
    }
}

@Composable
private fun VideoAttachment(
    attachment: Attachment,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .widthIn(max = 250.dp)
            .heightIn(max = 300.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSurfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        attachment.thumbnailPath?.let { thumbnail ->
            AsyncImage(
                model = thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        
        Icon(
            imageVector = Icons.Default.PlayCircle,
            contentDescription = "Play video",
            modifier = Modifier.size(48.dp),
            tint = Color.White
        )
        
        attachment.duration?.let { duration ->
            Text(
                text = formatDuration(duration),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun FileAttachment(attachment: Attachment) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(DarkSurfaceVariant)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.InsertDriveFile,
            contentDescription = null,
            tint = PrimaryPurple,
            modifier = Modifier.size(32.dp)
        )
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = attachment.fileName,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                maxLines = 1
            )
            Text(
                text = formatFileSize(attachment.fileSize),
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
        
        if (attachment.downloadStatus == DownloadStatus.COMPLETED) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = SuccessGreen,
                modifier = Modifier.size(20.dp)
            )
        } else {
            CircularProgressIndicator(
                progress = { attachment.downloadProgress / 100f },
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp
            )
        }
    }
}

@Composable
private fun MessageStatusIcon(status: MessageStatus) {
    val (icon, tint) = when (status) {
        MessageStatus.SENDING -> Icons.Default.Schedule to Color.White.copy(alpha = 0.5f)
        MessageStatus.SENT -> Icons.Default.Check to Color.White.copy(alpha = 0.7f)
        MessageStatus.DELIVERED -> Icons.Default.DoneAll to Color.White.copy(alpha = 0.7f)
        MessageStatus.SEEN -> Icons.Default.DoneAll to AccentBlue
        MessageStatus.FAILED -> Icons.Default.Error to ErrorRed
    }
    
    Icon(
        imageVector = icon,
        contentDescription = status.name,
        modifier = Modifier.size(16.dp),
        tint = tint
    )
}

@Composable
private fun ReactionsRow(
    reactions: List<Reaction>,
    onClick: () -> Unit
) {
    val groupedReactions = reactions.groupBy { it.emoji }
    
    Row(
        modifier = Modifier
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSurfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        groupedReactions.forEach { (emoji, reactionList) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(text = emoji, style = MaterialTheme.typography.bodySmall)
                if (reactionList.size > 1) {
                    Text(
                        text = reactionList.size.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun ReplyPreview(
    replyToId: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .padding(bottom = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(DarkSurfaceVariant.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(24.dp)
                .background(PrimaryPurple, RoundedCornerShape(2.dp))
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Replying to message...",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
    }
}

private fun formatTime(timestamp: Long): String {
    val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    return formatter.format(Date(timestamp))
}

private fun formatDuration(durationMs: Long): String {
    val seconds = (durationMs / 1000) % 60
    val minutes = (durationMs / (1000 * 60)) % 60
    return String.format("%d:%02d", minutes, seconds)
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }
}
