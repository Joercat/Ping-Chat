// ui/screens/settings/HelpScreen.kt
package com.ping.app.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ping.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Help",
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
            // Search
            OutlinedTextField(
                value = "",
                onValueChange = { },
                placeholder = { Text("Search help articles...", color = TextTertiary) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = TextSecondary
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryPurple,
                    unfocusedBorderColor = DividerColor,
                    cursorColor = PrimaryPurple
                ),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )
            
            // FAQ Section
            Text(
                text = "Frequently Asked Questions",
                style = MaterialTheme.typography.labelLarge,
                color = PrimaryPurple,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column {
                    FAQItem(
                        question = "How does Ping work without servers?",
                        answer = "Ping uses peer-to-peer technology to connect directly between devices. Your messages are encrypted and sent directly to the recipient without passing through any central server."
                    )
                    HorizontalDivider(color = DividerColor)
                    FAQItem(
                        question = "Are my messages encrypted?",
                        answer = "Yes! All messages are end-to-end encrypted using AES-256 encryption. Only you and the recipient can read your messages."
                    )
                    HorizontalDivider(color = DividerColor)
                    FAQItem(
                        question = "How do I add contacts?",
                        answer = "You can add contacts by scanning their QR code or by searching for their username. Both devices need to be on the same network or have a way to connect."
                    )
                    HorizontalDivider(color = DividerColor)
                    FAQItem(
                        question = "What happens if I'm offline?",
                        answer = "Messages will be stored locally and automatically sent when you reconnect with the recipient."
                    )
                    HorizontalDivider(color = DividerColor)
                    FAQItem(
                        question = "How do I backup my messages?",
                        answer = "You can export your data from Settings > Privacy & Security > Export Data. Messages are stored locally on your device."
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Contact Section
            Text(
                text = "Contact Us",
                style = MaterialTheme.typography.labelLarge,
                color = PrimaryPurple,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column {
                    ContactItem(
                        icon = Icons.Outlined.Email,
                        title = "Email Support",
                        subtitle = "support@ping.app",
                        onClick = {
                            val intent = Intent(Intent.ACTION_SENDTO).apply {
                                data = Uri.parse("mailto:support@ping.app")
                                putExtra(Intent.EXTRA_SUBJECT, "Ping Support Request")
                            }
                            context.startActivity(intent)
                        }
                    )
                    HorizontalDivider(color = DividerColor)
                    ContactItem(
                        icon = Icons.Outlined.Language,
                        title = "Website",
                        subtitle = "www.ping.app",
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://ping.app"))
                            context.startActivity(intent)
                        }
                    )
                    HorizontalDivider(color = DividerColor)
                    ContactItem(
                        icon = Icons.Outlined.BugReport,
                        title = "Report a Bug",
                        subtitle = "Help us improve Ping",
                        onClick = {
                            val intent = Intent(Intent.ACTION_SENDTO).apply {
                                data = Uri.parse("mailto:bugs@ping.app")
                                putExtra(Intent.EXTRA_SUBJECT, "Bug Report")
                            }
                            context.startActivity(intent)
                        }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun FAQItem(
    question: String,
    answer: String
) {
    var expanded by remember { mutableStateOf(false) }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = question,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = TextSecondary
            )
        }
        
        AnimatedVisibility(visible = expanded) {
            Text(
                text = answer,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun ContactItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = TextTertiary
        )
    }
}
