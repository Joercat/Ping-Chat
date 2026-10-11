package com.ping.app.engine

import android.net.Uri
import com.ping.app.core.crypto.Aead
import com.ping.app.core.crypto.AttachmentCrypto
import com.ping.app.core.crypto.AuthenticationFailedException
import com.ping.app.core.crypto.Bytes
import com.ping.app.core.crypto.IdentityKeyPair
import com.ping.app.core.crypto.PreKeyBundle
import com.ping.app.core.crypto.RatchetSession
import com.ping.app.core.protocol.BundleCodec
import com.ping.app.core.protocol.ContactCard
import com.ping.app.core.protocol.InviteCode
import com.ping.app.core.protocol.RelayApi
import com.ping.app.core.protocol.Wire
import com.ping.app.data.AttachmentRecord
import com.ping.app.data.ChatMessage
import com.ping.app.data.Contact
import com.ping.app.data.Conversation
import com.ping.app.data.MessageStatus
import com.ping.app.di.AppGraph
import com.ping.app.di.StorageState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/** What the user can see about the connection. */
data class EngineState(
    val relayConfigured: Boolean = false,
    val relayReachable: Boolean = false,
    val lastError: String? = null,
    val lanPeers: Int = 0,
    val queued: Int = 0,
    val lastSyncAt: Long = 0L
)

/**
 * The heart of the app: it owns sending, receiving and everything in between.
 *
 * A message exists in exactly two places — encrypted on the sender's device and encrypted on the
 * receiver's device. The relay only ever sees sealed envelopes, and only until they are collected.
 */
class Engine(private val graph: AppGraph) {

    private val settings get() = graph.settings
    private val store get() = graph.store
    private val cryptoStore get() = graph.cryptoStore

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    /** Serialises anything that touches ratchet state, so two messages cannot race. */
    private val sending = Mutex()
    private val receiving = Mutex()

    private var job: Job = SupervisorJob()
    private var scope = CoroutineScope(Dispatchers.Default + job)
    private var running = false

    /** Nonce handed out by the relay; reused for the next authenticated call. */
    @Volatile
    private var pollNonce: ByteArray? = null

    companion object {
        const val MAX_OUTBOX_ATTEMPTS = 6
        private const val POLL_WAIT_MS = RelayApi.DEFAULT_POLL_WAIT_MS
        private const val PREKEY_REPUBLISH_MS = 6 * 60 * 60 * 1000L
    }

    // ------------------------------------------------------------------ lifecycle

    fun start() {
        if (running) return
        running = true
        job = SupervisorJob()
        scope = CoroutineScope(Dispatchers.Default + job)
        if (settings.current.localNetworkEnabled) startLan()
        scope.launch { pollLoop() }
        scope.launch { outboxLoop() }
        scope.launch { maintenanceLoop() }
        scope.launch { typingResetLoop() }
    }

    fun stop() {
        running = false
        job.cancel()
        try {
            graph.lan.stop()
        } catch (t: Throwable) {
            // never started
        }
    }

    /** Called once the database is open, to publish fresh pre-keys and drain the outbox. */
    suspend fun onStorageReady() {
        if (!graph.identity.hasIdentity()) return
        graph.onDb {
            cryptoStore.pruneConsumedPreKeys()
            store.expireMessages(System.currentTimeMillis())
        }
        graph.bumpRevision()
        refreshPreKeys()
        if (graph.relay.isConfigured()) {
            background { register() }
            background { flushOutbox() }
        }
    }

    private suspend fun <T> background(block: suspend () -> T): T = withContext(Dispatchers.Default) { block() }

    private fun update(transform: (EngineState) -> EngineState) {
        _state.value = transform(_state.value)
    }

    // ------------------------------------------------------------------ local network

    private fun startLan() {
        val identity = graph.identity.keyPair() ?: return
        val mailbox = Wire.mailboxId(identity.dh.publicKey)
        try {
            graph.lan.start(mailbox)
        } catch (t: Throwable) {
            graph.note("Local network discovery is unavailable")
        }
        scope.launch {
            graph.lan.peers.collect { peers -> update { it.copy(lanPeers = peers.size) } }
        }
    }

    fun restartLan() {
        try {
            graph.lan.stop()
        } catch (t: Throwable) {
            // not running
        }
        if (settings.current.localNetworkEnabled) startLan()
    }

    // ------------------------------------------------------------------ relay

    /** Publishes this device's bundle (and any fresh one-time pre-keys). */
    suspend fun register(): String? {
        val identity = graph.identity.keyPair() ?: return null
        if (!graph.relay.isConfigured()) {
            update { it.copy(relayConfigured = false, relayReachable = false, lastError = null) }
            return null
        }
        return try {
            val unpublished = graph.onDb {
                graph.identity.refreshPreKeys()
                cryptoStore.unpublishedPreKeys(200)
            }
            val signedPreKey = graph.onDb { graph.identity.ensureSignedPreKey() }
                ?: return "Ping has no identity yet"
            graph.relay.register(
                signingKey = identity.signing.publicKey,
                dhIdentityKey = identity.dh.publicKey,
                dhBinding = identity.dhBinding,
                signedPreKeyId = signedPreKey.id,
                signedPreKey = signedPreKey.keyPair.publicKey,
                signedPreKeySignature = signedPreKey.signature,
                oneTimePreKeys = unpublished
            )
            graph.onDb { cryptoStore.markPreKeysPublished(unpublished.map { it.first }) }
            settings.setPreKeyPublication(System.currentTimeMillis(), signedPreKey.id)
            update { it.copy(relayConfigured = true, relayReachable = true, lastError = null) }
            null
        } catch (t: Throwable) {
            val message = t.message ?: "relay unavailable"
            update { it.copy(relayConfigured = true, relayReachable = false, lastError = message) }
            message
        }
    }

    /** Publishes freshly generated one-time pre-keys without a full re-registration. */
    private suspend fun refreshPreKeys() {
        val identity = graph.identity.keyPair() ?: return
        val changed = graph.onDb {
            val fresh = graph.identity.refreshPreKeys()
            fresh || cryptoStore.unpublishedPreKeyCount() > 0
        }
        if (!changed || !graph.relay.isConfigured()) return
        val mailbox = Wire.mailboxId(identity.dh.publicKey)
        try {
            val nonce = pollNonce ?: graph.relay.challenge(mailbox).also { pollNonce = it }
            val unpublished = graph.onDb { cryptoStore.unpublishedPreKeys(100) }
            if (unpublished.isEmpty()) return
            pollNonce = graph.relay.refreshPreKeys(identity, mailbox, nonce, unpublished)
            graph.onDb { cryptoStore.markPreKeysPublished(unpublished.map { it.first }) }
        } catch (t: Throwable) {
            // The next registration round will carry them instead.
            pollNonce = null
        }
    }

    /** Long-polls the relay, processes what arrives and acknowledges exactly what it processed. */
    private suspend fun pollLoop() {
        var backoff = 1_000L
        while (scope.isActive && running) {
            val identity = graph.identity.keyPair()
            if (identity == null || !graph.relay.isConfigured()) {
                update { it.copy(relayConfigured = graph.relay.isConfigured()) }
                delay(5_000)
                continue
            }
            val mailbox = Wire.mailboxId(identity.dh.publicKey)
            try {
                val result = graph.relay.poll(identity, mailbox, POLL_WAIT_MS, pollNonce)
                pollNonce = result.nextNonce ?: pollNonce
                val ids = ArrayList<String>(result.envelopes.size)
                for (polled in result.envelopes) {
                    val envelope = try {
                        polled.decode()
                    } catch (t: Throwable) {
                        // Unparseable: acknowledge it so it does not come back forever.
                        ids.add(polled.id)
                        continue
                    }
                    val id = handleEnvelope(envelope, polled.id)
                    ids.add(id ?: polled.id)
                }
                if (ids.isNotEmpty()) {
                    val nonce = pollNonce
                    if (nonce != null) {
                        try {
                            graph.relay.ack(mailbox, identity, ids, nonce)
                        } catch (t: Throwable) {
                            // The relay will hand the same envelopes out again; the ratchet is
                            // idempotent against duplicates, so this is harmless.
                        }
                    }
                }
                backoff = 1_000L
                update { it.copy(relayReachable = true, lastError = null, lastSyncAt = System.currentTimeMillis()) }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                pollNonce = null
                update { it.copy(relayReachable = false, lastError = t.message ?: "relay unreachable") }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000L)
            }
        }
    }

    // ------------------------------------------------------------------ outbox

    private suspend fun outboxLoop() {
        while (scope.isActive && running) {
            try {
                flushOutbox()
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                // keep looping: the next round may work
            }
            delay(3_000)
        }
    }

    suspend fun flushOutbox() {
        if (!graph.identity.hasIdentity()) return
        val due = graph.onDb { cryptoStore.dueOutbox(System.currentTimeMillis()) }
        for (entry in due) {
            val contact = graph.onDb { store.contact(entry.peerHash) }
            if (contact == null) {
                graph.onDb { cryptoStore.removeOutbox(entry.id) }
                continue
            }
            val payload = try {
                Wire.Payload.decode(entry.payload)
            } catch (t: Throwable) {
                graph.onDb { cryptoStore.removeOutbox(entry.id) }
                continue
            }
            val delivered = try {
                deliver(contact, payload)
            } catch (t: Throwable) {
                false
            }
            if (delivered) {
                graph.onDb { cryptoStore.removeOutbox(entry.id) }
                markSent(entry.id, System.currentTimeMillis())
            } else {
                val attempts = entry.attempts + 1
                if (attempts >= MAX_OUTBOX_ATTEMPTS) {
                    graph.onDb {
                        cryptoStore.removeOutbox(entry.id)
                        // The far side may have lost its state: start over with a fresh handshake
                        // instead of retrying forever against a session nobody can decrypt.
                        cryptoStore.deleteSession(entry.peerHash)
                    }
                    markFailed(entry.id)
                } else {
                    val wait = (2_000L shl attempts).coerceAtMost(10 * 60_000L)
                    graph.onDb {
                        cryptoStore.rescheduleOutbox(entry.id, attempts, System.currentTimeMillis() + wait, "offline")
                    }
                }
            }
        }
        val queued = graph.onDb { cryptoStore.outboxCount() }
        update { it.copy(queued = queued) }
    }

    // ------------------------------------------------------------------ maintenance

    private suspend fun maintenanceLoop() {
        while (scope.isActive && running) {
            delay(30_000)
            try {
                val now = System.currentTimeMillis()
                val expired = graph.onDb { store.expireMessages(now) }
                if (expired.isNotEmpty()) {
                    for (id in expired) {
                        val attachmentId = graph.onDb { store.message(id)?.attachmentId }
                        if (attachmentId != null) graph.attachments.delete(attachmentId)
                    }
                    graph.bumpRevision()
                }
                graph.onDb { cryptoStore.pruneConsumedPreKeys() }
                if (now - settings.current.lastPreKeyPublish > PREKEY_REPUBLISH_MS) register()
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                // keep looping
            }
        }
    }

    /** Typing indicators fade out on their own after a few seconds. */
    private suspend fun typingResetLoop() {
        while (scope.isActive && running) {
            delay(2_000)
            val now = System.currentTimeMillis()
            val current = graph.typing.value
            val pruned = current.filterValues { now - it < 8_000 }
            if (pruned.size != current.size) graph.typing.value = pruned
        }
    }

    // ------------------------------------------------------------------ bundle handling

    /**
     * The bundle handed to a peer on the local network: same contents as the one the relay
     * serves, so the X3DH handshake is identical whichever path it took.
     */
    suspend fun localBundleBytes(): ByteArray? {
        val identity = graph.identity.keyPair() ?: return null
        return bundling.withLock {
            graph.onDb {
                val signedPreKey = graph.identity.ensureSignedPreKey() ?: return@onDb null
                // Hand out exactly one one-time pre-key and mark it as given away: it stays
                // available for the incoming handshake, but nobody else gets the same one.
                val next = cryptoStore.unpublishedPreKeys(1).firstOrNull()
                var oneTimeId: Int? = null
                var oneTimeKey: ByteArray? = null
                if (next != null) {
                    // Handed out once: marked as published so no other peer gets the same key, but
                    // still usable for the handshake that is about to arrive.
                    cryptoStore.markPreKeysPublished(listOf(next.first))
                    oneTimeId = next.first
                    oneTimeKey = next.second
                }
                BundleCodec.encode(
                    PreKeyBundle(
                        signingKey = identity.signing.publicKey,
                        dhIdentityKey = identity.dh.publicKey,
                        dhBindingSignature = identity.dhBinding,
                        signedPreKeyId = signedPreKey.id,
                        signedPreKey = signedPreKey.keyPair.publicKey,
                        signedPreKeySignature = signedPreKey.signature,
                        oneTimePreKeyId = oneTimeId,
                        oneTimePreKey = oneTimeKey,
                        publishedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    private val bundling = Mutex()

    // ------------------------------------------------------------------ contacts

    suspend fun addContactFromInvite(text: String): Contact = addContactFromCard(InviteCode.decode(text))

    suspend fun addContactFromCard(card: ContactCard): Contact {
        if (!card.verify()) throw IllegalArgumentException("That invite is not signed correctly")
        val existing = graph.onDb { store.contactBySigningKey(card.signingKey) }
        if (existing != null) {
            val updated = existing.copy(
                // A name the user verified by hand is never overwritten by whatever arrives later.
                displayName = if (existing.verified) existing.displayName else card.displayName.ifBlank { existing.displayName },
                mailbox = card.mailbox,
                dhKey = card.dhIdentityKey,
                relayUrl = card.relayUrl.ifBlank { existing.relayUrl }
            )
            graph.onDb { store.upsertContact(updated); store.ensureConversation(updated.hash) }
            graph.bumpRevision()
            return updated
        }
        val contact = Contact(
            hash = Bytes.toHex(Bytes.sha256(card.signingKey).copyOfRange(0, 16)),
            signingKey = card.signingKey,
            dhKey = card.dhIdentityKey,
            displayName = card.displayName.ifBlank { "New contact" },
            mailbox = card.mailbox,
            relayUrl = card.relayUrl,
            addedAt = System.currentTimeMillis()
        )
        graph.onDb { store.upsertContact(contact); store.ensureConversation(contact.hash) }
        graph.bumpRevision()
        return contact
    }

    /**
     * Adds somebody from a short code by asking the relay for their public bundle.
     *
     * The bundle proves the two identity keys belong together, but nothing proves the relay handed
     * over the *right* person: that is what the safety number is for, and why new contacts start
     * out unverified.
     */
    suspend fun addContactFromCode(code: String): Contact {
        val cleaned = code.trim()
            .removePrefix("ping://")
            .removePrefix("ping:")
            .replace(" ", "")
            .replace("\n", "")
        val raw = try {
            Bytes.fromBase64Url(cleaned)
        } catch (t: IllegalArgumentException) {
            throw IllegalArgumentException("That does not look like a Ping code")
        }
        val mailbox = when (raw.size) {
            16 -> raw
            32 -> Wire.mailboxId(raw)
            else -> throw IllegalArgumentException("A Ping code is 16 or 32 bytes of base64url")
        }
        if (!graph.relay.isConfigured()) throw IllegalStateException("Add a relay address first")
        val bundle = graph.relay.fetchBundle(mailbox)
            ?: throw IllegalStateException("Nobody is registered under that code yet")
        return addContactFromBundle(bundle, mailbox)
    }

    private suspend fun addContactFromBundle(bundle: PreKeyBundle, mailbox: ByteArray): Contact {
        val existing = graph.onDb { store.contactBySigningKey(bundle.signingKey) }
        if (existing != null) return existing
        val contact = Contact(
            hash = Bytes.toHex(Bytes.sha256(bundle.signingKey).copyOfRange(0, 16)),
            signingKey = bundle.signingKey,
            dhKey = bundle.dhIdentityKey,
            displayName = "New contact",
            mailbox = mailbox,
            relayUrl = settings.current.relayUrl,
            addedAt = System.currentTimeMillis()
        )
        graph.onDb { store.upsertContact(contact); store.ensureConversation(contact.hash) }
        graph.bumpRevision()
        return contact
    }

    /** Marks a contact as verified after the safety numbers matched out of band. */
    suspend fun setVerified(peerHash: String, verified: Boolean) {
        graph.onDb { store.setContactVerified(peerHash, verified) }
        graph.bumpRevision()
    }

    suspend fun setBlocked(peerHash: String, blocked: Boolean) {
        graph.onDb { store.setContactBlocked(peerHash, blocked) }
        if (blocked) graph.onDb { cryptoStore.deleteSession(peerHash) }
        graph.bumpRevision()
    }

    suspend fun renameContact(peerHash: String, name: String) {
        graph.onDb { store.renameContact(peerHash, name.trim().take(Wire.MAX_NAME_BYTES)) }
        graph.bumpRevision()
    }

    suspend fun setDisappearing(peerHash: String, seconds: Long) {
        val conversation = graph.onDb { store.ensureConversation(peerHash) }
        graph.onDb { store.setConversationDisappearing(conversation.id, seconds) }
        graph.bumpRevision()
        // The peer should follow the same policy, so the setting travels inside the ratchet.
        val contact = graph.onDb { store.contact(peerHash) } ?: return
        sendEphemeral(contact.hash, Wire.Payload.ConversationSettings(seconds))
    }

    /** Forgets the ratchet with one contact; the next message starts a fresh handshake. */
    suspend fun resetSession(peerHash: String) {
        graph.onDb { cryptoStore.deleteSession(peerHash) }
        graph.bumpRevision()
    }

    // ------------------------------------------------------------------ sending

    suspend fun sendText(peerHash: String, text: String, replyToId: String? = null): ChatMessage {
        graph.identity.keyPair() ?: throw IllegalStateException("Ping has no identity yet")
        val trimmed = text.trim().take(Wire.MAX_TEXT_BYTES / 4)
        if (trimmed.isEmpty()) throw IllegalArgumentException("Nothing to send")
        val contact = graph.onDb { store.contact(peerHash) } ?: throw IllegalArgumentException("Unknown contact")
        if (contact.blocked) throw IllegalStateException("This contact is blocked")
        val conversation = graph.onDb { store.ensureConversation(peerHash) }
        val now = System.currentTimeMillis()
        val message = ChatMessage(
            id = Wire.randomEnvelopeId(),
            conversationId = conversation.id,
            peerHash = peerHash,
            outgoing = true,
            kind = 1,
            body = trimmed,
            replyToId = replyToId,
            createdAt = now,
            sentAt = null,
            deliveredAt = null,
            readAt = null,
            status = MessageStatus.PENDING,
            attachmentId = null,
            expiresAt = expiryFor(conversation.disappearingSeconds, now),
            editedAt = null,
            reactions = null
        )
        graph.onDb { store.insertMessage(message) }
        graph.bumpRevision()

        val payload = Wire.Payload.Text(trimmed, replyToId, now)
        val delivered = try {
            deliver(contact, payload)
        } catch (t: Throwable) {
            false
        }
        if (delivered) {
            markSent(message.id, System.currentTimeMillis())
        } else {
            graph.onDb { cryptoStore.enqueueOutbox(message.id, peerHash, conversation.id, payload.encode(), now) }
            val queued = graph.onDb { cryptoStore.outboxCount() }
            update { it.copy(queued = queued) }
        }
        return graph.onDb { store.message(message.id) } ?: message
    }

    suspend fun sendAttachment(peerHash: String, uri: Uri, displayName: String, mimeType: String): ChatMessage {
        val contact = graph.onDb { store.contact(peerHash) } ?: throw IllegalArgumentException("Unknown contact")
        if (contact.blocked) throw IllegalStateException("This contact is blocked")
        val conversation = graph.onDb { store.ensureConversation(peerHash) }
        graph.identity.keyPair() ?: throw IllegalStateException("Ping has no identity yet")
        val resolver = graph.context.contentResolver

        val transferId = Wire.randomTransferId()
        val fileKey = Bytes.random(32)
        val attachmentId = Wire.randomEnvelopeId()
        graph.attachments.delete(attachmentId)
        val file = graph.attachments.fileFor(attachmentId)

        // First pass: how big is it, and what does it hash to (the receiver checks this digest).
        val measured = withContext(Dispatchers.IO) {
            val stream = resolver.openInputStream(uri) ?: throw IllegalStateException("Cannot read that file")
            stream.use { measure(it) }
        }
        if (measured.size <= 0) throw IllegalStateException("That file is empty")
        val chunkCount = (measured.size + Wire.ATTACHMENT_CHUNK_BYTES - 1) / Wire.ATTACHMENT_CHUNK_BYTES

        // Second pass: encrypt chunk by chunk straight to disk. The plaintext never touches it.
        withContext(Dispatchers.IO) {
            val stream = resolver.openInputStream(uri) ?: throw IllegalStateException("Cannot read that file")
            stream.use { source ->
                java.io.RandomAccessFile(file, "rw").use { handle ->
                    val buffer = ByteArray(Wire.ATTACHMENT_CHUNK_BYTES)
                    var index = 0
                    while (true) {
                        val read = source.read(buffer)
                        if (read <= 0) break
                        val plain = if (read == buffer.size) buffer else buffer.copyOf(read)
                        val sealed = AttachmentCrypto.encryptChunk(fileKey, transferId, index, chunkCount, plain)
                        handle.seek(index.toLong() * (Wire.ATTACHMENT_CHUNK_BYTES + Aead.TAG_LEN))
                        handle.write(sealed)
                        index += 1
                    }
                }
            }
        }

        val now = System.currentTimeMillis()
        val messageId = Wire.randomEnvelopeId()
        val record = AttachmentRecord(
            id = attachmentId,
            transferId = Bytes.toBase64Url(transferId),
            messageId = messageId,
            conversationId = conversation.id,
            fileName = displayName.take(Wire.MAX_FILE_NAME_BYTES),
            mimeType = mimeType.take(Wire.MAX_MIME_BYTES),
            size = measured.size,
            chunkCount = chunkCount,
            sha256 = measured.sha256,
            fileKey = fileKey,
            outgoing = true,
            path = file.absolutePath,
            receivedChunks = chunkCount,
            status = 1
        )
        val message = ChatMessage(
            id = messageId,
            conversationId = conversation.id,
            peerHash = peerHash,
            outgoing = true,
            kind = 2,
            body = null,
            replyToId = null,
            createdAt = now,
            sentAt = null,
            deliveredAt = null,
            readAt = null,
            status = MessageStatus.PENDING,
            attachmentId = attachmentId,
            expiresAt = expiryFor(conversation.disappearingSeconds, now),
            editedAt = null,
            reactions = null
        )
        graph.onDb { cryptoStore.insertAttachment(record); store.insertMessage(message) }
        graph.bumpRevision()

        background { sendAttachmentData(contact, record, file) }
        return message
    }

    private class Measured(val size: Int, val sha256: ByteArray)

    private fun measure(stream: InputStream): Measured {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(Wire.ATTACHMENT_CHUNK_BYTES)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            total += read
            if (total > Wire.MAX_ATTACHMENT_BYTES) {
                throw IllegalStateException("Attachments are limited to ${Wire.MAX_ATTACHMENT_BYTES / (1024 * 1024)} MB")
            }
            digest.update(buffer, 0, read)
        }
        return Measured(total, digest.digest())
    }

    /** Sends the manifest followed by every chunk, each one sealed by the ratchet as it goes. */
    private suspend fun sendAttachmentData(contact: Contact, record: AttachmentRecord, file: File) {
        val transferId = Bytes.fromBase64Url(record.transferId)
        var ok = try {
            deliver(
                contact,
                Wire.Payload.AttachmentMeta(
                    transferId = transferId,
                    fileName = record.fileName,
                    mimeType = record.mimeType,
                    size = record.size,
                    sha256 = record.sha256,
                    chunkCount = record.chunkCount,
                    fileKey = record.fileKey
                )
            )
        } catch (t: Throwable) {
            false
        }
        if (ok) {
            for (index in 0 until record.chunkCount) {
                val chunk = withContext(Dispatchers.IO) {
                    java.io.RandomAccessFile(file, "r").use { handle ->
                        handle.seek(index.toLong() * (Wire.ATTACHMENT_CHUNK_BYTES + Aead.TAG_LEN))
                        val buffer = ByteArray(Wire.ATTACHMENT_CHUNK_BYTES + Aead.TAG_LEN)
                        val read = handle.read(buffer)
                        if (read <= 0) ByteArray(0) else if (read == buffer.size) buffer else buffer.copyOf(read)
                    }
                }
                if (chunk.isEmpty()) {
                    ok = false
                    break
                }
                ok = try {
                    deliver(contact, Wire.Payload.AttachmentChunk(transferId, index, record.chunkCount, chunk))
                } catch (t: Throwable) {
                    false
                }
                if (!ok) break
            }
        }
        if (ok) markSent(record.messageId, System.currentTimeMillis()) else markFailed(record.messageId)
    }

    private fun expiryFor(disappearingSeconds: Long, now: Long): Long? =
        if (disappearingSeconds > 0) now + disappearingSeconds * 1000L else null

    private suspend fun markSent(messageId: String, at: Long) {
        graph.onDb { store.updateStatus(messageId, MessageStatus.SENT, "sent_at", at) }
        graph.bumpRevision()
    }

    private suspend fun markFailed(messageId: String) {
        graph.onDb { store.updateStatus(messageId, MessageStatus.FAILED) }
        graph.bumpRevision()
    }

    suspend fun retryMessage(messageId: String) {
        val message = graph.onDb { store.message(messageId) } ?: return
        val contact = graph.onDb { store.contact(message.peerHash) } ?: return
        graph.onDb { store.updateStatus(messageId, MessageStatus.PENDING) }
        graph.bumpRevision()
        val body = message.body ?: return
        val payload = Wire.Payload.Text(body, message.replyToId, System.currentTimeMillis())
        val delivered = try {
            deliver(contact, payload)
        } catch (t: Throwable) {
            false
        }
        if (delivered) {
            markSent(messageId, System.currentTimeMillis())
        } else {
            graph.onDb {
                cryptoStore.enqueueOutbox(
                    messageId, message.peerHash, message.conversationId, payload.encode(), System.currentTimeMillis()
                )
            }
        }
    }

    suspend fun deleteMessageForEveryone(messageId: String) {
        val message = graph.onDb { store.message(messageId) } ?: return
        graph.onDb { store.tombstoneMessage(messageId) }
        graph.bumpRevision()
        if (message.outgoing) {
            val contact = graph.onDb { store.contact(message.peerHash) } ?: return
            sendEphemeral(contact.hash, Wire.Payload.Delete(messageId))
        }
    }

    suspend fun editMessage(messageId: String, newText: String) {
        val message = graph.onDb { store.message(messageId) } ?: return
        if (!message.outgoing) return
        val text = newText.trim().take(Wire.MAX_TEXT_BYTES / 4)
        if (text.isEmpty()) return
        graph.onDb { store.editMessage(messageId, text, System.currentTimeMillis()) }
        graph.bumpRevision()
        val contact = graph.onDb { store.contact(message.peerHash) } ?: return
        sendEphemeral(contact.hash, Wire.Payload.Edit(messageId, text))
    }

    suspend fun react(messageId: String, emoji: String) {
        val message = graph.onDb { store.message(messageId) } ?: return
        graph.onDb { store.setReactions(messageId, emoji) }
        graph.bumpRevision()
        if (message.outgoing) return
        val contact = graph.onDb { store.contact(message.peerHash) } ?: return
        sendEphemeral(contact.hash, Wire.Payload.Reaction(messageId, emoji))
    }

    /** Best effort: typing indicators and receipts are not worth queueing. */
    suspend fun sendEphemeral(peerHash: String, payload: Wire.Payload) {
        val contact = graph.onDb { store.contact(peerHash) } ?: return
        if (contact.blocked) return
        try {
            deliver(contact, payload)
        } catch (t: Throwable) {
            // ignored on purpose
        }
    }

    suspend fun sendReceipt(peerHash: String, messageIds: List<String>, read: Boolean) {
        if (messageIds.isEmpty()) return
        if (read && !settings.current.readReceipts) return
        sendEphemeral(peerHash, Wire.Payload.Receipt(messageIds, read))
    }

    suspend fun sendTyping(peerHash: String, typing: Boolean) {
        if (!settings.current.typingIndicators) return
        sendEphemeral(peerHash, Wire.Payload.Typing(typing))
    }

    // ------------------------------------------------------------------ delivery

    private class SessionUse(
        val session: RatchetSession,
        val initiation: SessionManager.Started?,
        val bundle: PreKeyBundle?
    )

    /**
     * A live session with [contact]. The handshake happens here the first time, and it is redone
     * whenever a previous first message never made it out (the peer never saw that session).
     */
    private suspend fun ensureSession(identity: IdentityKeyPair, contact: Contact): SessionUse = sending.withLock {
        val record = graph.onDb { cryptoStore.session(contact.hash) }
        if (record != null) {
            if (record.handshakeSent) {
                val existing = graph.sessions.load(contact.hash)
                if (existing != null) return@withLock SessionUse(existing, null, null)
            } else {
                // A previous attempt at this conversation never reached the far side: throw the
                // half-started session away so the next message carries a complete handshake.
                graph.onDb { cryptoStore.deleteSession(contact.hash) }
            }
        }
        val bundle = fetchBundle(contact) ?: throw RelayException("This contact cannot be reached yet", "no_bundle")
        val started = graph.sessions.startOutbound(identity, bundle)
        graph.onDb { graph.sessions.save(contact.hash, started.session, contact.signingKey, handshakeSent = false) }
        SessionUse(started.session, started, bundle)
    }

    private suspend fun fetchBundle(contact: Contact): PreKeyBundle? {
        if (settings.current.localNetworkEnabled) {
            val peer = graph.lan.peerForDhKey(Bytes.toBase64Url(contact.dhKey))
            if (peer != null) {
                val encoded = graph.lan.fetchBundle(peer)
                if (encoded != null) {
                    try {
                        val bundle = BundleCodec.decode(encoded)
                        if (bundle.verify() && bundle.signingKey.contentEquals(contact.signingKey)) {
                            // Straight from the device: the relay is not involved at all.
                            return bundle
                        }
                    } catch (t: Throwable) {
                        // fall through to the relay
                    }
                }
            }
        }
        if (!graph.relay.isConfigured()) return null
        return graph.relay.fetchBundle(contact.mailbox)
    }

    /** Builds the envelope for [payload] and hands it to the best available transport. */
    private suspend fun deliver(contact: Contact, payload: Wire.Payload): Boolean {
        val identity = graph.identity.keyPair() ?: return false
        val use = ensureSession(identity, contact)
        val message = graph.sessions.encrypt(use.session, payload)

        val envelope = if (use.initiation != null && use.bundle != null) {
            val handshake = graph.sessions.buildHandshake(identity, use.initiation, use.bundle, message)
            Wire.Envelope(
                kind = Wire.EnvelopeKind.HANDSHAKE,
                mailbox = contact.mailbox,
                sessionTag = null,
                senderIdentity = identity.dh.publicKey,
                payload = Wire.pad(handshake.encode())
            )
        } else {
            Wire.Envelope(
                kind = Wire.EnvelopeKind.SEALED,
                mailbox = contact.mailbox,
                sessionTag = use.session.sessionTag(),
                senderIdentity = null,
                payload = Wire.pad(message.encode())
            )
        }

        var delivered = false
        if (settings.current.localNetworkEnabled) {
            val peer = graph.lan.peerForDhKey(Bytes.toBase64Url(contact.dhKey))
            if (peer != null) {
                delivered = withContext(Dispatchers.IO) { graph.lan.deliver(peer, envelope) }
            }
        }
        if (!delivered && graph.relay.isConfigured()) {
            graph.relay.send(envelope)
            delivered = true
        }

        // The ratchet advanced either way, so persist it; whether the far side has seen the
        // handshake decides if the next message repeats it.
        val handshakeSent = if (use.initiation != null) delivered else true
        graph.onDb { graph.sessions.save(contact.hash, use.session, contact.signingKey, handshakeSent) }
        if (delivered) graph.onDb { store.touchContact(contact.hash, System.currentTimeMillis()) }
        return delivered
    }

    // ------------------------------------------------------------------ receiving

    /**
     * Handles one envelope. The return value is the relay envelope id when it can be
     * acknowledged; null means "do not acknowledge" (the caller then acks by default).
     */
    suspend fun handleEnvelope(envelope: Wire.Envelope, envelopeId: String? = null): String? = receiving.withLock {
        when (envelope.kind) {
            Wire.EnvelopeKind.SEALED -> handleSealed(envelope)
            Wire.EnvelopeKind.HANDSHAKE -> handleHandshake(envelope)
        }
    }

    private suspend fun handleSealed(envelope: Wire.Envelope): String? {
        val tag = envelope.sessionTag ?: return null
        val record = graph.onDb { cryptoStore.sessionByTag(tag) } ?: return null
        val contact = graph.onDb { store.contact(record.peerHash) } ?: return null
        if (contact.blocked) return null
        val session = graph.sessions.load(record.peerHash) ?: return null
        val payload = try {
            graph.sessions.decrypt(session, Wire.decodeRatchet(Wire.unpad(envelope.payload)))
        } catch (e: AuthenticationFailedException) {
            return null
        } catch (t: Throwable) {
            return null
        }
        graph.onDb { graph.sessions.save(contact.hash, session, contact.signingKey, record.handshakeSent) }
        process(contact, payload)
        return null
    }

    private suspend fun handleHandshake(envelope: Wire.Envelope): String? {
        val identity = graph.identity.keyPair() ?: return null
        val handshake = try {
            Wire.HandshakeInit.decode(Wire.unpad(envelope.payload))
        } catch (t: Throwable) {
            return null
        }
        // The sender must prove the DH key they publish belongs to their signing key.
        if (!IdentityKeyPair.verifyBinding(
                handshake.senderSigningKey,
                handshake.senderDhIdentity,
                handshake.senderBindingSignature
            )
        ) {
            return null
        }
        var contact = graph.onDb { store.contactBySigningKey(handshake.senderSigningKey) }
        if (contact == null) contact = rememberUnknownSender(handshake)
        if (contact.blocked) return null

        val signedPreKey = graph.onDb { cryptoStore.signedPreKey(handshake.signedPreKeyId) } ?: return null
        val oneTimePreKey = handshake.oneTimePreKeyId?.let { id -> graph.onDb { cryptoStore.consumePreKey(id) } }
        val session = try {
            graph.sessions.completeInbound(
                identity = identity,
                handshake = handshake,
                signedPreKey = signedPreKey.keyPair,
                oneTimePreKey = oneTimePreKey
            )
        } catch (t: Throwable) {
            return null
        }
        val payload = try {
            graph.sessions.decrypt(session, handshake.firstMessage)
        } catch (e: AuthenticationFailedException) {
            return null
        } catch (t: Throwable) {
            return null
        }
        graph.onDb { graph.sessions.save(contact.hash, session, contact.signingKey, handshakeSent = false) }
        process(contact, payload)
        if (handshake.oneTimePreKeyId != null) refreshPreKeys()
        return null
    }

    /**
     * Somebody we have never met managed to reach this mailbox, which means they were given this
     * device's code. They are stored as an unverified contact so the conversation and the safety
     * number are available, but nothing about them is trusted until the user says so.
     */
    private suspend fun rememberUnknownSender(handshake: Wire.HandshakeInit): Contact {
        val contact = Contact(
            hash = Bytes.toHex(Bytes.sha256(handshake.senderSigningKey).copyOfRange(0, 16)),
            signingKey = handshake.senderSigningKey,
            dhKey = handshake.senderDhIdentity,
            displayName = "New contact",
            mailbox = Wire.mailboxId(handshake.senderDhIdentity),
            relayUrl = settings.current.relayUrl,
            addedAt = System.currentTimeMillis()
        )
        graph.onDb { store.upsertContact(contact); store.ensureConversation(contact.hash) }
        graph.bumpRevision()
        return contact
    }

    /** Everything that arrives inside the ratchet ends up here. */
    private suspend fun process(contact: Contact, payload: Wire.Payload) {
        val conversation = graph.onDb { store.ensureConversation(contact.hash) }
        when (payload) {
            is Wire.Payload.Text -> {
                val now = payload.sentAt.coerceAtMost(System.currentTimeMillis() + 60_000)
                val message = ChatMessage(
                    id = Wire.randomEnvelopeId(),
                    conversationId = conversation.id,
                    peerHash = contact.hash,
                    outgoing = false,
                    kind = 1,
                    body = payload.text,
                    replyToId = payload.replyToId,
                    createdAt = now,
                    sentAt = now,
                    deliveredAt = null,
                    readAt = null,
                    status = MessageStatus.DELIVERED,
                    attachmentId = null,
                    expiresAt = expiryFor(conversation.disappearingSeconds, now),
                    editedAt = null,
                    reactions = null
                )
                graph.onDb {
                    store.insertMessage(message)
                    store.incrementUnread(conversation.id)
                    store.touchContact(contact.hash, System.currentTimeMillis())
                }
                graph.bumpRevision()
                notifyMessage(conversation.id, contact.displayName, payload.text)
                val receipts = graph.onDb { store.undeliveredIncoming(conversation.id) }
                if (receipts.isNotEmpty()) {
                    graph.onDb {
                        for (id in receipts) {
                            store.updateStatus(id, MessageStatus.DELIVERED, "delivered_at", System.currentTimeMillis())
                        }
                    }
                    sendReceipt(contact.hash, receipts, read = false)
                }
            }
            is Wire.Payload.Receipt -> {
                for (id in payload.messageIds) {
                    val existing = graph.onDb { store.message(id) } ?: continue
                    if (!existing.outgoing) continue
                    if (payload.read) {
                        graph.onDb { store.updateStatus(id, MessageStatus.READ, "read_at", System.currentTimeMillis()) }
                    } else if (existing.status != MessageStatus.READ) {
                        graph.onDb { store.updateStatus(id, MessageStatus.DELIVERED, "delivered_at", System.currentTimeMillis()) }
                    }
                }
                graph.bumpRevision()
            }
            is Wire.Payload.Typing -> {
                graph.typing.value = graph.typing.value + (contact.hash to System.currentTimeMillis())
            }
            is Wire.Payload.Profile -> {
                if (!contact.verified && payload.displayName.isNotBlank()) {
                    graph.onDb { store.renameContact(contact.hash, payload.displayName.take(Wire.MAX_NAME_BYTES)) }
                    graph.bumpRevision()
                }
            }
            is Wire.Payload.AttachmentMeta -> {
                receiveAttachmentMeta(contact, conversation.id, payload, conversation.disappearingSeconds)
            }
            is Wire.Payload.AttachmentChunk -> receiveAttachmentChunk(contact, payload)
            is Wire.Payload.Delete -> {
                graph.onDb { store.tombstoneMessage(payload.messageId) }
                graph.bumpRevision()
            }
            is Wire.Payload.Edit -> {
                graph.onDb { store.editMessage(payload.messageId, payload.newText, payload.sentAt) }
                graph.bumpRevision()
            }
            is Wire.Payload.Reaction -> {
                graph.onDb { store.setReactions(payload.messageId, payload.emoji) }
                graph.bumpRevision()
            }
            is Wire.Payload.ConversationSettings -> {
                graph.onDb { store.setConversationDisappearing(conversation.id, payload.disappearingSeconds) }
                graph.bumpRevision()
            }
        }
    }

    private suspend fun receiveAttachmentMeta(
        contact: Contact,
        conversationId: String,
        meta: Wire.Payload.AttachmentMeta,
        disappearingSeconds: Long
    ) {
        val transferId = Bytes.toBase64Url(meta.transferId)
        if (graph.onDb { cryptoStore.attachmentByTransferId(transferId) } != null) return
        val attachmentId = Wire.randomEnvelopeId()
        val messageId = Wire.randomEnvelopeId()
        val now = System.currentTimeMillis()
        val record = AttachmentRecord(
            id = attachmentId,
            transferId = transferId,
            messageId = messageId,
            conversationId = conversationId,
            fileName = meta.fileName.take(Wire.MAX_FILE_NAME_BYTES),
            mimeType = meta.mimeType.take(Wire.MAX_MIME_BYTES),
            size = meta.size,
            chunkCount = meta.chunkCount,
            sha256 = meta.sha256,
            fileKey = meta.fileKey,
            outgoing = false,
            path = null,
            receivedChunks = 0,
            status = 0
        )
        val message = ChatMessage(
            id = messageId,
            conversationId = conversationId,
            peerHash = contact.hash,
            outgoing = false,
            kind = 2,
            body = meta.fileName,
            replyToId = null,
            createdAt = now,
            sentAt = now,
            deliveredAt = null,
            readAt = null,
            status = MessageStatus.DELIVERED,
            attachmentId = attachmentId,
            expiresAt = expiryFor(disappearingSeconds, now),
            editedAt = null,
            reactions = null
        )
        graph.onDb {
            cryptoStore.insertAttachment(record)
            store.insertMessage(message)
            store.incrementUnread(conversationId)
        }
        graph.bumpRevision()
        notifyMessage(conversationId, contact.displayName, "Sent you a file: ${meta.fileName}")
    }

    private suspend fun receiveAttachmentChunk(contact: Contact, chunk: Wire.Payload.AttachmentChunk) {
        val transferId = Bytes.toBase64Url(chunk.transferId)
        val record = graph.onDb { cryptoStore.attachmentByTransferId(transferId) } ?: return
        val file = graph.attachments.fileFor(record.id)
        val ok = withContext(Dispatchers.IO) {
            try {
                graph.attachments.writeIncomingChunk(
                    file = file,
                    transferId = transferId,
                    fileKey = record.fileKey,
                    index = chunk.index,
                    chunkCount = chunk.chunkCount,
                    ciphertext = chunk.data
                )
                true
            } catch (t: Throwable) {
                false
            }
        }
        if (!ok) return
        val received = maxOf(record.receivedChunks, chunk.index + 1)
        val complete = received >= record.chunkCount
        val verified = complete && withContext(Dispatchers.IO) {
            graph.attachments.verifyDigest(file, transferId, record.fileKey, record.chunkCount, record.sha256)
        }
        graph.onDb {
            when {
                complete && verified -> {
                    cryptoStore.setAttachmentPath(record.id, file.absolutePath)
                    cryptoStore.setAttachmentReceivedChunks(record.id, received, 1)
                    store.updateStatus(record.messageId, MessageStatus.DELIVERED, "delivered_at", System.currentTimeMillis())
                }
                complete -> {
                    cryptoStore.setAttachmentReceivedChunks(record.id, received, 2)
                    store.updateStatus(record.messageId, MessageStatus.FAILED)
                    graph.attachments.delete(record.id)
                }
                else -> cryptoStore.setAttachmentReceivedChunks(record.id, received, 0)
            }
        }
        graph.bumpRevision()
        if (complete && verified) {
            val receipts = graph.onDb { store.undeliveredIncoming(record.conversationId) }
            if (receipts.isNotEmpty()) sendReceipt(contact.hash, receipts, read = false)
        }
    }

    private suspend fun notifyMessage(conversationId: String, title: String, body: String) {
        if (!settings.current.notificationsEnabled) return
        if (graph.activeConversation.value == conversationId) return
        graph.notifications.notifyMessage(
            conversationId = conversationId,
            title = title,
            body = if (settings.current.notificationPreview) body else "New message"
        )
    }

    /** Marks incoming messages as read and tells the sender (unless read receipts are off). */
    suspend fun markRead(peerHash: String, conversationId: String) {
        val ids = graph.onDb {
            val pending = store.unseenIncoming(conversationId)
            store.markConversationRead(conversationId, System.currentTimeMillis())
            pending
        }
        graph.bumpRevision()
        if (ids.isNotEmpty()) sendReceipt(peerHash, ids, read = true)
        graph.notifications.clear(conversationId)
    }

    suspend fun deleteConversation(conversation: Conversation) {
        val messages = graph.onDb { store.messages(conversation.id, 10_000) }
        for (message in messages) {
            message.attachmentId?.let { graph.attachments.delete(it) }
        }
        graph.onDb { store.deleteConversation(conversation) }
        graph.notifications.clear(conversation.id)
        graph.bumpRevision()
    }

    suspend fun storageState(): StorageState = graph.storage.value

    /** Decrypts an attachment into memory for display. Never writes plaintext to disk. */
    suspend fun readAttachment(record: AttachmentRecord): ByteArray? {
        val file = graph.attachments.fileFor(record.id)
        if (!file.exists()) return null
        return withContext(Dispatchers.IO) {
            try {
                graph.attachments.readAll(
                    file = file,
                    transferId = record.transferId,
                    fileKey = record.fileKey,
                    chunkCount = record.chunkCount,
                    size = record.size
                )
            } catch (t: Throwable) {
                null
            }
        }
    }

    /** Number of bytes the ciphertext of every attachment occupies. */
    fun attachmentBytes(): Long = graph.attachments.totalBytes()
}
