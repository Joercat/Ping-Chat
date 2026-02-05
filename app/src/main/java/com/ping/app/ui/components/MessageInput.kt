// ui/components/MessageInput.kt
package com.ping.app.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.ping.app.data.models.Attachment
import com.ping.app.data.models.Message
import com.ping.app.ui.theme.*
import com.ping.app.utils.FileUtils
import kotlinx.coroutines.delay

@Composable
fun MessageInput(
    replyToMessage: Message? = null,
    onSendMessage: (String) -> Unit,
    onSendAttachment: (Uri) -> Unit,
    onTypingStateChanged: (Boolean) -> Unit,
    onCancelReply: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var textFieldValue by remember { mutableStateOf(TextFieldValue()) }
    var showAttachmentPicker by remember { mutableStateOf(false) }
    var isTyping by remember { mutableStateOf(false) }
    
    val context = LocalContext.current
    
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { onSendAttachment(it) }
    }
    
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { onSendAttachment(it) }
    }
    
    // Typing indicator debounce
    LaunchedEffect(textFieldValue.text) {
        if (textFieldValue.text.isNotEmpty() && !isTyping) {
            isTyping = true
            onTypingStateChanged(true)
        }
        
        if (textFieldValue.text.isEmpty() && isTyping) {
            isTyping = false
            onTypingStateChanged(false)
        }
        
        // Auto stop typing after 3 seconds of no input
        if (isTyping) {
            delay(3000)
            if (isTyping) {
                isTyping = false
                onTypingStateChanged(false)
            }
        }
    }
    
    Column(modifier = modifier) {
        // Reply preview
        AnimatedVisibility(
            visible = replyToMessage != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            replyToMessage?.let { message ->
                ReplyInputPreview(
                    message = message,
                    onCancel = onCancelReply
                )
            }
        }
        
        // Attachment picker
        AnimatedVisibility(
            visible = showAttachmentPicker,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            AttachmentPickerRow(
                onImageClick = {
                    imagePicker.launch("image/*")
                    showAttachmentPicker = false
                },
                onVideoClick = {
                    imagePicker.launch("video/*")
                    showAttachmentPicker = false
                },
                onFileClick = {
                    filePicker.launch("*/*")
                    showAttachmentPicker = false
                },
                onCameraClick = {
                    // Launch camera
                    showAttachmentPicker = false
                },
                onClose = { showAttachmentPicker = false }
            )
        }
        
        // Main input row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkSurface)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            // Attachment button
            IconButton(
                onClick = { showAttachmentPicker = !showAttachmentPicker }
            ) {
                Icon(
                    imageVector = if (showAttachmentPicker) Icons.Default.Close else Icons.Default.Add,
                    contentDescription = "Attachments",
                    tint = PrimaryPurple
                )
            }
            
            // Text input
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(DarkSurfaceVariant)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                BasicTextField(
                    value = textFieldValue,
                    onValueChange = { textFieldValue = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = TextPrimary
                    ),
                    cursorBrush = SolidColor(PrimaryPurple),
                    decorationBox = { innerTextField ->
                        Box {
                            if (textFieldValue.text.isEmpty()) {
                                Text(
                                    text = "Type a message...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextTertiary
                                )
                            }
                            innerTextField()
                        }
                    }
                )
            }
            
            Spacer(modifier = Modifier.width(8.dp))
            
            // Send or Voice button
            AnimatedContent(
                targetState = textFieldValue.text.isNotEmpty(),
                transitionSpec = {
                    scaleIn() + fadeIn() togetherWith scaleOut() + fadeOut()
                },
                label = "sendButton"
            ) { hasText ->
                if (hasText) {
                    IconButton(
                        onClick = {
                            if (textFieldValue.text.isNotBlank()) {
                                onSendMessage(textFieldValue.text)
                                textFieldValue = TextFieldValue()
                                isTyping = false
                                onTypingStateChanged(false)
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(PrimaryPurple)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "Send",
                            tint = Color.White
                        )
                    }
                } else {
                    IconButton(
                        onClick = { /* Voice recording */ },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(PrimaryPurple)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Voice message",
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReplyInputPreview(
    message: Message,
    onCancel: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSurface)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(40.dp)
                .background(PrimaryPurple, RoundedCornerShape(2.dp))
        )
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Reply to",
                style = MaterialTheme.typography.labelSmall,
                color = PrimaryPurple
            )
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1
            )
        }
        
        IconButton(onClick = onCancel) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Cancel reply",
                tint = TextSecondary
            )
        }
    }
}

@Composable
private fun AttachmentPickerRow(
    onImageClick: () -> Unit,
    onVideoClick: () -> Unit,
    onFileClick: () -> Unit,
    onCameraClick: () -> Unit,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSurface)
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        AttachmentOption(
            icon = Icons.Outlined.Image,
            label = "Gallery",
            color = AccentPink,
            onClick = onImageClick
        )
        AttachmentOption(
            icon = Icons.Outlined.Videocam,
            label = "Video",
            color = AccentBlue,
            onClick = onVideoClick
        )
        AttachmentOption(
            icon = Icons.Outlined.InsertDriveFile,
            label = "File",
            color = AccentOrange,
            onClick = onFileClick
        )
        AttachmentOption(
            icon = Icons.Outlined.CameraAlt,
            label = "Camera",
            color = AccentGreen,
            onClick = onCameraClick
        )
    }
}

@Composable
private fun AttachmentOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = color,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
    }
}
