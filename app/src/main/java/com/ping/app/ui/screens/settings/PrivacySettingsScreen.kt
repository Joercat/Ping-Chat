// ui/screens/settings/PrivacySettingsScreen.kt
package com.ping.app.ui.screens.settings

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
fun PrivacySettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToBlockedContacts: () -> Unit
) {
    var showLastSeen by remember { mutableStateOf(true) }
    var showOnlineStatus by remember { mutableStateOf(true) }
    var showReadReceipts by remember { mutableStateOf(true) }
    var showTypingIndicator by remember { mutableStateOf(true) }
    var screenshotProtection by remember { mutableStateOf(false) }
    var incognitoKeyboard by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Privacy & Security",
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
            // Encryption info
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = SuccessGreen.copy(alpha = 0.1f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = SuccessGreen
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "End-to-End Encrypted",
                            style = MaterialTheme.typography.titleSmall,
                            color = SuccessGreen
                        )
                        Text(
                            text = "All messages are secured with AES-256 encryption",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }
            
            // Privacy settings
            SettingsSection(title = "Privacy") {
                PrivacyToggleItem(
                    title = "Last Seen",
                    subtitle = "Show when you were last online",
                    checked = showLastSeen,
                    onCheckedChange = { showLastSeen = it }
                )
                
                HorizontalDivider(color = DividerColor)
                
                PrivacyToggleItem(
                    title = "Online Status",
                    subtitle = "Show when you're currently online",
                    checked = showOnlineStatus,
                    onCheckedChange = { showOnlineStatus = it }
                )
                
                HorizontalDivider(color = DividerColor)
                
                PrivacyToggleItem(
                    title = "Read Receipts",
                    subtitle = "Let others know when you've read their messages",
                    checked = showReadReceipts,
                    onCheckedChange = { showReadReceipts = it }
                )
                
                HorizontalDivider(color = DividerColor)
                
                PrivacyToggleItem(
                    title = "Typing Indicator",
                    subtitle = "Show when you're typing a message",
                    checked = showTypingIndicator,
                    onCheckedChange = { showTypingIndicator = it }
                )
            }
            
            SettingsSection(title = "Security") {
                PrivacyToggleItem(
                    title = "Screenshot Protection",
                    subtitle = "Prevent screenshots in the app",
                    checked = screenshotProtection,
                    onCheckedChange = { screenshotProtection = it }
                )
                
                HorizontalDivider(color = DividerColor)
                
                PrivacyToggleItem(
                    title = "Incognito Keyboard",
                    subtitle = "Request keyboard to disable learning",
                    checked = incognitoKeyboard,
                    onCheckedChange = { incognitoKeyboard = it }
                )
                
                HorizontalDivider(color = DividerColor)
                
                PrivacyActionItem(
                    icon = Icons.Outlined.Block,
                    title = "Blocked Contacts",
                    subtitle = "Manage blocked users",
                    onClick = onNavigateToBlockedContacts
                )
                
                HorizontalDivider(color = DividerColor)
                
                PrivacyActionItem(
                    icon = Icons.Outlined.Key,
                    title = "Encryption Keys",
                    subtitle = "View and verify your encryption keys",
                    onClick = { /* Show keys */ }
                )
            }
            
            SettingsSection(title = "Data") {
                PrivacyActionItem(
                    icon = Icons.Outlined.Download,
                    title = "Export Data",
                    subtitle = "Download a copy of your data",
                    onClick = { /* Export data */ }
                )
                
                HorizontalDivider(color = DividerColor)
                
                PrivacyActionItem(
                    icon = Icons.Outlined.DeleteForever,
                    title = "Delete All Data",
                    subtitle = "Permanently delete all messages and data",
                    onClick = { /* Delete data */ },
                    tint = ErrorRed
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun PrivacyToggleItem(
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
private fun PrivacyActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = TextPrimary
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (tint == ErrorRed) ErrorRed else PrimaryPurple,
            modifier = Modifier.size(24.dp)
        )
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = tint
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

@Composable
private fun SettingsSection(
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

private fun Modifier.clickable(onClick: () -> Unit) = this.then(
    androidx.compose.foundation.clickable(onClick = onClick)
)
