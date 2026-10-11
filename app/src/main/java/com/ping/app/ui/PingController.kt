package com.ping.app.ui

import android.net.Uri
import com.ping.app.core.crypto.SafetyNumber
import com.ping.app.core.protocol.ContactCard
import com.ping.app.core.protocol.InviteCode
import com.ping.app.data.AttachmentRecord
import com.ping.app.data.ChatMessage
import com.ping.app.data.Contact
import com.ping.app.data.Conversation
import com.ping.app.data.GroupRecord
import com.ping.app.data.SettingsSnapshot
import com.ping.app.di.AppGraph
import com.ping.app.di.StorageState
import com.ping.app.engine.EngineState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One row in the conversation list. */
data class ConversationView(
    val conversation: Conversation,
    val contact: Contact?,
    val displayName: String,
    val typing: Boolean,
    val group: GroupRecord? = null
) {
    val peerHash: String get() = conversation.peerHash
}

/** Safety number plus everything the user needs to check it. */
data class VerificationView(
    val number: String,
    val verified: Boolean,
    val ownFingerprint: String,
    val theirFingerprint: String
)

/**
 * Sits between the screens and the engine.
 *
 * Screens are dumb: they render flows and call one of these methods. Everything here is
 * fire-and-forget, because the interesting state lives in the database and is re-published
 * through [AppGraph.revision].
 */
class PingController(val graph: AppGraph) {

    val scope: CoroutineScope = graph.scope

    val storage: StateFlow<StorageState> = graph.storage
    val settings: StateFlow<SettingsSnapshot> = graph.settings.state
    val engineState: StateFlow<EngineState> = graph.engine.state
    val typing: StateFlow<Map<String, Long>> = graph.typing
    val notes: StateFlow<List<String>> = graph.toastMessages

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private fun <T> run(block: suspend () -> T) {
        scope.launch {
            _busy.value = true
            try {
                block()
            } catch (t: Throwable) {
                _lastError.value = t.message ?: "Something went wrong"
            } finally {
                _busy.value = false
            }
        }
    }

    fun clearError() {
        _lastError.value = null
    }

    // ------------------------------------------------------------------ onboarding

    val onboardingComplete: Boolean get() = settings.value.onboardingComplete

    fun completeOnboarding(displayName: String, relayUrl: String, allowInsecure: Boolean, useLan: Boolean) =
        run {
            graph.awaitStorage()
            graph.onDb { graph.identity.create(displayName.ifBlank { "Ping user" }) }
            graph.settings.setDisplayName(displayName.ifBlank { "Ping user" })
            graph.settings.setRelayUrl(relayUrl)
            graph.settings.setAllowInsecureRelay(allowInsecure)
            graph.settings.setLocalNetworkEnabled(useLan)
            graph.settings.setOnboardingComplete(true)
            graph.settings.setDarkTheme(true)
            graph.bumpRevision()
            graph.engine.start()
            graph.engine.register()
        }

    fun myCard(): ContactCard? = graph.identity.contactCard()

    fun myInviteCode(): String? = myCard()?.let { InviteCode.encode(it) }

    fun myShortCode(): String {
        val identity = graph.identity.keyPair() ?: return ""
        return com.ping.app.core.crypto.Bytes.toBase64Url(
            com.ping.app.core.protocol.Wire.mailboxId(identity.dh.publicKey)
        )
    }

    fun renameSelf(name: String) = run {
        graph.identity.updateDisplayName(name.trim())
        graph.bumpRevision()
    }

    // ------------------------------------------------------------------ lists

    fun conversations(): StateFlow<List<ConversationView>?> =
        graph.live("conversations") { conversationViews() }

    private suspend fun conversationViews(): List<ConversationView> {
        val now = System.currentTimeMillis()
        val typingNow = graph.typing.value
        return graph.onDb {
            graph.store.conversations().map { conversation ->
                val contact = graph.store.contact(conversation.peerHash)
                val group = conversation.groupId?.let { graph.store.group(it) }
                ConversationView(
                    conversation = conversation,
                    contact = contact,
                    displayName = when {
                        group != null -> group.name
                        contact?.displayName?.isNotBlank() == true -> contact.displayName
                        contact != null -> contact.hash.take(8)
                        else -> "Unknown"
                    },
                    // A group has no single peer, so there is nobody to show "typing" for.
                    typing = group == null && (now - (typingNow[conversation.peerHash] ?: 0L)) < 6_000,
                    group = group
                )
            }
        }
    }

    fun totalUnread(): StateFlow<Int?> =
        graph.live("totalUnread") { graph.onDb { graph.store.totalUnread() } }

    /** Turns a peer hash (from a deep link or a notification) into a conversation. */
    fun resolveConversation(peerHash: String, onReady: (Conversation?) -> Unit) {
        scope.launch {
            graph.awaitStorage()
            val conversation = graph.onDb { graph.store.ensureConversation(peerHash) }
            graph.bumpRevision()
            onReady(conversation)
        }
    }

    /** The conversation of a group, so a freshly created group can be opened. */
    fun resolveGroupConversation(groupId: String, onReady: (String?) -> Unit) {
        scope.launch {
            graph.awaitStorage()
            val conversation = graph.onDb { graph.store.ensureGroupConversation(groupId) }
            graph.bumpRevision()
            onReady(conversation.id)
        }
    }

    fun messages(conversationId: String): StateFlow<List<ChatMessage>?> =
        graph.live("messages:$conversationId") { graph.onDb { graph.store.messages(conversationId) } }

    fun conversation(conversationId: String): StateFlow<Conversation?> =
        graph.live("conversation:$conversationId") { graph.onDb { graph.store.conversation(conversationId) } }

    fun conversationForPeer(peerHash: String): StateFlow<Conversation?> =
        graph.live("conversationForPeer:$peerHash") { graph.onDb { graph.store.conversationForPeer(peerHash) } }

    fun contact(peerHash: String): StateFlow<Contact?> =
        graph.live("contact:$peerHash") { graph.onDb { graph.store.contact(peerHash) } }

    fun contacts(): StateFlow<List<Contact>?> =
        graph.live("contacts") { graph.onDb { graph.store.contacts() } }

    fun verification(peerHash: String): StateFlow<VerificationView?> = graph.live("verification:$peerHash") {
        graph.onDb {
            val mine = graph.identity.keyPair() ?: return@onDb null
            val theirs = graph.store.contact(peerHash) ?: return@onDb null
            VerificationView(
                number = SafetyNumber.compute(mine.signing.publicKey, theirs.signingKey),
                verified = theirs.verified,
                ownFingerprint = SafetyNumber.shortFingerprint(mine.signing.publicKey),
                theirFingerprint = SafetyNumber.shortFingerprint(theirs.signingKey)
            )
        }
    }

    fun attachment(attachmentId: String): StateFlow<AttachmentRecord?> =
        graph.live("attachment:$attachmentId") { graph.onDb { graph.cryptoStore.attachment(attachmentId) } }

    // ------------------------------------------------------------------ chat actions

    // ------------------------------------------------------------------ groups

    fun groups(): StateFlow<List<GroupRecord>?> = graph.live("groups") { graph.onDb { graph.store.groups() } }

    /** This device's own contact hash, for labeling your messages in a group. */
    fun myHash(): StateFlow<String?> = graph.live("myHash") { graph.engine.selfHash() }

    fun group(groupId: String): StateFlow<GroupRecord?> =
        graph.live("group:$groupId") { graph.onDb { graph.store.group(groupId) } }

    fun createGroup(name: String, memberHashes: List<String>) = run {
        graph.awaitStorage()
        val group = graph.engine.createGroup(name, memberHashes)
        graph.note(
            "Created \"${group.name}\" with ${group.memberCount} " +
                if (group.memberCount == 1) "member" else "members"
        )
        group
    }

    fun addGroupMember(groupId: String, contactHash: String) = run {
        graph.engine.addGroupMember(groupId, contactHash)
        graph.note("Added to the group — the key was rotated for everybody")
    }

    fun removeGroupMember(groupId: String, contactHash: String) = run {
        graph.engine.removeGroupMember(groupId, contactHash)
        graph.note("Removed — a fresh key keeps future messages out of reach")
    }

    fun leaveGroup(groupId: String) = run {
        graph.engine.leaveGroup(groupId)
        graph.note("You left the group")
    }

    fun sendGroupText(groupId: String, text: String) = run {
        graph.awaitStorage()
        graph.engine.sendGroupText(groupId, text)
    }

    fun sendText(peerHash: String, text: String) = run {
        graph.awaitStorage()
        graph.engine.sendText(peerHash, text)
        graph.typing.value = graph.typing.value - peerHash
    }

    fun sendAttachment(peerHash: String, uri: Uri, name: String, mime: String) = run {
        graph.awaitStorage()
        graph.engine.sendAttachment(peerHash, uri, name, mime)
    }

    fun retryMessage(messageId: String) = run { graph.engine.retryMessage(messageId) }

    fun deleteMessage(messageId: String) = run { graph.engine.deleteMessageForEveryone(messageId) }

    fun markRead(conversationId: String, peerHash: String) = run {
        graph.awaitStorage()
        graph.engine.markRead(peerHash, conversationId)
    }

    fun notifyTyping(peerHash: String) = run { graph.engine.sendTyping(peerHash, true) }

    fun stopTyping(peerHash: String) = run { graph.engine.sendTyping(peerHash, false) }

    fun loadAttachment(record: AttachmentRecord, onLoaded: (ByteArray?) -> Unit) {
        scope.launch { onLoaded(graph.engine.readAttachment(record)) }
    }

    // ------------------------------------------------------------------ contacts

    fun addContactByInvite(text: String) = run {
        graph.awaitStorage()
        val contact = graph.engine.addContactFromInvite(text)
        _lastError.value = null
        graph.note("Added ${contact.displayName}")
    }

    /** Accepts either a full invite (PING1…) or a short relay code. */
    fun addContact(text: String) = run {
        graph.awaitStorage()
        val trimmed = text.trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException("Nothing to add")
        if (InviteCode.looksLikeInviteCode(trimmed)) {
            val contact = graph.engine.addContactFromInvite(trimmed)
            graph.note("Added ${contact.displayName}")
        } else {
            graph.engine.addContactFromCode(trimmed)
            graph.note("Added a contact — check the safety number before trusting it")
        }
    }

    fun addContactByCode(code: String) = run {
        graph.awaitStorage()
        val contact = graph.engine.addContactFromCode(code)
        graph.note("Added a contact — check the safety number before trusting it")
        contact
    }

    fun setVerified(peerHash: String, verified: Boolean) = run { graph.engine.setVerified(peerHash, verified) }

    fun setBlocked(peerHash: String, blocked: Boolean) = run { graph.engine.setBlocked(peerHash, blocked) }

    fun renameContact(peerHash: String, name: String) = run { graph.engine.renameContact(peerHash, name) }

    fun setDisappearing(peerHash: String, seconds: Long) = run { graph.engine.setDisappearing(peerHash, seconds) }

    fun resetSession(peerHash: String) = run {
        graph.engine.resetSession(peerHash)
        graph.note("The next message starts a new session")
    }

    fun deleteConversation(conversation: Conversation) = run { graph.engine.deleteConversation(conversation) }

    // ------------------------------------------------------------------ settings

    fun setRelayUrl(url: String) = run {
        graph.settings.setRelayUrl(url)
        graph.engine.register()
        graph.engine.restartLan()
    }

    fun testRelay(url: String, allowInsecure: Boolean) = run {
        val problem = com.ping.app.engine.RelayClient.validateRelayUrl(url, allowInsecure)
        if (problem != null) throw IllegalArgumentException(problem)
        graph.settings.setRelayUrl(url)
        graph.settings.setAllowInsecureRelay(allowInsecure)
        val error = graph.engine.register()
        if (error != null) throw IllegalStateException(error)
        graph.note("Relay answered and accepted this device's keys")
    }

    fun setLanEnabled(enabled: Boolean) = run {
        graph.settings.setLocalNetworkEnabled(enabled)
        graph.engine.restartLan()
    }

    fun setNotifications(enabled: Boolean) = run { graph.settings.setNotificationsEnabled(enabled) }

    fun setNotificationPreview(enabled: Boolean) = run { graph.settings.setNotificationPreview(enabled) }

    fun setReadReceipts(enabled: Boolean) = run { graph.settings.setReadReceipts(enabled) }

    fun setTypingIndicators(enabled: Boolean) = run { graph.settings.setTypingIndicators(enabled) }

    fun setScreenshotGuard(enabled: Boolean) = run { graph.settings.setScreenshotGuard(enabled) }

    fun setAppLock(enabled: Boolean) = run { graph.settings.setAppLock(enabled) }

    fun setAllowInsecureRelay(enabled: Boolean) = run { graph.settings.setAllowInsecureRelay(enabled) }

    fun setKeepRelayAwake(enabled: Boolean) = run { graph.settings.setKeepRelayAwake(enabled) }

    fun defaultDisappearing(): Long = settings.value.defaultDisappearingSeconds

    fun setDefaultDisappearing(seconds: Long) = run { graph.settings.setDefaultDisappearing(seconds) }

    fun storageSummary(): StateFlow<String?> = graph.live("storageSummary") {
        graph.onDb {
            val messages = graph.store.messageCount()
            val attachments = graph.cryptoStore.attachmentCount()
            val sessions = graph.cryptoStore.sessionCount()
            val bytes = com.ping.app.data.dbFileSize(graph.context)
            val attachBytes = graph.engine.attachmentBytes()
            "$messages messages · $attachments files · $sessions encrypted sessions\n" +
                "database ${bytes / 1024} KB · attachments ${attachBytes / 1024} KB"
        }
    }

    /** Deletes everything: messages, keys, contact list and the database file itself. */
    fun panicWipe() = run {
        graph.engine.stop()
        graph.onDb { graph.store.wipeEverything() }
        graph.vault.destroy()
        graph.settings.reset()
        runCatching { graph.onDb { graph.cryptoStore.sessions().forEach { graph.sessions.forget(it.peerHash) } } }
        graph.bumpRevision()
        graph.settings.setOnboardingComplete(false)
    }
}
