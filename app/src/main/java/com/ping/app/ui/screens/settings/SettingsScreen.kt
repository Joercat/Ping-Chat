// ui/screens/settings/SettingsScreen.kt
package com.ping.app.ui.screens.settings

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current
    
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
                            text = "@${uiState.currentUser?.username ?: "username"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                        uiState.currentUser?.status?.let { status ->
                            Text(
                                text = status,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextTertiary
                            )
                        }
                    }
                    
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = TextTertiary
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Account settings
            SettingsSection(title = "Account") {
                SettingsItem(
                    icon = Icons.Outlined.Key,
                    title = "Privacy & Security",
                    subtitle = "Encryption, blocked contacts",
                    onClick = onNavigateToPrivacy
                )
                
                HorizontalDivider(color = DividerColor)
                
                SettingsItem(
                    icon = Icons.Outlined.Notifications,
                    title = "Notifications",
                    subtitle = "Message, group & call tones",
                    onClick = onNavigateToNotifications
                )
                
                HorizontalDivider(color = DividerColor)
                
                SettingsItem(
                    icon = Icons.Outlined.Storage,
                    title = "Storage & Data",
                    subtitle = "Network usage, auto-download",
                    onClick = onNavigateToStorage
                )
            }
            
            // Appearance settings
            SettingsSection(title = "Appearance") {
                SettingsItemWithToggle(
                    icon = Icons.Outlined.DarkMode,
                    title = "Dark Mode",
                    subtitle = "Use dark theme",
                    checked = uiState.isDarkMode,
                    onCheckedChange = { viewModel.toggleDarkMode() }
                )
                
                HorizontalDivider(color = DividerColor)
                
                SettingsItem(
                    icon = Icons.Outlined.Wallpaper,
                    title = "Chat Wallpaper",
                    subtitle = "Change chat background",
                    onClick = { /* Open wallpaper picker */ }
                )
                
                HorizontalDivider(color = DividerColor)
                
                SettingsItem(
                    icon = Icons.Outlined.TextFields,
                    title = "Font Size",
                    subtitle = uiState.fontSize,
                    onClick = { /* Open font size dialog */ }
                )
            }
            
            // Connections settings
            SettingsSection(title = "Connections") {
                SettingsItemWithStatus(
                    icon = Icons.Outlined.Wifi,
                    title = "Network Status",
                    subtitle = if (uiState.isConnected) "Connected" else "Disconnected",
                    statusColor = if (uiState.isConnected) OnlineGreen else OfflineGray
                )
                
                HorizontalDivider(color = DividerColor)
                
                SettingsItem(
                    icon = Icons.Outlined.QrCodeScanner,
                    title = "Scan QR Code",
                    subtitle = "Connect with friends nearby",
                    onClick = { /* Open QR scanner */ }
                )
                
                HorizontalDivider(color = DividerColor)
                
                SettingsItem(
                    icon = Icons.Outlined.QrCode2,
                    title = "My QR Code",
                    subtitle = "Share your profile",
                    onClick = { /* Show QR code */ }
                )
                
                HorizontalDivider(color = DividerColor)
                
                SettingsItem(
                    icon = Icons.Outlined.Devices,
                    title = "Linked Devices",
                    subtitle = "Manage your devices",
                    onClick = { /* Open linked devices */ }
                )
            }
            
            // Support settings
            SettingsSection(title = "Support") {
                SettingsItem(
                    icon = Icons.Outlined.Help,
                    title = "Help",
                    subtitle = "FAQ, contact us",
                    onClick = onNavigateToHelp
                )
                
                HorizontalDivider(color = DividerColor)
                
                SettingsItem(
                    icon = Icons.Outlined.Info,
                    title = "About",
                    subtitle = "Version ${uiState.appVersion}",
                    onClick = onNavigateToAbout
                )
                
                HorizontalDivider(color = DividerColor)
                
                SettingsItem(
                    icon = Icons.Outlined.Share,
                    title = "Invite Friends",
                    subtitle = "Share Ping with friends",
                    onClick = { viewModel.shareApp(context) }
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Logout button
            Button(
                onClick = { viewModel.showLogoutDialog() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ErrorRed.copy(alpha = 0.15f),
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
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // App info with working links
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
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
                        modifier = Modifier.clickable {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://ping.app/privacy"))
                            context.startActivity(intent)
                        }
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextTertiary
                    )
                    Text(
                        text = "Terms of Service",
                        style = MaterialTheme.typography.labelMedium,
                        color = PrimaryPurple,
                        modifier = Modifier.clickable {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://ping.app/terms"))
                            context.startActivity(intent)
                        }
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "Made with ❤️ for privacy",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary
                )
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
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to log out? Your messages will remain safely stored on this device.",
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
                    Text("Log Out", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.hideLogoutDialog() }
                ) {
                    Text("Cancel")
                }
            },
            containerColor = DarkSurface,
            shape = RoundedCornerShape(16.dp)
        )
    }
    
    // Handle logout navigation
    LaunchedEffect(uiState.isLoggedOut) {
        if (uiState.isLoggedOut) {
            // Navigate to auth screen - handled by parent
        }
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
            fontWeight = FontWeight.SemiBold,
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
    onClick: () -> Unit
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
        
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = TextTertiary
        )
    }
}

@Composable
private fun SettingsItemWithToggle(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
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
private fun SettingsItemWithStatus(
    icon: ImageVector,
    title: String,
    subtitle: String,
    statusColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
        
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(statusColor)
        )
    }
}
