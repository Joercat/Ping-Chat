// ui/components/AttachmentPicker.kt
package com.ping.app.ui.components

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ping.app.data.models.Attachment
import com.ping.app.ui.theme.*

@Composable
fun AttachmentPicker(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    onImageSelected: (Uri) -> Unit,
    onVideoSelected: (Uri) -> Unit,
    onFileSelected: (Uri) -> Unit,
    onCameraCapture: (Uri) -> Unit,
    onLocationShare: () -> Unit,
    onContactShare: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
    // Image picker
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        uris.forEach { uri ->
            onImageSelected(uri)
        }
        onDismiss()
    }
    
    // Video picker
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            onVideoSelected(it)
            onDismiss()
        }
    }
    
    // File picker
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            onFileSelected(it)
            onDismiss()
        }
    }
    
    // Camera
    var capturedImageUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            capturedImageUri?.let {
                onCameraCapture(it)
                onDismiss()
            }
        }
    }
    
    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Handle permissions result
    }
    
    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkSurface)
                .padding(16.dp)
        ) {
            // Handle bar
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(TextTertiary)
            )
            
            Spacer(modifier = Modifier.height(20.dp))
            
            // Attachment options grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                AttachmentOption(
                    icon = Icons.Outlined.Image,
                    label = "Gallery",
                    color = AccentPink,
                    onClick = {
                        imagePickerLauncher.launch("image/*")
                    }
                )
                
                AttachmentOption(
                    icon = Icons.Outlined.Videocam,
                    label = "Video",
                    color = AccentBlue,
                    onClick = {
                        videoPickerLauncher.launch("video/*")
                    }
                )
                
                AttachmentOption(
                    icon = Icons.Outlined.CameraAlt,
                    label = "Camera",
                    color = AccentGreen,
                    onClick = {
                        // Create temp file and launch camera
                        // capturedImageUri = createTempImageUri(context)
                        // cameraLauncher.launch(capturedImageUri!!)
                    }
                )
                
                AttachmentOption(
                    icon = Icons.Outlined.InsertDriveFile,
                    label = "File",
                    color = AccentOrange,
                    onClick = {
                        filePickerLauncher.launch("*/*")
                    }
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                AttachmentOption(
                    icon = Icons.Outlined.LocationOn,
                    label = "Location",
                    color = ErrorRed,
                    onClick = onLocationShare
                )
                
                AttachmentOption(
                    icon = Icons.Outlined.Person,
                    label = "Contact",
                    color = PrimaryPurple,
                    onClick = onContactShare
                )
                
                AttachmentOption(
                    icon = Icons.Outlined.Poll,
                    label = "Poll",
                    color = AwayYellow,
                    onClick = { /* Create poll */ }
                )
                
                AttachmentOption(
                    icon = Icons.Outlined.Mic,
                    label = "Audio",
                    color = OnlineGreen,
                    onClick = {
                        filePickerLauncher.launch("audio/*")
                    }
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AttachmentOption(
    icon: ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = color,
                modifier = Modifier.size(28.dp)
            )
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
    }
}

@Composable
fun SelectedAttachmentsPreview(
    attachments: List<SelectedAttachment>,
    onRemove: (SelectedAttachment) -> Unit,
    modifier: Modifier = Modifier
) {
    if (attachments.isEmpty()) return
    
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .background(DarkSurface)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(attachments) { attachment ->
            AttachmentPreviewItem(
                attachment = attachment,
                onRemove = { onRemove(attachment) }
            )
        }
    }
}

@Composable
private fun AttachmentPreviewItem(
    attachment: SelectedAttachment,
    onRemove: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(80.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSurfaceVariant)
    ) {
        when (attachment.type) {
            AttachmentType.IMAGE -> {
                AsyncImage(
                    model = attachment.uri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            AttachmentType.VIDEO -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = attachment.uri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Icon(
                        imageVector = Icons.Default.PlayCircle,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            AttachmentType.FILE -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.InsertDriveFile,
                        contentDescription = null,
                        tint = PrimaryPurple,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = attachment.fileName ?: "File",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        maxLines = 2
                    )
                }
            }
            AttachmentType.AUDIO -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AudioFile,
                        contentDescription = null,
                        tint = OnlineGreen,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = attachment.fileName ?: "Audio",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        maxLines = 2
                    )
                }
            }
        }
        
        // Remove button
        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(24.dp)
                .background(DarkBackground.copy(alpha = 0.7f), CircleShape)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove",
                tint = Color.White,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentPickerBottomSheet(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    onImageSelected: (List<Uri>) -> Unit,
    onVideoSelected: (Uri) -> Unit,
    onFileSelected: (Uri) -> Unit,
    onCameraCapture: () -> Unit
) {
    if (!isVisible) return
    
    val sheetState = rememberModalBottomSheetState()
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DarkSurface,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(TextTertiary)
            )
        }
    ) {
        AttachmentPickerContent(
            onImageClick = {
                // Launch image picker
            },
            onVideoClick = {
                // Launch video picker
            },
            onFileClick = {
                // Launch file picker
            },
            onCameraClick = onCameraCapture,
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun AttachmentPickerContent(
    onImageClick: () -> 
