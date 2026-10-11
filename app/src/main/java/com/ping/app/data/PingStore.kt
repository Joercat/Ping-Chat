package com.ping.app.data

import android.database.Cursor
import com.ping.app.core.crypto.BufReader
import com.ping.app.core.crypto.BufWriter
import com.ping.app.core.crypto.IdentityKeyPair
import com.ping.app.core.crypto.X25519KeyPair
import com.ping.app.core.crypto.Ed25519KeyPair
import java.util.UUID

/**
 * Everything that is not a session key: identity, contacts, conversations and messages.
 *
 * Columns holding personal data are sealed with [Vault] before they are written, on top of the
 * SQLCipher encryption of the file itself.
 */
class PingStore(private val db: Db, private val vault: Vault) {

    // ------------------------------------------------------------------ identity

    fun saveIdentity(identity: IdentityKeyPair, displayName: String, createdAt: Long) {
        val writer = BufWriter(256)
            .bytes(identity.signing.privateKey)
            .bytes(identity.signing.publicKey)
            .bytes(identity.dh.privateKey)
            .bytes(identity.dh.publicKey)
            .blob(identity.dhBinding)
            .string(displayName)
            .u64(createdAt)
            .toByteArray()
        db.exec("INSERT OR REPLACE INTO identity(id, sealed) VALUES(1, ?)", arrayOf<Any?>(vault.seal(writer)))
    }

    fun loadIdentity(): IdentityRecord? = db.queryOne("SELECT sealed FROM identity WHERE id = 1") { cursor ->
        val reader = BufReader(vault.open(cursor.getBlob(0)))
        val result = IdentityRecord(
            signingPrivateKey = reader.bytes(32),
            signingPublicKey = reader.bytes(32),
            dhPrivateKey = reader.bytes(32),
            dhPublicKey = reader.bytes(32),
            dhBinding = reader.blob(),
            displayName = reader.string(),
            createdAt = reader.u64()
        )
        reader.expectEnd()
        result
    }

    fun identityKeyPair(record: IdentityRecord): IdentityKeyPair = IdentityKeyPair(
        signing = Ed25519KeyPair(record.signingPrivateKey, record.signingPublicKey),
        dh = X25519KeyPair(record.dhPrivateKey, record.dhPublicKey)
    )

    fun updateIdentityDisplayName(name: String) {
        val existing = loadIdentity() ?: return
        saveIdentity(
            IdentityKeyPair(
                Ed25519KeyPair(existing.signingPrivateKey, existing.signingPublicKey),
                X25519KeyPair(existing.dhPrivateKey, existing.dhPublicKey)
            ),
            name,
            existing.createdAt
        )
    }

    // ------------------------------------------------------------------ contacts

    fun upsertContact(contact: Contact) {
        db.exec(
            """
            INSERT OR REPLACE INTO contacts
                (hash, signing_key, dh_key, display_name, mailbox, relay_url, added_at, verified, blocked, disappearing, last_seen_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)
            """.trimIndent(),
            arrayOf<Any?>(
                contact.hash,
                vault.seal(contact.signingKey),
                vault.seal(contact.dhKey),
                vault.sealString(contact.displayName),
                vault.seal(contact.mailbox),
                vault.sealString(contact.relayUrl),
                contact.addedAt,
                if (contact.verified) 1 else 0,
                if (contact.blocked) 1 else 0,
                contact.disappearingSeconds,
                contact.lastSeenAt
            )
        )
    }

    private fun cursorToContact(cursor: Cursor): Contact = Contact(
        hash = cursor.getString(0),
        signingKey = vault.open(cursor.getBlob(1)),
        dhKey = vault.open(cursor.getBlob(2)),
        displayName = cursor.getBlob(3)?.let { vault.openString(it) } ?: "",
        mailbox = cursor.getBlob(4)?.let { vault.open(it) } ?: ByteArray(0),
        relayUrl = cursor.getBlob(5)?.let { vault.openString(it) } ?: "",
        addedAt = cursor.getLong(6),
        verified = cursor.getInt(7) == 1,
        blocked = cursor.getInt(8) == 1,
        disappearingSeconds = cursor.getLong(9),
        lastSeenAt = if (cursor.isNull(10)) null else cursor.getLong(10)
    )

    private val contactColumns =
        "hash, signing_key, dh_key, display_name, mailbox, relay_url, added_at, verified, blocked, disappearing, last_seen_at"

    fun contacts(includeBlocked: Boolean = true): List<Contact> = db.query(
        "SELECT $contactColumns FROM contacts ${if (includeBlocked) "" else "WHERE blocked = 0"} ORDER BY added_at DESC"
    ) { cursorToContact(it) }

    fun contact(hash: String): Contact? = db.queryOne("SELECT $contactColumns FROM contacts WHERE hash = ?", arrayOf(hash)) {
        cursorToContact(it)
    }

    fun contactBySigningKey(signingKey: ByteArray): Contact? =
        contacts(includeBlocked = true).firstOrNull { it.signingKey.contentEquals(signingKey) }

    fun isBlocked(signingKey: ByteArray): Boolean = contactBySigningKey(signingKey)?.blocked == true

    fun setContactVerified(hash: String, verified: Boolean) =
        db.exec("UPDATE contacts SET verified = ? WHERE hash = ?", arrayOf<Any?>(if (verified) 1 else 0, hash))

    fun setContactBlocked(hash: String, blocked: Boolean) =
        db.exec("UPDATE contacts SET blocked = ? WHERE hash = ?", arrayOf<Any?>(if (blocked) 1 else 0, hash))

    fun setContactDisappearing(hash: String, seconds: Long) =
        db.exec("UPDATE contacts SET disappearing = ? WHERE hash = ?", arrayOf<Any?>(seconds, hash))

    fun renameContact(hash: String, displayName: String) =
        db.exec("UPDATE contacts SET display_name = ? WHERE hash = ?", arrayOf<Any?>(vault.sealString(displayName), hash))

    fun touchContact(hash: String, at: Long) =
        db.exec("UPDATE contacts SET last_seen_at = ? WHERE hash = ?", arrayOf<Any?>(at, hash))

    fun deleteContact(hash: String) = db.transaction {
        db.exec("DELETE FROM contacts WHERE hash = ?", arrayOf(hash))
        db.exec("DELETE FROM conversations WHERE peer_hash = ?", arrayOf(hash))
        db.exec("DELETE FROM messages WHERE peer_hash = ?", arrayOf(hash))
        db.exec("DELETE FROM sessions WHERE peer_hash = ?", arrayOf(hash))
        db.exec("DELETE FROM outbox WHERE peer_hash = ?", arrayOf(hash))
    }

    // ------------------------------------------------------------------ conversations

    private val conversationColumns =
        "id, peer_hash, created_at, last_message_at, unread, pinned, muted, archived, disappearing"

    private fun cursorToConversation(cursor: Cursor, preview: String = "", outgoing: Boolean = false) = Conversation(
        id = cursor.getString(0),
        peerHash = cursor.getString(1),
        createdAt = cursor.getLong(2),
        lastMessageAt = cursor.getLong(3),
        unreadCount = cursor.getInt(4),
        pinned = cursor.getInt(5) == 1,
        muted = cursor.getInt(6) == 1,
        archived = cursor.getInt(7) == 1,
        disappearingSeconds = cursor.getLong(8),
        preview = preview,
        lastMessageOutgoing = outgoing
    )

    /** Finds the direct conversation with a peer, creating it if needed. */
    fun ensureConversation(peerHash: String): Conversation = db.transaction {
        conversationForPeer(peerHash)?.let { return@transaction it }
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        db.exec(
            "INSERT INTO conversations(id, peer_hash, created_at, last_message_at) VALUES(?,?,?,?)",
            arrayOf<Any?>(id, peerHash, now, now)
        )
        Conversation(
            id = id,
            peerHash = peerHash,
            createdAt = now,
            lastMessageAt = now,
            unreadCount = 0,
            pinned = false,
            muted = false,
            archived = false,
            disappearingSeconds = 0L
        )
    }

    fun conversationForPeer(peerHash: String): Conversation? = db.queryOne(
        "SELECT $conversationColumns FROM conversations WHERE peer_hash = ? LIMIT 1", arrayOf(peerHash)
    ) { cursorToConversation(it) }

    fun conversation(id: String): Conversation? = db.queryOne(
        "SELECT $conversationColumns FROM conversations WHERE id = ?", arrayOf(id)
    ) { cursorToConversation(it) }

    /** Conversation list with a decrypted preview of the newest message. */
    fun conversations(includeArchived: Boolean = false): List<Conversation> {
        val conversations = db.query(
            "SELECT $conversationColumns FROM conversations " +
                (if (includeArchived) "" else "WHERE archived = 0 ") +
                "ORDER BY pinned DESC, last_message_at DESC"
        ) { cursorToConversation(it) }
        return conversations.map { conversation ->
            val last = lastMessage(conversation.id)
            conversation.copy(
                preview = last?.body ?: "",
                lastMessageOutgoing = last?.outgoing ?: false
            )
        }
    }

    fun setConversationPinned(id: String, pinned: Boolean) =
        db.exec("UPDATE conversations SET pinned = ? WHERE id = ?", arrayOf<Any?>(if (pinned) 1 else 0, id))

    fun setConversationMuted(id: String, muted: Boolean) =
        db.exec("UPDATE conversations SET muted = ? WHERE id = ?", arrayOf<Any?>(if (muted) 1 else 0, id))

    fun setConversationArchived(id: String, archived: Boolean) =
        db.exec("UPDATE conversations SET archived = ? WHERE id = ?", arrayOf<Any?>(if (archived) 1 else 0, id))

    fun setConversationDisappearing(id: String, seconds: Long) =
        db.exec("UPDATE conversations SET disappearing = ? WHERE id = ?", arrayOf<Any?>(seconds, id))

    fun clearUnread(id: String) = db.exec("UPDATE conversations SET unread = 0 WHERE id = ?", arrayOf(id))

    fun deleteConversation(conversation: Conversation) = db.transaction {
        db.exec("DELETE FROM conversations WHERE id = ?", arrayOf(conversation.id))
        db.exec("DELETE FROM messages WHERE conversation_id = ?", arrayOf(conversation.id))
    }

    fun totalUnread(): Int =
        db.queryOne("SELECT COALESCE(SUM(unread), 0) FROM conversations") { it.getInt(0) } ?: 0

    // ------------------------------------------------------------------ messages

    private val messageColumns =
        "id, conversation_id, peer_hash, outgoing, kind, body, reply_to, created_at, sent_at, delivered_at, read_at, status, attachment_id, expires_at, edited_at, reactions"

    private fun cursorToMessage(cursor: Cursor): ChatMessage = ChatMessage(
        id = cursor.getString(0),
        conversationId = cursor.getString(1),
        peerHash = cursor.getString(2),
        outgoing = cursor.getInt(3) == 1,
        kind = cursor.getInt(4),
        body = cursor.getBlob(5)?.let { vault.openString(it) },
        replyToId = cursor.getString(6),
        createdAt = cursor.getLong(7),
        sentAt = if (cursor.isNull(8)) null else cursor.getLong(8),
        deliveredAt = if (cursor.isNull(9)) null else cursor.getLong(9),
        readAt = if (cursor.isNull(10)) null else cursor.getLong(10),
        status = MessageStatus.entries.getOrElse(cursor.getInt(11)) { MessageStatus.PENDING },
        attachmentId = cursor.getString(12),
        expiresAt = if (cursor.isNull(13)) null else cursor.getLong(13),
        editedAt = if (cursor.isNull(14)) null else cursor.getLong(14),
        reactions = cursor.getBlob(15)?.let { vault.openString(it) }
    )

    fun insertMessage(message: ChatMessage) = db.transaction {
        db.exec(
            """
            INSERT OR REPLACE INTO messages
                (id, conversation_id, peer_hash, outgoing, kind, body, reply_to, created_at, sent_at,
                 delivered_at, read_at, status, attachment_id, expires_at, edited_at, reactions)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """.trimIndent(),
            arrayOf<Any?>(
                message.id,
                message.conversationId,
                message.peerHash,
                if (message.outgoing) 1 else 0,
                message.kind,
                message.body?.let { vault.sealString(it) },
                message.replyToId,
                message.createdAt,
                message.sentAt,
                message.deliveredAt,
                message.readAt,
                message.status.ordinal,
                message.attachmentId,
                message.expiresAt,
                message.editedAt,
                message.reactions?.let { vault.sealString(it) }
            )
        )
        db.exec(
            "UPDATE conversations SET last_message_at = ? WHERE id = ? AND last_message_at < ?",
            arrayOf<Any?>(message.createdAt, message.conversationId, message.createdAt)
        )
    }

    fun incrementUnread(conversationId: String) =
        db.exec("UPDATE conversations SET unread = unread + 1 WHERE id = ?", arrayOf(conversationId))

    fun messages(conversationId: String, limit: Int = 500): List<ChatMessage> = db.query(
        "SELECT $messageColumns FROM messages WHERE conversation_id = ? ORDER BY created_at ASC LIMIT ?",
        arrayOf<Any?>(conversationId, limit)
    ) { cursorToMessage(it) }

    fun lastMessage(conversationId: String): ChatMessage? = db.queryOne(
        "SELECT $messageColumns FROM messages WHERE conversation_id = ? ORDER BY created_at DESC LIMIT 1",
        arrayOf(conversationId)
    ) { cursorToMessage(it) }

    fun message(id: String): ChatMessage? = db.queryOne(
        "SELECT $messageColumns FROM messages WHERE id = ?", arrayOf(id)
    ) { cursorToMessage(it) }

    fun messageCount(conversationId: String): Int =
        db.queryOne("SELECT COUNT(*) FROM messages WHERE conversation_id = ?", arrayOf(conversationId)) { it.getInt(0) } ?: 0

    fun updateStatus(id: String, status: MessageStatus, timestampField: String? = null, timestamp: Long = 0L) {
        if (timestampField == null) {
            db.exec("UPDATE messages SET status = ? WHERE id = ?", arrayOf<Any?>(status.ordinal, id))
        } else {
            db.exec("UPDATE messages SET status = ?, $timestampField = ? WHERE id = ?", arrayOf<Any?>(status.ordinal, timestamp, id))
        }
    }

    fun markConversationRead(conversationId: String, timestamp: Long) = db.transaction {
        db.exec(
            "UPDATE messages SET status = ?, read_at = ? WHERE conversation_id = ? AND outgoing = 0 AND read_at IS NULL",
            arrayOf<Any?>(MessageStatus.READ.ordinal, timestamp, conversationId)
        )
        db.exec("UPDATE conversations SET unread = 0 WHERE id = ?", arrayOf(conversationId))
    }

    /** Incoming ids that have not been delivered yet (used to send delivery receipts). */
    fun undeliveredIncoming(conversationId: String): List<String> = db.query(
        "SELECT id FROM messages WHERE conversation_id = ? AND outgoing = 0 AND delivered_at IS NULL LIMIT 200",
        arrayOf(conversationId)
    ) { it.getString(0) }

    fun unseenIncoming(conversationId: String): List<String> = db.query(
        "SELECT id FROM messages WHERE conversation_id = ? AND outgoing = 0 AND read_at IS NULL LIMIT 200",
        arrayOf(conversationId)
    ) { it.getString(0) }

    fun editMessage(id: String, newText: String, editedAt: Long) =
        db.exec("UPDATE messages SET body = ?, edited_at = ? WHERE id = ?", arrayOf<Any?>(vault.sealString(newText), editedAt, id))

    fun setReactions(id: String, reactions: String?) =
        db.exec("UPDATE messages SET reactions = ? WHERE id = ?", arrayOf<Any?>(reactions?.let { vault.sealString(it) }, id))

    /** Erases the body but keeps the bubble, the way "delete for everyone" behaves. */
    fun tombstoneMessage(id: String) = db.transaction {
        db.exec("UPDATE messages SET body = NULL, attachment_id = NULL WHERE id = ?", arrayOf(id))
        db.exec("DELETE FROM attachments WHERE message_id = ?", arrayOf(id))
    }

    fun deleteMessage(id: String) = db.exec("DELETE FROM messages WHERE id = ?", arrayOf(id))

    fun messagesWithAttachments(conversationId: String): List<ChatMessage> = db.query(
        "SELECT $messageColumns FROM messages WHERE conversation_id = ? AND attachment_id IS NOT NULL ORDER BY created_at ASC",
        arrayOf(conversationId)
    ) { cursorToMessage(it) }

    fun expireMessages(now: Long): List<String> {
        val ids = db.query(
            "SELECT id FROM messages WHERE expires_at IS NOT NULL AND expires_at < ?", arrayOf(now)
        ) { it.getString(0) }
        if (ids.isNotEmpty()) db.exec("DELETE FROM messages WHERE expires_at IS NOT NULL AND expires_at < ?", arrayOf(now))
        return ids
    }

    fun searchMessages(query: String, limit: Int = 100): List<ChatMessage> {
        // Message bodies are encrypted per row, so searching means scanning and decrypting.
        val needle = query.lowercase()
        if (needle.isBlank()) return emptyList()
        val results = ArrayList<ChatMessage>()
        db.query("SELECT $messageColumns FROM messages ORDER BY created_at DESC LIMIT 5000") { cursor ->
            val message = cursorToMessage(cursor)
            if (message.body?.lowercase()?.contains(needle) == true) results.add(message)
        }
        return results.take(limit)
    }

    // ------------------------------------------------------------------ maintenance

    fun deleteAllMessages() {
        db.exec("DELETE FROM messages")
        db.exec("DELETE FROM attachments")
        db.exec("DELETE FROM conversations")
    }

    fun wipeEverything() {
        db.transaction {
            for (table in listOf("messages", "attachments", "conversations", "contacts", "sessions", "prekeys", "signed_prekeys", "outbox", "pending_acks", "identity")) {
                db.exec("DELETE FROM $table")
            }
        }
        db.secureErase()
    }

    fun messageCount(): Long = db.tableCount("messages")

    fun attachmentCount(): Long = db.tableCount("attachments")

    fun sessionCount(): Long = db.tableCount("sessions")

    fun outboxCount(): Long = db.tableCount("outbox")

    fun contactCount(): Long = db.tableCount("contacts")
}

/** Bytes of the database file, for the storage screen. */
fun dbFileSize(context: android.content.Context): Long = Db.databaseFile(context).let { file ->
    file.length() + java.io.File(file.path + "-wal").length()
}
