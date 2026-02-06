// ui/screens/settings/AboutScreen.kt
package com.ping.app.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ping.app.R
import com.ping.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val packageInfo = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (e: Exception) {
            null
        }
    }
    
    val versionName = packageInfo?.versionName ?: "1.0.0"
    val versionCode = packageInfo?.longVersionCode ?: 1
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "About",
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
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(32.dp))
            
            // App logo
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(PrimaryPurple),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "P",
                    style = MaterialTheme.typography.displayMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // App name
            Text(
                text = "Ping",
                style = MaterialTheme.typography.headlineMedium,
                color = PrimaryPurple,
                fontWeight = FontWeight.Bold
            )
            
            Text(
                text = "Secure P2P Messenger",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Version
            Text(
                text = "Version $versionName ($versionCode)",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Features
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Features",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    FeatureItem(
                        icon = Icons.Outlined.Lock,
                        title = "End-to-End Encryption",
                        description = "AES-256 encryption for all messages"
                    )
                    
                    FeatureItem(
                        icon = Icons.Outlined.CloudOff,
                        title = "No Servers",
                        description = "Direct peer-to-peer connection"
                    )
                    
                    FeatureItem(
                        icon = Icons.Outlined.Speed,
                        title = "Fast & Lightweight",
                        description = "Instant message delivery"
                    )
                    
                    FeatureItem(
                        icon = Icons.Outlined.VisibilityOff,
                        title = "Private by Design",
                        description = "Your data stays on your device"
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Links
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column {
                    AboutLinkItem(
                        icon = Icons.Outlined.Policy,
                        title = "Privacy Policy",
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://ping.app/privacy"))
                            context.startActivity(intent)
                        }
                    )
                    
                    HorizontalDivider(color = DividerColor)
                    
                    AboutLinkItem(
                        icon = Icons.Outlined.Description,
                        title = "Terms of Service",
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://ping.app/terms"))
                            context.startActivity(intent)
                        }
                    )
                    
                    HorizontalDivider(color = DividerColor)
                    
                    AboutLinkItem(
                        icon = Icons.Outlined.Code,
                        title = "Open Source Licenses",
                        onClick = {
                            // Show licenses dialog or navigate to licenses screen
                        }
                    )
                    
                    HorizontalDivider(color = DividerColor)
                    
                    AboutLinkItem(
                        icon = Icons.Outlined.Share,
                        title = "Share Ping",
                        onClick = {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Ping Messenger")
                                putExtra(
                                    Intent.EXTRA_TEXT,
                                    "Check out Ping - a secure peer-to-peer messenger! Download it here: https://ping.app/download"
                                )
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Share Ping"))
                        }
                    )
                    
                    HorizontalDivider(color = DividerColor)
                    
                    AboutLinkItem(
                        icon = Icons.Outlined.Star,
                        title = "Rate Ping",
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                data = Uri.parse("market://details?id=${context.packageName}")
                            }
                            try {
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                val webIntent = Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://play.google.com/store/apps/details?id=${context.packageName}")
                                )
                                context.startActivity(webIntent)
                            }
                        }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Copyright
            Text(
                text = "© 2024 Ping Messenger",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary
            )
            
            Text(
                text = "Made with ❤️ for privacy",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary
            )
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun FeatureItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = PrimaryPurple,
            modifier = Modifier.size(24.dp)
        )
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun AboutLinkItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
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
        
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = TextPrimary,
            modifier = Modifier.weight(1f)
        )
        
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = TextTertiary
        )
    }
}
