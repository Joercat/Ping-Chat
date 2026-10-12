package com.ping.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ping.app.data.Settings
import com.ping.app.ui.PingController
import com.ping.app.ui.theme.ErrorRed
import com.ping.app.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(controller: PingController, onBack: () -> Unit) {
    val settings by controller.settings.collectAsState()
    val engineState by controller.engineState.collectAsState()
    val busy by controller.busy.collectAsState()
    val error by controller.lastError.collectAsState()
    val storage by controller.storageSummary().collectAsState(initial = null)
    val clipboard = LocalClipboardManager.current

    var name by remember(settings.displayName) { mutableStateOf(settings.displayName) }
    var relay by remember(settings.relayUrl) { mutableStateOf(settings.relayUrl) }
    var confirmWipe by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Settings") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Section("You")
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(64) },
                label = { Text("Display name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { controller.renameSelf(name) },
                    modifier = Modifier.weight(1f)
                ) { Text("Save name") }
                OutlinedButton(
                    onClick = {
                        controller.myInviteCode()?.let { clipboard.setText(AnnotatedString(it)) }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Copy invite")
                }
            }

            Section("Relay")
            Text(
                "The relay only forwards sealed envelopes and keeps them in memory until they are " +
                    "collected. It never stores messages on disk and cannot read any of them.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = relay,
                onValueChange = { relay = it.take(256) },
                label = { Text("Relay address") },
                placeholder = { Text("https://your-relay.example.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { controller.testRelay(relay, settings.allowInsecureRelay) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f)
                ) { Text("Save and test") }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = when {
                    !settings.relayUrl.isBlank() && engineState.relayReachable -> "Connected to the relay"
                    !settings.relayUrl.isBlank() && engineState.lastError != null -> engineState.lastError ?: ""
                    settings.relayUrl.isBlank() -> "No relay configured: local network only"
                    else -> "Not connected yet"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (engineState.relayReachable) MaterialTheme.colorScheme.primary else TextSecondary
            )
            SwitchRow(
                title = "Keep the relay awake",
                subtitle = "Sends a small request every few minutes so a free host such as Render " +
                    "does not put the relay to sleep. The relay only sees that a device is online.",
                checked = settings.keepRelayAwake,
                onChange = { controller.setKeepRelayAwake(it) }
            )
            Text(
                text = "Relay last answered: " + lastSeenLabel(settings.relayLastSeen),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            SwitchRow(
                title = "Allow plain HTTP for private addresses",
                checked = settings.allowInsecureRelay,
                onChange = { controller.setAllowInsecureRelay(it) }
            )
            SwitchRow(
                title = "Use the local network",
                checked = settings.localNetworkEnabled,
                onChange = { controller.setLanEnabled(it) }
            )

            Section("Privacy")
            SwitchRow(
                title = "Notifications",
                checked = settings.notificationsEnabled,
                onChange = { controller.setNotifications(it) }
            )
            // Ping's own switch can be on while Android refuses to show anything. That used to be a
            // silent failure; now it is said out loud, with a way to fix it.
            val notificationContext = LocalContext.current
            val systemAllowsNotifications = NotificationManagerCompat.from(notificationContext).areNotificationsEnabled()
            if (settings.notificationsEnabled && !systemAllowsNotifications) {
                Text(
                    "Android is blocking Ping's notifications, so new messages arrive without an alert. " +
                        "Allow notifications for Ping in Android's settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                TextButton(onClick = {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, notificationContext.packageName)
                    notificationContext.startActivity(intent)
                }) { Text("Open notification settings") }
            }
            SwitchRow(
                title = "Show message text in notifications",
                checked = settings.notificationPreview,
                onChange = { controller.setNotificationPreview(it) }
            )
            SwitchRow(
                title = "Send read receipts",
                checked = settings.readReceipts,
                onChange = { controller.setReadReceipts(it) }
            )
            SwitchRow(
                title = "Send typing indicators",
                checked = settings.typingIndicators,
                onChange = { controller.setTypingIndicators(it) }
            )
            SwitchRow(
                title = "Block screenshots",
                checked = settings.screenshotGuard,
                onChange = { controller.setScreenshotGuard(it) }
            )
            SwitchRow(
                title = "Require unlock to open Ping",
                checked = settings.appLock,
                onChange = { controller.setAppLock(it) }
            )

            Section("Messages")
            var showDisappearing by remember { mutableStateOf(false) }
            OutlinedButton(onClick = { showDisappearing = true }, modifier = Modifier.fillMaxWidth()) {
                val label = Settings.DISAPPEARING_OPTIONS
                    .firstOrNull { it.first == settings.defaultDisappearingSeconds }?.second ?: "Off"
                Text("New chats disappear after: $label")
            }
            if (showDisappearing) {
                AlertDialog(
                    onDismissRequest = { showDisappearing = false },
                    title = { Text("Disappearing messages") },
                    text = {
                        Column {
                            Settings.DISAPPEARING_OPTIONS.forEach { (seconds, label) ->
                                TextButton(onClick = {
                                    controller.setDefaultDisappearing(seconds)
                                    showDisappearing = false
                                }) { Text(label) }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showDisappearing = false }) { Text("Cancel") }
                    }
                )
            }

            Section("Storage")
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    storage ?: "…",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(16.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { confirmWipe = true },
                colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Delete everything") }

            Section("How Ping protects you")
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Line("Messages", "Sealed with a Double Ratchet (X3DH handshake, ChaCha20-Poly1305, fresh keys per message)")
                    Line("Metadata", "Envelopes are padded to fixed sizes and carry only a rotating session tag; the relay never learns who wrote to whom")
                    Line("At rest", "An SQLCipher database whose key is sealed by this phone's secure hardware, with the sensitive columns encrypted a second time")
                    Line("Identity", "Ed25519 signing key and X25519 key agreement key, generated here and never uploaded")
                    Line("No accounts", "There is no phone number, no e-mail and no server-side profile")
                }
            }

            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Text(error ?: "", color = ErrorRed, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("Delete everything?") },
            text = {
                Text(
                    "This destroys your keys, your contacts and every message, and it cancels the " +
                        "relay registration. There is no way back — there is no backup anywhere."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmWipe = false
                    controller.panicWipe()
                }) { Text("Delete", color = ErrorRed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmWipe = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(24.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(10.dp))
}

/** "not yet", "just now", "7 min ago", … for the relay status line. */
private fun lastSeenLabel(lastSeen: Long): String {
    if (lastSeen <= 0L) return "not yet"
    val seconds = (System.currentTimeMillis() - lastSeen) / 1_000
    return when {
        seconds < 60 -> "just now"
        seconds < 3_600 -> "${seconds / 60} min ago"
        seconds < 86_400 -> "${seconds / 3_600} h ago"
        else -> "${seconds / 86_400} d ago"
    }
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    subtitle: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Line(title: String, body: String) {
    Column(modifier = Modifier.padding(bottom = 10.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Text(body, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    }
}
