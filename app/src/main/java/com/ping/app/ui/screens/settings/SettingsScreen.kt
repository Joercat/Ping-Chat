// ui/screens/settings/SettingsScreen.kt
package com.ping.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.ping.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToProfile: () -> Unit,
    onNavigateToPrivacy: () -> Unit,
    onNavigateToNotifications: () -> Unit,
    onNavigateToStorage: () -> Unit,
    onNavigateToHelp: () -> Unit,
    onNavigateToAbout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
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
            // Profile section
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .clickable(onClick = onNavigateToProfile),
                colors = CardDefaults.cardColors(
                    containerColor = DarkSurface
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Avatar
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .clip(CircleShape)
                            .background(DarkSurfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        uiState.currentUser?.avatarUrl?.let { url ->
                            AsyncImage(
                                model = url,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize()
                            )
                        } ?: Text(
                            text = uiState.currentUser?.displayName?.firstOrNull()?.uppercase() ?: "?",
                            style = MaterialTheme.typography.headlineSmall,
                            color = PrimaryPurple
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(16.dp))
                    
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = uiState.currentUser?.displayName ?: "User",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "@${uiState.currentUser?.username ?: ""}",
                            style = MaterialTheme.typography.bodyMedium,
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
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Settings sections
            SettingsSection(title = "Account") {
                SettingsItem(
                    icon = Icons.Outlined.Key,
                    title = "Privacy & Security",
                    subtitle = "Encryption, blocked contacts",
                    onClick = onNavigateToPrivacy
                )
                
                SettingsItem(
                    icon = Icons.Outlined.Notifications,
                    title = "Notifications",
                    subtitle = "Message, group & call tones",
                    onClick = onNavigateToNotifications
                )
                
                SettingsItem(
                    icon = Icons.Outlined.Storage,
                    title = "Storage & Data",
                    subtitle = "Network usage, auto-download",
                    onClick = onNavigateToStorage
                )
            }
            
            SettingsSection(title = "Appearance") {
                SettingsItem(
                    icon = Icons.Outlined.DarkMode,
                    title = "Theme",
                    subtitle = "Dark mode",
                    trailing = {
                        Switch(
                            checked = uiState.isDarkMode,
                            onCheckedChange = { viewModel.toggleDarkMode() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = PrimaryPurple,
                                checkedTrackColor = PrimaryPurple.copy(alpha = 0.5f)
                            )
                        )
                    }
                )
                
                SettingsItem(
                    icon = Icons.Outlined.Wallpaper,
                    title = "Chat Wallpaper",
                    subtitle = "Change chat background",
                    onClick = { /* Change wallpaper */ }
                )
                
                SettingsItem(
                    icon = Icons.Outlined.TextFields,
                    title = "Font Size",
                    subtitle = uiState.fontSize,
                    onClick = { /* Change font size */ }
                )
            }
            
            SettingsSection(title = "Connections") {
                SettingsItem(
                    icon = Icons.Outlined.Wifi,
                    title = "Network",
                    subtitle = if (uiState.isConnected) "Connected" else "Disconnected",
                    trailing = {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (uiState.isConnected) OnlineGreen else OfflineGray)
                        )
                    }
                )
                
                SettingsItem(
                    icon = Icons.Outlined.QrCodeScanner,
                    title = "Scan QR Code",
                    subtitle = "Connect with friends",
                    onClick = { /* Open QR scanner */ }
                )
                
                SettingsItem(
                    icon = Icons.Outlined.Devices,
                    title = "Linked Devices",
                    subtitle = "Manage your devices",
                    onClick = { /* Linked devices */ }
                )
            }
            
            SettingsSection(title = "Support") {
                SettingsItem(
                    icon = Icons.Outlined.Help,
                    title = "Help",
                    subtitle = "FAQ, contact us",
                    onClick = onNavigateToHelp
                )
                
                SettingsItem(
                    icon = Icons.Outlined.Info,
                    title = "About",
                    subtitle = "Version ${uiState.appVersion}",
                    onClick = onNavigateToAbout
                )
                
                SettingsItem(
                    icon = Icons.Outlined.Share,
                    title = "Invite Friends",
                    subtitle = "Share Ping with friends",
                    onClick = { viewModel.shareApp() }
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Logout button
            Button(
                onClick = { viewModel.showLogoutDialog() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ErrorRed.copy(alpha = 0.1f),
                    contentColor = ErrorRed
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Logout,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Log Out",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // App info
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
     // ui/screens/settings/SettingsScreen.kt (continued)
                    text = "Ping Messenger",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextTertiary
                )
                Text(
                    text = "Version ${uiState.appVersion}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Privacy Policy",
                        style = MaterialTheme.typography.labelMedium,
                        color = PrimaryPurple,
                        modifier = Modifier.clickable { /* Open privacy policy */ }
                    )
                    Text(
                        text = "Terms of Service",
                        style = MaterialTheme.typography.labelMedium,
                        color = PrimaryPurple,
                        modifier = Modifier.clickable { /* Open terms */ }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
    
    // Logout confirmation dialog
    if (uiState.showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.hideLogoutDialog() },
            title = {
                Text(
                    text = "Log Out",
                    color = TextPrimary
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to log out? Your messages will remain on this device.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.logout() },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = ErrorRed
                    )
                ) {
                    Text("Log Out")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.hideLogoutDialog() }
                ) {
                    Text("Cancel")
                }
            },
            containerColor = DarkSurface
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

@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = PrimaryPurple,
            modifier = Modifier.size(24.dp)
        )
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
        
        if (trailing != null) {
            trailing()
        } else if (onClick != null) {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = TextTertiary
            )
        }
    }
}
                
