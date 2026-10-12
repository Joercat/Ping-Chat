package com.ping.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ping.app.core.protocol.Wire
import com.ping.app.data.Contact
import com.ping.app.ui.PingController
import com.ping.app.ui.theme.AccentGreen
import com.ping.app.ui.theme.PrimaryPurple
import com.ping.app.ui.theme.TextSecondary

/**
 * Creating a group: a name and a set of contacts.
 *
 * The rules the crypto needs are stated on the screen, because they are the difference between a
 * group that is private and one that only looks private: every member gets a key that is delivered
 * inside their own encrypted one-to-one channel, and anybody who joins later gets a *new* key, so
 * they cannot read what was said before.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewGroupScreen(controller: PingController, onBack: () -> Unit, onCreated: (String) -> Unit) {
    val contacts by controller.contacts().collectAsState(initial = null)
    var name by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    val busy by controller.busy.collectAsState()
    val error by controller.lastError.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                },
                title = { Text("New group") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(Wire.MAX_NAME_BYTES) },
                label = { Text("Group name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Messages use one key per epoch, sent to each member inside their own encrypted chat. " +
                    "The relay carries the same sealed envelopes as a normal message and learns nothing " +
                    "about the group.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Spacer(Modifier.height(12.dp))
            Text("Members", style = MaterialTheme.typography.titleSmall)
            val list = contacts.orEmpty()
            if (list.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Add a contact first — a group needs somebody to talk to.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(list, key = { it.hash }) { contact ->
                        val checked = contact.hash in selected
                        ContactPickRow(
                            contact = contact,
                            checked = checked,
                            onClick = {
                                selected = if (checked) selected - contact.hash else selected + contact.hash
                            }
                        )
                    }
                }
            }
            if (error != null) {
                Text(error ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
            }
            Button(
                onClick = {
                    controller.createGroup(name, selected.toList()) { group ->
                        if (group != null) onCreated(group.id)
                    }
                },
                enabled = !busy && selected.isNotEmpty() && name.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Icon(Icons.Filled.GroupAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(
                    when {
                        selected.isEmpty() -> "Pick at least one member"
                        name.isBlank() -> "Name the group"
                        selected.size == 1 -> "Create group with 1 member"
                        else -> "Create group with ${selected.size} members"
                    }
                )
            }
        }
    }
}

@Composable
private fun ContactPickRow(contact: Contact, checked: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        InitialsAvatar(name = contact.displayName.ifBlank { contact.hash.take(2) })
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                contact.displayName.ifBlank { contact.hash.take(8) },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (contact.displayName.isNotBlank()) {
                Text(contact.hash.take(12), style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }
        }
        if (checked) {
            Icon(Icons.Filled.Check, contentDescription = "Selected", tint = AccentGreen)
        }
    }
}

/**
 * A group's details: who is in it, who can be added, and how to leave.
 *
 * Every membership change rotates the epoch key (adding as well as removing), which is what keeps
 * "left the group" meaningful and stops a new member from reading the backlog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoScreen(controller: PingController, groupId: String, onBack: () -> Unit, onLeft: () -> Unit) {
    val group by controller.group(groupId).collectAsState(initial = null)
    val contacts by controller.contacts().collectAsState(initial = null)
    val me by controller.myHash().collectAsState(initial = null)
    var confirmLeave by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }

    val record = group
    val names = contacts.orEmpty().associate { it.hash to it.displayName.ifBlank { it.hash.take(8) } }
    val members = record?.members.orEmpty()
    val addable = contacts.orEmpty().filter { it.hash !in members }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                },
                title = { Text(record?.name ?: "Group") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text(
                    "Sender-key encryption · epoch ${record?.epoch ?: 1} · every membership change " +
                        "rotates the key",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
            Spacer(Modifier.height(12.dp))
            Text("Members (${members.size})", style = MaterialTheme.typography.titleSmall)
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(members, key = { it }) { hash ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        InitialsAvatar(name = names[hash]?.take(2) ?: hash.take(2))
                        Spacer(Modifier.size(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (hash == me) "${names[hash] ?: hash.take(8)} (you)" else names[hash] ?: hash.take(8),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                        if (hash != me) {
                            IconButton(onClick = { controller.removeGroupMember(groupId, hash) }) {
                                Icon(
                                    Icons.Filled.PersonRemove,
                                    contentDescription = "Remove",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
            if (addable.isNotEmpty()) {
                Button(
                    onClick = { adding = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) { Text("Add a member") }
            }
            TextButton(
                onClick = { confirmLeave = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Text("Leave group", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (adding) {
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add a member") },
            text = {
                Column {
                    Text(
                        "Everybody gets a new key, so the newcomer cannot read earlier messages.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn {
                        items(addable, key = { it.hash }) { contact ->
                            ContactPickRow(
                                contact = contact,
                                checked = false,
                                onClick = {
                                    controller.addGroupMember(groupId, contact.hash)
                                    adding = false
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { adding = false }) { Text("Done") }
            }
        )
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave ${record?.name ?: "this group"}?") },
            text = {
                Text(
                    "The others rotate the key without you, so you stop receiving new messages. " +
                        "The conversation and its history are deleted from this phone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    controller.leaveGroup(groupId)
                    onLeft()
                }) { Text("Leave", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false }) { Text("Stay") }
            }
        )
    }
}

/** Small round initials avatar for the group screens. */
@Composable
private fun InitialsAvatar(name: String, tint: Color = PrimaryPurple) {
    Column(
        modifier = Modifier
            .size(38.dp)
            .background(tint.copy(alpha = 0.18f), CircleShape),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            name.trim().take(2).uppercase().ifBlank { "?" },
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = tint
        )
    }
}
