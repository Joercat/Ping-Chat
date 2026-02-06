// ui/screens/settings/NotificationSettingsScreen.kt
package com.ping.app.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ping.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(
    onNavigateBack: () -> Unit
) {
    var messageNotifications by remember { mutableStateOf(true) }
    var groupNotifications by remember { mutableStateOf(true) }
    var showPreview by remember { mutableStateOf(true) }
    var vibrate by remember { mutableStateOf(true) }
    var led by remember { mutableStateOf(true) }
    var inAppSounds by remember { mutableStateOf(true) }
    var inAppVibrate by remember { mutableStateOf(true) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Notifications",
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkBackground
                )
            )
        },
        containerColor = DarkBackground
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            NotificationSection(title = "Messages") {
                NotificationToggleItem(
                    title = "Message Notifications",
                    subtitle = "Show notifications for new messages",
                    checked = messageNotifications,
                    onCheckedChange = { messageNotifications = it }
                )
                
                HorizontalDivider(color = DividerColor)
                
                NotificationActionItem(
                    title = "Notification Sound",
                    subtitle = "Ping",
                    onClick = { /* Select sound */ }
                )
                
                HorizontalDivider(color = DividerColor)
                
                NotificationToggleItem(
                    title = "Vibrate",
                    subtitle = "Vibrate on new messages",
                    checked = vibrate,
                    onCheckedChange = { vibrate = it }
                )
                
                HorizontalDivider(color = DividerColor)
                
                NotificationToggleItem(
                    title = "LED Light",
                    subtitle = "Flash LED on new messages",
                    checked = led,
                    onCheckedChange = { led = it }
                )
            }
            
            NotificationSection(title = "Groups") {
                NotificationToggleItem(
                    title = "Group Notifications",
                    subtitle = "Show notifications for group messages",
                    checked = groupNotifications,
                    onCheckedChange = { groupNotifications = it }
                )
                
                HorizontalDivider(color = DividerColor)
                
                NotificationActionItem(
                    title = "Group Sound",
                    subtitle = "Default",
                    onClick = { /* Select sound */ }
                )
            }
            
            NotificationSection(title = "Preview") {
                NotificationToggleItem(
                    title = "Show Preview",
                    subtitle = "Show message content in notifications",
                    checked = showPreview,
                    onCheckedChange = { showPreview = it }
                )
            }
            
            NotificationSection(title = "In-App Notifications") {
                NotificationToggleItem(
                    title = "In-App Sounds",
                    subtitle = "Play sounds while app is open",
                    checked = inAppSounds,
                    onCheckedChange = { inAppSounds = it }
                )
                
                HorizontalDivider(color = DividerColor)
                
                NotificationToggleItem(
                    title = "In-App Vibrate",
                    subtitle = "Vibrate while app is open",
                    checked = inAppVibrate,
                    onCheckedChange = { inAppVibrate = it }
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Reset button
            TextButton(
                onClick = { /* Reset to defaults */ },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Text(
                    text = "Reset Notification Settings",
                    color = PrimaryPurple
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun NotificationSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = PrimaryPurple,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
        )
        
        Card(
            colors = CardDefaults.cardColors(
                containerColor = DarkSurface
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column {
                content()
            }
        }
    }
}

@Composable
private fun NotificationToggleItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
        
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = PrimaryPurple,
                checkedTrackColor = PrimaryPurple.copy(alpha = 0.5f),
                uncheckedThumbColor = TextTertiary,
                uncheckedTrackColor = DarkSurfaceVariant
            )
        )
    }
}

@Composable
private fun NotificationActionItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
        
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = TextTertiary
        )
    }
}
