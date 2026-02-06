// ui/screens/profile/ProfileScreen.kt
package com.ping.app.ui.screens.profile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.ping.app.data.models.PresenceStatus
import com.ping.app.ui.components.OnlineIndicator
import com.ping.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    userId: String? = null, // null for own profile
    onNavigateBack: () -> Unit,
    onStartChat: ((String) -> Unit)? = null,
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val isOwnProfile = userId == null
    
    LaunchedEffect(userId) {
        viewModel.loadProfile(userId)
    }
    
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.updateAvatar(it) }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (isOwnProfile) "My Profile" else "Profile",
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
                actions = {
                    if (isOwnProfile) {
                        IconButton(onClick = { viewModel.toggleEditMode() }) {
                            Icon(
                                imageVector = if (uiState.isEditing) Icons.Default.Check else Icons.Default.Edit,
                                contentDescription = if (uiState.isEditing) "Save" else "Edit",
                                tint = PrimaryPurple
                            )
                        }
                    } else {
                        IconButton(onClick = { /* More options */ }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More",
                                tint = TextPrimary
                            )
                        }
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
            // Profile header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkSurface)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Avatar
                    Box {
                        Box(
                            modifier = Modifier
                                .size(120.dp)
                                .clip(CircleShape)
                                .background(DarkSurfaceVariant)
                                .then(
                                    if (isOwnProfile && uiState.isEditing) {
                                        Modifier.clickable { imagePicker.launch("image/*") }
                                    } else {
                                        Modifier
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            uiState.user?.avatarUrl?.let { url ->
                                AsyncImage(
                                    model = url,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } ?: Text(
                                text = uiState.user?.displayName?.firstOrNull()?.uppercase() ?: "?",
                                style = MaterialTheme.typography.displayMedium,
                                color = PrimaryPurple
                            )
                        }
                        
                        // Online indicator
                        uiState.user?.let { user ->
                            OnlineIndicator(
                                status = user.presence,
                                size = 24.dp,
                                modifier = Modifier.align(Alignment.BottomEnd)
                            )
                        }
                        
                        // Edit overlay
                        if (isOwnProfile && uiState.isEditing) {
                            Box(
                                modifier = Modifier
                                    .size(120.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.5f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CameraAlt,
                                    contentDescription = "Change photo",
                                    tint = Color.White,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Display name
                    if (uiState.isEditing) {
                        OutlinedTextField(
                            value = uiState.editedDisplayName,
                            onValueChange = { viewModel.updateDisplayName(it) },
                            textStyle = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PrimaryPurple,
                                unfocusedBorderColor = DividerColor
                            ),
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true,
                            modifier = Modifier.width(200.dp)
                        )
                    } else {
                        Text(
                            text = uiState.user?.displayName ?: "",
                            style = MaterialTheme.typography.headlineSmall,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    // Username
                    Text(
                        text = "@${uiState.user?.username ?: ""}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextSecondary
                    )
                    
                    // Status
                    uiState.user?.status?.let { status ->
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        if (uiState.isEditing) {
                            OutlinedTextField(
                                value = uiState.editedStatus,
                                onValueChange = { viewModel.updateStatus(it) },
                                placeholder = { Text("Your status") },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = PrimaryPurple,
                                    unfocusedBorderColor = DividerColor
                                ),
                                shape = RoundedCornerShape(8.dp),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(0.8f)
                            )
                        } else {
                            Text(
                                text = status,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextTertiary
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Action buttons (for other users)
            if (!isOwnProfile && onStartChat != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { uiState.user?.id?.let { onStartChat(it) } },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryPurple
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Message,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Message")
                    }
                    
                    OutlinedButton(
                        onClick = { /* Voice call */ },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = PrimaryPurple
                        ),
                        border = ButtonDefaults.outlinedButtonBorder.copy(
                            brush = androidx.compose.ui.graphics.SolidColor(PrimaryPurple)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Call,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Call")
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
            }
            
            // Info section
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = DarkSurface
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    ProfileInfoItem(
                        icon = Icons.Outlined.AlternateEmail,
                        label = "Username",
                        value = "@${uiState.user?.username ?: ""}"
                    )
                    
                    HorizontalDivider(color = DividerColor)
                    
                    ProfileInfoItem(
                        icon = Icons.Outlined.Key,
                        label = "Encryption",
                        value = "End-to-end encrypted",
                        valueColor = SuccessGreen
                    )
                    
                    if (!isOwnProfile) {
                        HorizontalDivider(color = DividerColor)
                        
                        ProfileInfoItem(
                            icon = Icons.Outlined.AccessTime,
                            label = "Last seen",
                            value = uiState.user?.lastSeen?.let { 
                                formatLastSeen(it) 
                            } ?: "Unknown"
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Actions section
            if (!isOwnProfile) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = DarkSurface
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        ProfileActionItem(
                            icon = Icons.Outlined.Star,
                            label = if (uiState.user?.isFavorite == true) "Remove from favorites" else "Add to favorites",
                            onClick = { viewModel.toggleFavorite() }
                        )
                        
                        HorizontalDivider(color = DividerColor)
                        
                        ProfileActionItem(
                            icon = Icons.Outlined.Block,
                            label = if (uiState.user?.isBlocked == true) "Unblock" else "Block",
                            onClick = { viewModel.toggleBlock() },
                            tint = if (uiState.user?.isBlocked == true) ErrorRed else TextPrimary
                        )
                        
                        HorizontalDivider(color = DividerColor)
                        
                        ProfileActionItem(
                            icon = Icons.Outlined.Report,
                            label = "Report",
                            onClick = { /* Report user */ },
                            tint = ErrorRed
                        )
                    }
                }
            }
            
            // Settings section (for own profile)
            if (isOwnProfile) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = DarkSurface
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        ProfileActionItem(
                            icon = Icons.Outlined.QrCode2,
                            label = "My QR Code",
                            onClick = { viewModel.showQrCode() }
                        )
                        
                        HorizontalDivider(color = DividerColor)
                        
                        ProfileActionItem(
                            icon = Icons.Outlined.Share,
                            label = "Share Profile",
                            onClick = { viewModel.shareProfile() }
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ProfileInfoItem(
    icon: ImageVector,
    label: String,
    value: String,
    valueColor: Color = TextPrimary
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
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = valueColor
            )
        }
    }
}

@Composable
private fun ProfileActionItem(
    icon: ImageVector,
    label: String,
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
            tint = tint,
            modifier = Modifier.size(24.dp)
        )
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = tint,
            modifier = Modifier.weight(1f)
        )
        
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = TextTertiary
        )
    }
}

private fun formatLastSeen(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    
    return when {
        diff < 60_000 -> "Just now"
        diff < 3600_000 -> "${diff / 60_000} minutes ago"
        diff < 86400_000 -> "${diff / 3600_000} hours ago"
        diff < 604800_000 -> "${diff / 86400_000} days ago"
        else -> "A while ago"
    }
}
