package com.ping.app.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ping.app.data.AttachmentRecord
import com.ping.app.data.ChatMessage
import com.ping.app.data.MessageStatus
import com.ping.app.ui.PingController
import com.ping.app.ui.theme.AccentGreen
import com.ping.app.ui.theme.ErrorRed
import com.ping.app.ui.theme.PrimaryPurple
import com.ping.app.ui.theme.ReceivedBubble
import com.ping.app.ui.theme.SentBubble
import com.ping.app.ui.theme.TextSecondary
import com.ping.app.utils.toFormattedTime

/** One conversation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    controller: PingController,
    conversationId: String,
    onBack: () -> Unit,
    onOpenContact: (String) -> Unit,
    onPickAttachment: () -> Unit,
    pickedAttachment: PickedAttachment?,
    onPickedAttachmentHandled: () -> Unit
) {
    val conversation by controller.conversation(conversationId).collectAsState(initial = null)
    val peerHash = conversation?.peerHash
    val contact by controller.contact(peerHash.orEmpty()).collectAsState(initial = null)
    val messages by controller.messages(conversationId).collectAsState(initial = null)
    val typingMap by controller.typing.collectAsState()

    var draft by remember { mutableStateOf("") }
    var lastTypingSent by remember { mutableStateOf(0L) }
    val listState = rememberLazyListState()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    LaunchedEffect(conversationId) {
        controller.markRead(conversationId, peerHash.orEmpty())
    }

    // Land at the newest message when the conversation opens …
    LaunchedEffect(conversationId) {
        val count = messages?.size ?: 0
        if (count > 0) listState.scrollToItem(count - 1)
    }

    // … but only follow new messages while the reader is already at the bottom. Animating on every
    // incoming message forced the list around while somebody was reading history, which is part of
    // why the screen felt like it was flashing.
    LaunchedEffect(messages?.size) {
        val count = messages?.size ?: 0
        if (count == 0) return@LaunchedEffect
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (lastVisible >= count - 3) listState.animateScrollToItem(count - 1)
    }

    LaunchedEffect(peerHash, pickedAttachment) {
        if (peerHash != null && pickedAttachment != null) {
            controller.sendAttachment(
                peerHash,
                pickedAttachment.uri,
                pickedAttachment.name,
                pickedAttachment.mimeType
            )
            onPickedAttachmentHandled()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                contact?.displayName ?: "Unknown",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (contact?.verified == true) {
                                Spacer(Modifier.size(4.dp))
                                Icon(
                                    Icons.Filled.VerifiedUser,
                                    contentDescription = "Verified",
                                    tint = AccentGreen,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                        val typing = peerHash != null && (typingMap[peerHash] ?: 0L) > System.currentTimeMillis() - 6_000
                        val disappearing = conversation?.disappearingSeconds ?: 0L
                        if (typing || disappearing > 0) {
                            Text(
                                text = if (typing) "typing…" else "Disappearing messages",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (typing) AccentGreen else TextSecondary
                            )
                        }
                    }
                },
                actions = {
                    if ((conversation?.disappearingSeconds ?: 0L) > 0) {
                        Icon(
                            Icons.Filled.Timer,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .size(18.dp)
                        )
                    }
                    IconButton(onClick = { peerHash?.let(onOpenContact) }) {
                        Icon(Icons.Filled.Info, contentDescription = "Contact details")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(messages.orEmpty(), key = { it.id }) { message ->
                    MessageBubble(
                        message = message,
                        controller = controller,
                        onCopy = { text -> clipboard.setText(AnnotatedString(text)) },
                        onRetry = { controller.retryMessage(message.id) },
                        onDelete = { controller.deleteMessage(message.id) }
                    )
                }
            }

            Composer(
                draft = draft,
                onDraftChange = { value ->
                    draft = value
                    if (peerHash != null && value.isNotEmpty()) {
                        val now = System.currentTimeMillis()
                        if (now - lastTypingSent > 3_000) {
                            lastTypingSent = now
                            controller.notifyTyping(peerHash)
                        }
                    }
                },
                onSend = {
                    val peer = peerHash ?: return@Composer
                    val text = draft
                    draft = ""
                    controller.sendText(peer, text)
                    controller.stopTyping(peer)
                },
                onAttach = onPickAttachment
            )
        }
    }
}

data class PickedAttachment(
    val uri: android.net.Uri,
    val name: String,
    val mimeType: String
)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: ChatMessage,
    controller: PingController,
    onCopy: (String) -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val outgoing = message.outgoing
    val bubbleColor = if (outgoing) SentBubble else ReceivedBubble
    val textColor = if (outgoing) Color.White else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start
    ) {
        Box {
            Surface(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(
                        RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (outgoing) 16.dp else 4.dp,
                            bottomEnd = if (outgoing) 4.dp else 16.dp
                        )
                    )
                    .combinedClickable(onClick = {}, onLongClick = { menuOpen = true }),
                color = bubbleColor
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (message.attachmentId != null) {
                        AttachmentBody(message = message, controller = controller, outgoing = outgoing)
                    } else if (message.body == null) {
                        Text(
                            "This message was deleted",
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor.copy(alpha = 0.6f)
                        )
                    } else {
                        Text(message.body, style = MaterialTheme.typography.bodyMedium, color = textColor)
                    }
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            message.createdAt.toFormattedTime(),
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor.copy(alpha = 0.7f)
                        )
                        if (message.editedAt != null) {
                            Spacer(Modifier.size(4.dp))
                            Text(
                                "edited",
                                style = MaterialTheme.typography.labelSmall,
                                color = textColor.copy(alpha = 0.7f)
                            )
                        }
                        if (outgoing) {
                            Spacer(Modifier.size(4.dp))
                            Text(
                                text = when (message.status) {
                                    MessageStatus.PENDING -> "·"
                                    MessageStatus.SENT -> "✓"
                                    MessageStatus.DELIVERED -> "✓✓"
                                    MessageStatus.READ -> "✓✓"
                                    MessageStatus.FAILED -> "!"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = when (message.status) {
                                    MessageStatus.READ -> AccentGreen
                                    MessageStatus.FAILED -> ErrorRed
                                    else -> textColor.copy(alpha = 0.8f)
                                },
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                message.body?.let { body ->
                    DropdownMenuItem(
                        text = { Text("Copy") },
                        onClick = {
                            menuOpen = false
                            onCopy(body)
                        }
                    )
                }
                if (message.status == MessageStatus.FAILED && outgoing) {
                    DropdownMenuItem(
                        text = { Text("Try again") },
                        onClick = {
                            menuOpen = false
                            onRetry()
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text("Delete for everyone") },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    }
                )
            }
        }
    }
}

@Composable
private fun AttachmentBody(message: ChatMessage, controller: PingController, outgoing: Boolean) {
    val record by controller.attachment(message.attachmentId.orEmpty()).collectAsState(initial = null)
    val attachment = record
    if (attachment == null) {
        Text("File", color = if (outgoing) Color.White else MaterialTheme.colorScheme.onSurface)
        return
    }
    if (attachment.mimeType.startsWith("image/")) {
        var bitmap by remember(attachment.id) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(attachment.id, attachment.status) {
            if (attachment.complete) {
                controller.loadAttachment(attachment) { bytes ->
                    bitmap = bytes?.let { decodeSampled(it, 1080) }
                }
            }
        }
        val current = bitmap
        if (current != null) {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = attachment.fileName,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
        } else {
            AttachmentChip(attachment = attachment, outgoing = outgoing)
        }
    } else {
        AttachmentChip(attachment = attachment, outgoing = outgoing)
    }
}

@Composable
private fun AttachmentChip(attachment: AttachmentRecord, outgoing: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.InsertDriveFile,
            contentDescription = null,
            tint = if (outgoing) Color.White else PrimaryPurple,
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.size(8.dp))
        Column {
            Text(
                attachment.fileName,
                style = MaterialTheme.typography.bodyMedium,
                color = if (outgoing) Color.White else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = when {
                    attachment.status == 2 -> "Could not be verified"
                    attachment.complete -> "${attachment.size / 1024} KB"
                    else -> "${attachment.receivedChunks}/${attachment.chunkCount} received"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (outgoing) Color.White.copy(alpha = 0.8f) else TextSecondary
            )
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            IconButton(onClick = onAttach) {
                Icon(Icons.Filled.AttachFile, contentDescription = "Send a file", tint = TextSecondary)
            }
            TextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message") },
                maxLines = 5,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
            IconButton(
                onClick = onSend,
                enabled = draft.isNotBlank()
            ) {
                Icon(
                    Icons.Filled.Send,
                    contentDescription = "Send",
                    tint = if (draft.isNotBlank()) PrimaryPurple else TextSecondary
                )
            }
        }
    }
}

/** Decodes an image at a bounded size so a huge photo cannot exhaust memory. */
private fun decodeSampled(bytes: ByteArray, maxDimension: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    var width = bounds.outWidth
    var height = bounds.outHeight
    while (width / 2 >= maxDimension || height / 2 >= maxDimension) {
        sample *= 2
        width /= 2
        height /= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

