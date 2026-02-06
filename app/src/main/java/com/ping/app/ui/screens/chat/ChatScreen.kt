// ui/screens/chat/ChatScreen.kt - Fixed imports section
package com.ping.app.ui.screens.chat

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.ping.app.data.models.*
import com.ping.app.ui.components.*
import com.ping.app.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    conversationId: String,
    onNavigateBack: () -> Unit,
    onNavigateToProfile: (String) -> Unit,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val typingState by viewModel.typingState.collectAsState()
    val presence by viewModel.peerPresence.collectAsState()
    
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    
    var replyToMessage by remember { mutableStateOf<Message?>(null) }
    var showMessageOptions by remember { mutableStateOf<Message?>(null) }
    
    // Load conversation data
    LaunchedEffect(conversationId) {
        viewModel.loadConversation(conversationId)
    }
    
    // Auto-scroll to bottom on new messages
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }
    
    // Mark messages as read when screen is visible
    LaunchedEffect(conversationId) {
        viewModel.markConversationAsRead()
    }
    
    Scaffold(
        topBar = {
            ChatTopBar(
                conversation = uiState.conversation,
                peerUser = uiState.peerUser,
                presence = presence,
                isTyping = typingState?.isTyping == true,
                onBackClick = onNavigateBack,
                onProfileClick = { uiState.peerUser?.id?.let(onNavigateToProfile) },
                onCallClick = { /* Video/Audio call */ },
                onMoreClick = { /* Show more options */ }
            )
        },
        bottomBar = {
            MessageInput(
                replyToMessage = replyToMessage,
                onSendMessage = { content ->
                    viewModel.sendMessage(content, replyToMessage?.id)
                    replyToMessage = null
                },
                onSendAttachment = { uri ->
                    viewModel.sendAttachment(uri, replyToMessage?.id)
                    replyToMessage = null
                },
                onTypingStateChanged = { isTyping ->
                    viewModel.updateTypingState(isTyping)
                },
                onCancelReply = { replyToMessage = null }
            )
        },
        containerColor = DarkBackground
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Messages list
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                reverseLayout = true,
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                // Typing indicator
                if (typingState?.isTyping == true) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            TypingIndicator(userName = uiState.peerUser?.displayName)
                        }
                    }
                }
                
                items(
                    items = messages,
                    key = { it.id }
                ) { message ->
                    val isFromMe = message.senderId == viewModel.currentUserId
                    val showAvatar = shouldShowAvatar(message, messages)
                    
                    MessageBubble(
                        message = message,
                        isFromMe = isFromMe,
                        showAvatar = showAvatar,
                        onLongPress = { showMessageOptions = message },
                        onReplyClick = {
                            message.replyToId?.let { replyId ->
                                scope.launch {
                                    val index = messages.indexOfFirst { it.id == replyId }
                                    if (index >= 0) {
                                        listState.animateScrollToItem(index)
                                    }
                                }
                            }
                        },
                        onImageClick = { attachment ->
                            // Open image viewer
                        },
                        onReactionClick = {
                            // Show reaction details
                        }
                    )
                }
                
                // Load more indicator
                item {
                    if (uiState.isLoadingMore) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                color = PrimaryPurple,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
            
            // Empty state
            if (messages.isEmpty() && !uiState.isLoading) {
                EmptyConversation(
                    peerName = uiState.peerUser?.displayName ?: "User",
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            
            // Loading state
            if (uiState.isLoading) {
                CircularProgressIndicator(
                    color = PrimaryPurple,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
    
    // Message options bottom sheet
    showMessageOptions?.let { message ->
        MessageOptionsSheet(
            message = message,
            isFromMe = message.senderId == viewModel.currentUserId,
            onDismiss = { showMessageOptions = null },
            onReply = {
                replyToMessage = message
                showMessageOptions = null
            },
            onReact = { emoji ->
                viewModel.addReaction(message.id, emoji)
                showMessageOptions = null
            },
            onCopy = {
                viewModel.copyToClipboard(message.content)
                showMessageOptions = null
            },
            onForward = {
                showMessageOptions = null
            },
            onPin = {
                viewModel.togglePinMessage(message)
                showMessageOptions = null
            },
            onEdit = {
                showMessageOptions = null
            },
            onDelete = { forEveryone ->
                viewModel.deleteMessage(message.id, forEveryone)
                showMessageOptions = null
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    conversation: Conversation?,
    peerUser: User?,
    presence: PresenceInfo?,
    isTyping: Boolean,
    onBackClick: () -> Unit,
    onProfileClick: () -> Unit,
    onCallClick: () -> Unit,
    onMoreClick: () -> Unit
) {
    TopAppBar(
        title = {
            Row(
                modifier = Modifier.clickable(onClick = onProfileClick),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Avatar with online indicator
                Box {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(DarkSurfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        peerUser?.avatarUrl?.let { url ->
                            AsyncImage(
                                model = url,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize()
                            )
                        } ?: Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = TextSecondary
                        )
                    }
                    
                    // Online indicator
                    presence?.let {
                        OnlineIndicator(
                            status = it.status,
                            size = 14.dp,
                            modifier = Modifier.align(Alignment.BottomEnd)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Column {
                    Text(
                        text = peerUser?.displayName ?: conversation?.groupName ?: "Chat",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                    
                    UserPresenceRow(
                        status = presence?.status ?: PresenceStatus.OFFLINE,
                        lastSeen = presence?.lastSeen,
                        isTyping = isTyping
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = TextPrimary
                )
            }
        },
        actions = {
            IconButton(onClick = onCallClick) {
                Icon(
                    imageVector = Icons.Default.VideoCall,
                    contentDescription = "Video call",
                    tint = TextPrimary
                )
            }
            IconButton(onClick = onCallClick) {
                Icon(
                    imageVector = Icons.Default.Call,
                    contentDescription = "Voice call",
                    tint = TextPrimary
                )
            }
            IconButton(onClick = onMoreClick) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "More options",
                    tint = TextPrimary
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = DarkSurface
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageOptionsSheet(
    message: Message,
    isFromMe: Boolean,
    onDismiss: () -> Unit,
    onReply: () -> Unit,
    onReact: (String) -> Unit,
    onCopy: () -> Unit,
    onForward: () -> Unit,
    onPin: () -> Unit,
    onEdit: () -> Unit,
    onDelete: (Boolean) -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DarkSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            // Quick reactions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                listOf("❤️", "😂", "😮", "😢", "😡", "👍").forEach { emoji ->
                    Text(
                        text = emoji,
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.clickable { onReact(emoji) }
                    )
                }
            }
            
            HorizontalDivider(color = DividerColor)
            
            // Options
            MessageOptionItem(
                icon = Icons.Default.Reply,
                label = "Reply",
                onClick = onReply
            )
            
            MessageOptionItem(
                icon = Icons.Default.ContentCopy,
                label = "Copy",
                onClick = onCopy
            )
            
            MessageOptionItem(
                icon = Icons.Default.Forward,
                label = "Forward",
                onClick = onForward
            )
            
            MessageOptionItem(
                icon = if (message.isPinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                label = if (message.isPinned) "Unpin" else "Pin",
                onClick = onPin
            )
            
            if (isFromMe && message.type == MessageType.TEXT) {
                MessageOptionItem(
                    icon = Icons.Default.Edit,
                    label = "Edit",
                    onClick = onEdit
                )
            }
            
            MessageOptionItem(
                icon = Icons.Default.Delete,
                label = "Delete for me",
                onClick = { onDelete(false) },
                tint = ErrorRed
            )
            
            if (isFromMe) {
                MessageOptionItem(
                    icon = Icons.Default.DeleteForever,
                    label = "Delete for everyone",
                    onClick = { onDelete(true) },
                    tint = ErrorRed
                )
            }
        }
    }
}

@Composable
private fun MessageOptionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = TextPrimary
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = tint
        )
    }
}

@Composable
private fun EmptyConversation(
    peerName: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Outlined.ChatBubbleOutline,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = TextTertiary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Start a conversation with $peerName",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Messages are end-to-end encrypted",
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary
        )
    }
}

private fun shouldShowAvatar(message: Message, messages: List<Message>): Boolean {
    val index = messages.indexOf(message)
    if (index == messages.lastIndex) return true
    
    val nextMessage = messages.getOrNull(index + 1) ?: return true
    return nextMessage.senderId != message.senderId
}
