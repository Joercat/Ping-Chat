package com.ping.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ping.app.ui.PingController
import com.ping.app.ui.theme.AccentGreen
import com.ping.app.ui.theme.PrimaryPurple
import com.ping.app.ui.theme.TextSecondary

/**
 * First launch: create the identity.
 *
 * No account, no phone number, no e-mail — just a key pair that is generated on this device and
 * never leaves it. The relay address is the only thing that has to come from outside, and it can
 * stay empty for local-network-only use.
 */
@Composable
fun OnboardingScreen(controller: PingController, onDone: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var relay by remember { mutableStateOf("") }
    var useLan by remember { mutableStateOf(true) }
    var allowInsecure by remember { mutableStateOf(false) }
    val busy by controller.busy.collectAsState()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(32.dp))
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(CircleShape)
                    .background(PrimaryPurple),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(44.dp)
                )
            }
            Spacer(Modifier.height(20.dp))
            Text("Ping", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Peer-to-peer messages with end-to-end encryption",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(28.dp))
            Card2 {
                Text(
                    "What this app does",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                Bullet("Every message is sealed on this device and opened on the other one. Nobody in between can read it — not the relay, not your network.")
                Bullet("The relay only holds encrypted envelopes until the other phone collects them. It never writes a message to disk.")
                Bullet("Phones on the same Wi-Fi talk directly and can work with no internet at all.")
                Bullet("Your identity is a key pair stored in this phone's secure hardware. There is no account to sign up for.")
            }

            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(64) },
                label = { Text("Your name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = relay,
                onValueChange = { relay = it.take(256) },
                label = { Text("Relay address (optional)") },
                placeholder = { Text("https://your-relay.example.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "You can leave this empty: then Ping only works on your local network. " +
                    "Messages stay end-to-end encrypted either way, but a relay that runs over plain " +
                    "HTTP also leaks who talks to whom, so it is only accepted for private addresses.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            Spacer(Modifier.height(12.dp))
            SwitchRow(
                title = "Use the local network",
                subtitle = "Find people on the same Wi-Fi and talk to them directly",
                icon = Icons.Filled.Wifi,
                checked = useLan,
                onChange = { useLan = it }
            )
            SwitchRow(
                title = "Allow plain HTTP relays on private addresses",
                subtitle = "Only needed for a relay at home such as http://192.168.1.10:8080",
                icon = Icons.Filled.Lock,
                checked = allowInsecure,
                onChange = { allowInsecure = it }
            )

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    controller.completeOnboarding(name, relay, allowInsecure, useLan)
                    onDone()
                },
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Create my identity", fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "The keys are generated here and never uploaded. If you uninstall the app, " +
                    "the conversations cannot be recovered — there is no backup and no server-side copy.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Card2(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    ) {
        Column(modifier = Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(AccentGreen)
        )
        Spacer(Modifier.size(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = PrimaryPurple, modifier = Modifier.size(22.dp))
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        Spacer(Modifier.size(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
