package com.ping.app.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.ping.app.data.Settings
import com.ping.app.ui.PingController
import com.ping.app.ui.theme.ErrorRed
import com.ping.app.ui.theme.PrimaryPurple
import com.ping.app.ui.theme.TextSecondary

/**
 * Adding somebody: paste anything they sent you, or scan the QR code they show.
 *
 * Both paths end up with the same self-signed contact card, so a code that was tampered with on
 * the way is rejected before it can be saved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactScreen(controller: PingController, onBack: () -> Unit, onAdded: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var showMyCode by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val error by controller.lastError.collectAsState()

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (!contents.isNullOrBlank()) {
            text = contents
            controller.addContact(contents)
            onAdded()
        }
        showScanner = false
    }

    LaunchedEffect(showScanner) {
        if (showScanner) {
            val options = ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Point at a Ping code")
                .setBeepEnabled(false)
                .setOrientationLocked(false)
            try {
                scanner.launch(options)
            } catch (t: Throwable) {
                // No camera (or no camera permission): the paste box still works.
                controller.clearError()
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Add contact") }
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
            Text(
                "Paste the code they sent you, or scan their QR code.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Invite code or contact code") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        controller.addContact(text)
                        onAdded()
                    },
                    enabled = text.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) { Text("Add") }
                OutlinedButton(onClick = { showScanner = true }) {
                    Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Scan")
                }
            }
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(error ?: "", color = ErrorRed, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(28.dp))
            Text("Your own code", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            val invite = controller.myInviteCode()
            val short = controller.myShortCode()
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (invite != null) {
                        Text(
                            "The full invite proves it is really you: it contains the public keys and " +
                                "is signed by them. Share it over any channel.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        Spacer(Modifier.height(12.dp))
                        QrImage(content = invite)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { clipboard.setText(AnnotatedString(invite)) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("Copy")
                            }
                            OutlinedButton(
                                onClick = { showMyCode = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("Share")
                            }
                        }
                    } else {
                        Text("Finish onboarding first", color = TextSecondary)
                    }
                    if (short.isNotBlank()) {
                        Spacer(Modifier.height(16.dp))
                        Text("Short code", style = MaterialTheme.typography.labelLarge)
                        Text(
                            short,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = TextSecondary
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "The short code only identifies your mailbox at the relay. It relies on the " +
                                "relay handing over the right keys, so compare the safety number afterwards.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showMyCode) {
        ShareSheet(controller = controller, onClose = { showMyCode = false })
    }
}

@Composable
private fun ShareSheet(controller: PingController, onClose: () -> Unit) {
    val context = LocalContext.current
    val invite = controller.myInviteCode().orEmpty()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Share your invite") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                QrImage(content = invite, size = 220)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Send it however you like — it contains no personal data, only your public keys.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, invite)
                }
                context.startActivity(Intent.createChooser(intent, "Share invite"))
            }) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}

@Composable
fun QrImage(content: String, size: Int = 260) {
    if (content.isBlank()) return
    val bitmap = remember(content, size) { runCatching { renderQr(content, size) }.getOrNull() }
    if (bitmap != null) {
        Surface(
            color = androidx.compose.ui.graphics.Color.White,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.padding(4.dp)
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Contact QR code",
                modifier = Modifier
                    .size(size.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .padding(12.dp)
            )
        }
    } else {
        Box(
            modifier = Modifier
                .size(size.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) { Text("QR unavailable", color = TextSecondary) }
    }
}

private fun renderQr(content: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            pixels[y * size + x] = if (matrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE
        }
    }
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
    return bitmap
}

/** Contact details: verification, security toggles and deletion. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactScreen(controller: PingController, peerHash: String, onBack: () -> Unit) {
    val contact by controller.contact(peerHash).collectAsState(initial = null)
    val verification by controller.verification(peerHash).collectAsState(initial = null)
    val conversation by controller.conversationForPeer(peerHash).collectAsState(initial = null)
    var name by remember(contact?.hash) { mutableStateOf(contact?.displayName ?: "") }
    var showDisappearing by remember { mutableStateOf(false) }
    val options = Settings.DISAPPEARING_OPTIONS

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Contact") }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(name = name.ifBlank { "?" }, verified = verification?.verified == true, size = 64)
                Spacer(Modifier.size(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(64) },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { controller.renameContact(peerHash, name) }) { Text("Save name") }

            Spacer(Modifier.height(16.dp))
            Text("Safety number", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Compare these digits with the other person over a call or in person. If they match, " +
                    "nobody is sitting in the middle — not even a relay you do not control.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Spacer(Modifier.height(10.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        verification?.number ?: "…",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Their key: ${verification?.theirFingerprint ?: "?"}   ·   yours: ${verification?.ownFingerprint ?: "?"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = verification?.verified == true,
                    onCheckedChange = { controller.setVerified(peerHash, it) }
                )
                Spacer(Modifier.size(12.dp))
                Column {
                    Text("Verified")
                    Text(
                        "Marks this contact as confirmed by you",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Text("This chat", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { showDisappearing = true }, modifier = Modifier.fillMaxWidth()) {
                val current = conversation?.disappearingSeconds ?: 0L
                Text("Disappearing messages: " + (options.firstOrNull { it.first == current }?.second ?: "Off"))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { controller.resetSession(peerHash) }, modifier = Modifier.fillMaxWidth()) {
                Text("Start a new encrypted session")
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = contact?.blocked == true,
                    onCheckedChange = { controller.setBlocked(peerHash, it) }
                )
                Spacer(Modifier.size(12.dp))
                Column {
                    Text("Block")
                    Text(
                        "Ignore everything from this contact",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            val conversationValue = conversation
            if (conversationValue != null) {
                Button(
                    onClick = {
                        controller.deleteConversation(conversationValue)
                        onBack()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = ErrorRed)
                ) { Text("Delete this conversation") }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (showDisappearing) {
        AlertDialog(
            onDismissRequest = { showDisappearing = false },
            title = { Text("Disappearing messages") },
            text = {
                Column {
                    options.forEach { (seconds, label) ->
                        TextButton(onClick = {
                            controller.setDisappearing(peerHash, seconds)
                            showDisappearing = false
                        }) { Text(label) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showDisappearing = false }) { Text("Cancel") } }
        )
    }
}
