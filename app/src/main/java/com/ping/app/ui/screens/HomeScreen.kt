package com.ping.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ping.app.data.Contact
import com.ping.app.ui.ConversationView
import com.ping.app.ui.PingController
import com.ping.app.ui.theme.AccentGreen
import com.ping.app.ui.theme.OfflineGray
import com.ping.app.ui.theme.PrimaryPurple
import com.ping.app.ui.theme.TextSecondary
import com.ping.app.utils.toFormattedDate

/** The conversation list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    controller: PingController,
    onOpenChat: (ConversationView) -> Unit,
    onAddContact: () -> Unit,
    onSettings: () -> Unit,
    onShowMyCode: () -> Unit,
    onNewGroup: () -> Unit
) {
    val conversations by controller.conversations().collectAsState(initial = null)
    val engineState by controller.engineState.collectAsState()
    val settings by controller.settings.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Ping", fontWeight = FontWeight.Bold)
                        Text(
                            text = connectionLabel(engineState, settings.relayUrl.isNotBlank()),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (engineState.relayReachable || engineState.lanPeers > 0) AccentGreen else TextSecondary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNewGroup) {
                        Icon(Icons.Filled.GroupAdd, contentDescription = "New group")
                    }
                    IconButton(onClick = onShowMyCode) {
                        Icon(Icons.Filled.QrCode, contentDescription = "My code")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddContact,
                icon = { Icon(Icons.Filled.PersonAdd, contentDescription = null) },
                text = { Text("Add contact") },
                containerColor = PrimaryPurple
            )
        }
    ) { padding ->
        val list = conversations
        if (list.isNullOrEmpty()) {
            EmptyHome(modifier = Modifier.padding(padding), onAddContact = onAddContact)
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(list, key = { it.conversation.id }) { view ->
                    ConversationRow(view = view, onClick = { onOpenChat(view) })
                }
            }
        }
    }
}

private fun connectionLabel(state: com.ping.app.engine.EngineState, hasRelay: Boolean): String = when {
    !hasRelay && state.lanPeers > 0 -> "Local network · ${state.lanPeers} nearby"
    !hasRelay -> "Local network only"
    state.relayReachable && state.queued > 0 -> "Connected · ${state.queued} waiting"
    state.relayReachable -> "End-to-end encrypted"
    state.lastError != null -> "Offline — retrying"
    else -> "Connecting…"
}

@Composable
private fun ConversationRow(view: ConversationView, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Avatar(
                name = view.displayName,
                verified = view.contact?.verified == true,
                group = view.group != null
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        view.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (view.contact?.verified == true) {
                        Spacer(Modifier.size(4.dp))
                        Icon(
                            Icons.Filled.VerifiedUser,
                            contentDescription = "Verified",
                            tint = AccentGreen,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    when {
                        view.typing -> "typing…"
                        view.contact?.blocked == true -> "Blocked"
                        view.conversation.preview.isNotBlank() ->
                            (if (view.conversation.lastMessageOutgoing) "You: " else "") + view.conversation.preview
                        view.group != null -> "Group · ${view.group.memberCount + 1} members"
                        else -> "No messages yet"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (view.typing) AccentGreen else TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.size(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    view.conversation.lastMessageAt.toFormattedDate(),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                if (view.conversation.unreadCount > 0) {
                    Spacer(Modifier.height(6.dp))
                    BadgedBox(badge = { Badge { Text(view.conversation.unreadCount.toString()) } }) {}
                }
            }
        }
    }
}

@Composable
fun Avatar(name: String, verified: Boolean, size: Int = 46, group: Boolean = false) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(if (verified) PrimaryPurple.copy(alpha = 0.85f) else MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (group) {
            Icon(
                Icons.Filled.Groups,
                contentDescription = null,
                tint = if (verified) MaterialTheme.colorScheme.onPrimary else TextSecondary,
                modifier = Modifier.size((size * 0.55f).dp)
            )
        } else {
            Text(
                text = name.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (verified) MaterialTheme.colorScheme.onPrimary else TextSecondary
            )
        }
    }
}

@Composable
private fun EmptyHome(modifier: Modifier = Modifier, onAddContact: () -> Unit) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                Icons.Filled.ChatBubble,
                contentDescription = null,
                tint = OfflineGray,
                modifier = Modifier.size(56.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text("No conversations yet", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Add somebody by scanning their code, or share yours from the button above.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}
