// ui/screens/conversations/ConversationsScreen.kt
package com.ping.app.ui.screens.conversations

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ping.app.data.models.*
import com.ping.app.ui.components.OnlineIndicator
import com.ping.app.ui.components.TypingIndicatorSmall
import com.ping.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    onNavigateToChat: (String) -> Unit,
    onNavigateToNewChat: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: ConversationsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val conversations by viewModel.conversations.collectAsState()
    val onlineUsers by viewModel.onlineUsers.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            if (showSearch) {
                SearchTopBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    onClose = {
                        showSearch = false
                        searchQuery = ""
                    }
                )
            } else {
                MainTopBar(
                    onSearchClick = { showSearch = true },
                    onSettingsClick = onNavigateToSettings
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToNewChat,
                containerColor = PrimaryPurple,
                contentColor = androidx.compose.ui.graphics.Color.White
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "New chat"
                )
            }
        },
        containerColor = DarkBackground
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            // Online users row
            if (onlineUsers.isNotEmpty()) {
                item {
                    OnlineUsersRow(
                        users = onlineUsers,
                        onUserClick = { userId ->
                            viewModel.openOrCreateConversation(userId) { conversationId ->
                                onNavigateToChat(conversationId)
                            }
                        }
                    )
                }
            }
            
            // Pinned conversations
            val pinnedConversations = conversations.filter { it.isPinned }
            if (pinnedConversations.isNotEmpty()) {
                item {
                    SectionHeader(title = "Pinned")
                }
                items(
                    items = pinnedConversations,
                    key = { it.id }
                ) { conversation ->
                    ConversationItem(
                        conversation = conversation,
                        typingUserId = viewModel.getTypingUser(conversation.id),
                        onClick = { onNavigateToChat(conversation.id) },
                        onLongClick = { /* Show options */ }
                    )
                }
            }
            
            // All conversations
            val regularConversations = conversations.filter { !it.isPinned }
            if (regularConversations.isNotEmpty()) {
                if (pinnedConversations.isNotEmpty()) {
                    item {
                        SectionHeader(title = "All Messages")
                    }
                }
                items(
                    items = regularConversations,
                    key = { it.id }
                ) { conversation ->
                    ConversationItem(
                        conversation = conversation,
                        typingUserId = viewModel.getTypingUser(conversation.id),
                        onClick = { onNavigateToChat(conversation.id) },
                        onLongClick = { /* Show options */ }
                    )
                }
            }
            
            // Empty state
            if (conversations.isEmpty() && !uiState.isLoading) {
                item {
                    EmptyConversationsState(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp)
                    )
                }
            }
        }
        
        // Loading indicator
        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = PrimaryPurple)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTopBar(
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    TopAppBar(
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Ping",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = PrimaryPurple
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(OnlineGreen)
                )
            }
        },
        actions = {
            IconButton(onClick = onSearchClick) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = TextPrimary
                )
            }
            IconButton(onClick = onSettingsClick) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = TextPrimary
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = DarkBackground
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit
) {
    TopAppBar(
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = {
                    Text("Search conversations...", color = TextTertiary)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
                ),
                singleLine = true
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Close search",
                    tint = TextPrimary
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = DarkBackground
        )
    )
}

@Composable
private fun OnlineUsersRow(
    users: List<User>,
    onUserClick: (String) -> Unit
) {
    Column(
        modifier = Modifier.padding(vertical = 8.dp)
    ) {
        Text(
            text = "Online Now",
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(users) { user ->
                OnlineUserItem(
                    user = user,
                    onClick = { onUserClick(user.id) }
                )
            }
        }
    }
}

@Composable
private fun OnlineUserItem(
    user: User,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        Box {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(DarkSurfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                user.avatarUrl?.let { url ->
                    coil.compose.AsyncImage(
                        model = url,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                    )
                } ?: Text(
                    text = user.displayName.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.titleLarge,
                    color = PrimaryPurple
                )
            }
            
            OnlineIndicator(
                status = PresenceStatus.ONLINE,
                size = 16.dp,
                modifier = Modifier.align(Alignment.BottomEnd)
            )
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        
        Text(
            text = user.displayName.split(" ").first(),
            style = MaterialTheme.typography.labelSmall,
            color = TextPrimary,
            maxLines = 1
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = TextSecondary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationItem(
    conversation: Conversation,
    typingUserId: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar
        Box {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(DarkSurfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (conversation.isGroup) {
                    Icon(
                        imageVector = Icons.Default.Group,
                        contentDescription = null,
                        tint = PrimaryPurple,
                        modifier = Modifier.size(28.dp)
                    )
                } else {
                    conversation.groupAvatar?.let { url ->
                        coil.compose.AsyncImage(
                            model = url,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize()
                        )
                    } ?: Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            
            // Online indicator (for direct chats)
            if (!conversation.isGroup) {
                OnlineIndicator(
                    status = PresenceStatus.ONLINE, // Get actual status
                    size = 16.dp,
                    modifier = Modifier.align(Alignment.BottomEnd)
                )
            }
        }
        
        Spacer(modifier = Modifier.width(12.dp))
        
        // Content
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = conversation.groupName ?: "Chat",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = if (conversation.unreadCount > 0) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                
                conversation.lastMessageTimestamp?.let { timestamp ->
                    Text(
                        text = formatTimestamp(timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (conversation.unreadCount > 0) PrimaryPurple else TextTertiary
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Last message or typing indicator
                if (typingUserId != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TypingIndicatorSmall()
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "typing...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = PrimaryPurple
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        conversation.lastMessage?.let { message ->
                            // Status icon for sent messages
                            if (message.senderId == "currentUserId") { // Replace with actual check
                                MessageStatusIcon(
                                    status = message.status,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            
                            Text(
                                text = getLastMessagePreview(message),
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                
                // Unread badge
                if (conversation.unreadCount > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(PrimaryPurple)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (conversation.unreadCount > 99) "99+" else conversation.unreadCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = androidx.compose.ui.graphics.Color.White
                        )
                    }
                }
                
                // Muted icon
                if (conversation.isMuted) {
                    Icon(
                        imageVector = Icons.Default.VolumeOff,
                        contentDescription = "Muted",
                        tint = TextTertiary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                
                // Pinned icon
                if (conversation.isPinned) {
                    Icon(
                        imageVector = Icons.Default.PushPin,
                        contentDescription = "Pinned",
                        tint = PrimaryPurple,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageStatusIcon(
    status: MessageStatus,
    modifier: Modifier = Modifier
) {
    val (icon, tint) = when (status) {
        MessageStatus.SENDING -> Icons.Default.Schedule to TextTertiary
        MessageStatus.SENT -> Icons.Default.Check to TextSecondary
        MessageStatus.DELIVERED -> Icons.Default.DoneAll to TextSecondary
        MessageStatus.SEEN -> Icons.Default.DoneAll to AccentBlue
        MessageStatus.FAILED -> Icons.Default.Error to ErrorRed
    }
    
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = modifier
    )
}

@Composable
private fun EmptyConversationsState(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Outlined.Forum,
            contentDescription = null,
            modifier = Modifier.size(100.dp),
            tint = TextTertiary
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "No conversations yet",
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Start a new chat by tapping the button below",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    
    return when {
        diff < 60_000 -> "Now"
        diff < 3600_000 -> "${diff / 60_000}m"
        diff < 86400_000 -> "${diff / 3600_000}h"
        diff < 604800_000 -> "${diff / 86400_000}d"
        else -> {
            val formatter = SimpleDateFormat("MMM dd", Locale.getDefault())
            formatter.format(Date(timestamp))
        }
    }
}

private fun getLastMessagePreview(message: Message): String {
    return when (message.type) {
        MessageType.TEXT -> message.content
        MessageType.IMAGE -> "📷 Photo"
        MessageType.VIDEO -> "🎥 Video"
        MessageType.AUDIO -> "🎵 Audio"
        MessageType.FILE -> "📎 ${message.content}"
        MessageType.VOICE_NOTE -> "🎤 Voice message"
        MessageType.LOCATION -> "📍 Location"
        MessageType.CONTACT -> "👤 Contact"
        MessageType.STICKER -> "🏷️ Sticker"
        MessageType.GIF -> "GIF"
        MessageType.SYSTEM -> message.content
    }
}
