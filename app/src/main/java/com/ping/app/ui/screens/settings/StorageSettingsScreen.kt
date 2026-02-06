// ui/screens/settings/StorageSettingsScreen.kt
package com.ping.app.ui.screens.settings

import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ping.app.ui.theme.*
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: StorageSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    
    LaunchedEffect(Unit) {
        viewModel.calculateStorageUsage(context)
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Storage & Data",
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
            // Storage usage overview
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Storage Usage",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Progress bar
                    LinearProgressIndicator(
                        progress = { uiState.usagePercentage },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = PrimaryPurple,
                        trackColor = DarkSurfaceVariant
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Used: ${uiState.usedStorage}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        Text(
                            text = "Total: ${uiState.totalStorage}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Storage breakdown
                    StorageBreakdownItem(
                        icon = Icons.Outlined.Image,
                        label = "Photos",
                        size = uiState.photosSize,
                        color = AccentPink
                    )
                    StorageBreakdownItem(
                        icon = Icons.Outlined.Videocam,
                        label = "Videos",
                        size = uiState.videosSize,
                        color = AccentBlue
                    )
                    StorageBreakdownItem(
                        icon = Icons.Outlined.AudioFile,
                        label = "Audio",
                        size = uiState.audioSize,
                        color = AccentGreen
                    )
                    StorageBreakdownItem(
                        icon = Icons.Outlined.InsertDriveFile,
                        label = "Documents",
                        size = uiState.documentsSize,
                        color = AccentOrange
                    )
                }
            }
            
            // Auto-download settings
            SettingsSection(title = "Auto-Download") {
                AutoDownloadItem(
                    title = "When using mobile data",
                    subtitle = uiState.mobileDataSetting,
                    onClick = { viewModel.showMobileDataDialog() }
                )
                HorizontalDivider(color = DividerColor)
                AutoDownloadItem(
                    title = "When connected to Wi-Fi",
                    subtitle = uiState.wifiSetting,
                    onClick = { viewModel.showWifiDialog() }
                )
                HorizontalDivider(color = DividerColor)
                AutoDownloadItem(
                    title = "When roaming",
                    subtitle = uiState.roamingSetting,
                    onClick = { viewModel.showRoamingDialog() }
                )
            }
            
            // Storage management
            SettingsSection(title = "Manage Storage") {
                StorageActionItem(
                    icon = Icons.Outlined.CleaningServices,
                    title = "Clear Cache",
                    subtitle = "Free up ${uiState.cacheSize}",
                    onClick = { viewModel.clearCache(context) }
                )
                HorizontalDivider(color = DividerColor)
                StorageActionItem(
                    icon = Icons.Outlined.Photo,
                    title = "Clear Media",
                    subtitle = "Delete downloaded media",
                    onClick = { viewModel.showClearMediaDialog() },
                    tint = ErrorRed
                )
            }
            
            // Network usage
            SettingsSection(title = "Network Usage") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Data Sent",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = uiState.dataSent,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "Data Received",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = uiState.dataReceived,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
                
                HorizontalDivider(color = DividerColor)
                
                TextButton(
                    onClick = { viewModel.resetNetworkStats() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Reset Statistics",
                        color = PrimaryPurple
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
    
    // Clear media confirmation dialog
    if (uiState.showClearMediaDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.hideClearMediaDialog() },
            title = { Text("Clear Media", color = TextPrimary) },
            text = { 
                Text(
                    "This will delete all downloaded photos, videos, and audio files. This action cannot be undone.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.clearMedia(context) },
                    colors = ButtonDefaults.textButtonColors(contentColor = ErrorRed)
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.hideClearMediaDialog() }) {
                    Text("Cancel")
                }
            },
            containerColor = DarkSurface
        )
    }
}

@Composable
private fun StorageBreakdownItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    size: String,
    color: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = size,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
    }
}

@Composable
private fun AutoDownloadItem(
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

@Composable
private fun StorageActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    tint: Color = TextPrimary
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
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column { content() }
        }
    }
}
