package com.ping.app.data

import android.database.Cursor
import com.ping.app.core.crypto.IdentityKeyPair
import com.ping.app.core.crypto.OneTimePreKey
import com.ping.app.core.crypto.PreKeyBundle
import com.ping.app.core.crypto.SignedPreKey
import com.ping.app.core.crypto.X25519KeyPair

/** A signed pre-key plus the id it is published under. */
data class StoredSignedPreKey(
    val id: Int,
    val keyPair: X25519KeyPair,
    val signature: ByteArray,
    val createdAt: Long
)

data class OutboxEntry(
    val id: String,
    val peerHash: String,
    val conversationId: String,
    val payload: ByteArray,
    val createdAt: Long,
    val attempts: Int,
    val nextAttemptAt: Long,
    val lastError: String?
)

/**
 * Storage for everything the ratchet needs: sessions, pre-keys, the outbox and attachments.
 *
 * Session state and private pre-keys are sealed with the Keystore key, so the SQLCipher file alone
 * is not enough to impersonate a device — an attacker would need the phone's secure hardware.
 */
class CryptoStore(private val db: Db, private val vault: Vault) {

    // ------------------------------------------------------------------ sessions

    fun saveSession(record: SessionRecord) = db.exec(
        """
        INSERT OR REPLACE INTO sessions(peer_hash, state, tag, updated_at, peer_identity, handshake_sent)
        VALUES(?,?,?,?,?,?)
        """.trimIndent(),
        arrayOf<Any?>(
            record.peerHash,
            vault.seal(record.state),
            record.tag,
            record.updatedAt,
            record.peerIdentityKey?.let { vault.seal(it) },
            if (record.handshakeSent) 1 else 0
        )
    )

    fun session(peerHash: String): SessionRecord? = db.queryOne(
        "SELECT peer_hash, state, tag, updated_at, peer_identity, handshake_sent FROM sessions WHERE peer_hash = ?",
        arrayOf(peerHash)
    ) { cursorToSession(it) }

    /** Every session, used to match an incoming envelope to the right conversation by its tag. */
    fun sessions(): List<SessionRecord> = db.query(
        "SELECT peer_hash, state, tag, updated_at, peer_identity, handshake_sent FROM sessions"
    ) { cursorToSession(it) }

    fun sessionByTag(tag: ByteArray): SessionRecord? =
        sessions().firstOrNull { it.tag.contentEquals(tag) }

    fun deleteSession(peerHash: String) = db.exec("DELETE FROM sessions WHERE peer_hash = ?", arrayOf(peerHash))

    fun markHandshakeSent(peerHash: String, sent: Boolean) =
        db.exec("UPDATE sessions SET handshake_sent = ? WHERE peer_hash = ?", arrayOf<Any?>(if (sent) 1 else 0, peerHash))

    private fun cursorToSession(cursor: Cursor) = SessionRecord(
        peerHash = cursor.getString(0),
        state = vault.open(cursor.getBlob(1)),
        tag = cursor.getBlob(2),
        updatedAt = cursor.getLong(3),
        peerIdentityKey = cursor.getBlob(4)?.let { vault.open(it) },
        handshakeSent = cursor.getInt(5) == 1
    )

    // ------------------------------------------------------------------ one-time pre-keys

    fun addOneTimePreKeys(keys: List<OneTimePreKey>) = db.transaction {
        for (key in keys) {
            db.exec(
                "INSERT OR REPLACE INTO prekeys(id, secret, public, created_at, published, consumed) VALUES(?,?,?,?,0,0)",
                arrayOf<Any?>(key.id, vault.seal(key.keyPair.privateKey), key.keyPair.publicKey, System.currentTimeMillis())
            )
        }
    }

    fun nextPreKeyId(): Int = (db.queryOne("SELECT COALESCE(MAX(id), 0) FROM prekeys") { it.getInt(0) } ?: 0) + 1

    fun unconsumedPreKeyCount(): Int =
        db.queryOne("SELECT COUNT(*) FROM prekeys WHERE consumed = 0") { it.getInt(0) } ?: 0

    /** Keys that still have to be published to the relay (or handed over the local network). */
    fun unpublishedPreKeyCount(): Int =
        db.queryOne("SELECT COUNT(*) FROM prekeys WHERE consumed = 0 AND published = 0") { it.getInt(0) } ?: 0

    /** Public halves that still need to be published to the relay. */
    fun unpublishedPreKeys(limit: Int = 100): List<Pair<Int, ByteArray>> = db.query(
        "SELECT id, public FROM prekeys WHERE consumed = 0 AND published = 0 ORDER BY id LIMIT ?",
        arrayOf<Any?>(limit)
    ) { it.getInt(0) to it.getBlob(1) }

    fun markPreKeysPublished(ids: List<Int>) = db.transaction {
        for (id in ids) db.exec("UPDATE prekeys SET published = 1 WHERE id = ?", arrayOf(id))
    }

    /**
     * Loads a one-time pre-key and marks it consumed in the same transaction, so a pre-key is
     * never used for two different sessions.
     */
    fun consumePreKey(id: Int): X25519KeyPair? = db.transaction {
        val secret = db.queryOne(
            "SELECT secret, public FROM prekeys WHERE id = ? AND consumed = 0", arrayOf(id)
        ) { vault.open(it.getBlob(0)) to it.getBlob(1) } ?: return@transaction null
        db.exec("UPDATE prekeys SET consumed = 1 WHERE id = ?", arrayOf(id))
        X25519KeyPair(secret.first, secret.second)
    }

    /** Deletes consumed pre-keys; the private halves are no longer needed. */
    fun pruneConsumedPreKeys() = db.exec("DELETE FROM prekeys WHERE consumed = 1")

    // ------------------------------------------------------------------ signed pre-keys

    fun addSignedPreKey(id: Int, keyPair: X25519KeyPair, signature: ByteArray, createdAt: Long, active: Boolean = true) =
        db.exec(
            "INSERT OR REPLACE INTO signed_prekeys(id, secret, public, signature, created_at, active) VALUES(?,?,?,?,?,?)",
            arrayOf<Any?>(id, vault.seal(keyPair.privateKey), keyPair.publicKey, signature, createdAt, if (active) 1 else 0)
        )

    fun activeSignedPreKey(): StoredSignedPreKey? = db.queryOne(
        "SELECT id, secret, public, signature, created_at FROM signed_prekeys WHERE active = 1 ORDER BY created_at DESC LIMIT 1"
    ) {
        StoredSignedPreKey(it.getInt(0), X25519KeyPair(vault.open(it.getBlob(1)), it.getBlob(2)), it.getBlob(3), it.getLong(4))
    }

    fun signedPreKey(id: Int): StoredSignedPreKey? = db.queryOne(
        "SELECT id, secret, public, signature, created_at FROM signed_prekeys WHERE id = ?", arrayOf(id)
    ) {
        StoredSignedPreKey(it.getInt(0), X25519KeyPair(vault.open(it.getBlob(1)), it.getBlob(2)), it.getBlob(3), it.getLong(4))
    }

    /**
     * Creates a new signed pre-key when the current one is older than a week, keeping the previous
     * one around so messages already in flight still decrypt.
     */
    fun rotateSignedPreKeyIfNeeded(identity: IdentityKeyPair, now: Long): StoredSignedPreKey {
        val current = activeSignedPreKey()
        if (current != null && now - current.createdAt < SignedPreKey.LIFETIME_MS) return current

        val nextId = (db.queryOne("SELECT COALESCE(MAX(id), 0) FROM signed_prekeys") { it.getInt(0) } ?: 0) + 1
        val created = PreKeyBundle.newSignedPreKey(identity, nextId, now)
        addSignedPreKey(created.id, created.keyPair, created.signature, created.createdAt, active = true)
        db.exec("UPDATE signed_prekeys SET active = 0 WHERE id != ?", arrayOf(created.id))
        db.exec("DELETE FROM signed_prekeys WHERE created_at < ?", arrayOf(now - SignedPreKey.RETIRE_MS))
        return StoredSignedPreKey(created.id, created.keyPair, created.signature, created.createdAt)
    }

    fun signedPreKeyCount(): Int =
        db.queryOne("SELECT COUNT(*) FROM signed_prekeys") { it.getInt(0) } ?: 0

    // ------------------------------------------------------------------ outbox

    fun enqueueOutbox(id: String, peerHash: String, conversationId: String, payload: ByteArray, now: Long) =
        db.exec(
            "INSERT OR REPLACE INTO outbox(id, peer_hash, conversation_id, payload, created_at, attempts, next_attempt_at) VALUES(?,?,?,?,?,0,?)",
            arrayOf<Any?>(id, peerHash, conversationId, vault.seal(payload), now, now)
        )

    fun dueOutbox(now: Long, limit: Int = 20): List<OutboxEntry> = db.query(
        "SELECT id, peer_hash, conversation_id, payload, created_at, attempts, next_attempt_at, last_error FROM outbox WHERE next_attempt_at <= ? ORDER BY created_at LIMIT ?",
        arrayOf<Any?>(now, limit)
    ) { cursorToOutbox(it) }

    fun outboxEntries(): List<OutboxEntry> = db.query(
        "SELECT id, peer_hash, conversation_id, payload, created_at, attempts, next_attempt_at, last_error FROM outbox ORDER BY created_at"
    ) { cursorToOutbox(it) }

    fun outboxCount(): Int = db.queryOne("SELECT COUNT(*) FROM outbox") { it.getInt(0) } ?: 0

    /**
     * Outbox rows still waiting for one attachment transfer.
     *
     * A transfer bigger than one envelope is queued as several outbox rows (`attachment:<id>` for
     * the announcement and `chunk:<id>:<index>` for every chunk), so its progress is simply how many
     * of those rows are left.
     */
    fun pendingTransferRows(messageId: String): Int = db.queryOne(
        "SELECT COUNT(*) FROM outbox WHERE id = ? OR id LIKE ?",
        arrayOf<Any?>("attachment:$messageId", "chunk:$messageId:%")
    ) { it.getInt(0) } ?: 0

    fun rescheduleOutbox(id: String, attempts: Int, nextAttemptAt: Long, error: String?) =
        db.exec(
            "UPDATE outbox SET attempts = ?, next_attempt_at = ?, last_error = ? WHERE id = ?",
            arrayOf<Any?>(attempts, nextAttemptAt, error?.take(200), id)
        )

    fun removeOutbox(id: String) = db.exec("DELETE FROM outbox WHERE id = ?", arrayOf(id))

    private fun cursorToOutbox(cursor: Cursor) = OutboxEntry(
        id = cursor.getString(0),
        peerHash = cursor.getString(1),
        conversationId = cursor.getString(2),
        payload = vault.open(cursor.getBlob(3)),
        createdAt = cursor.getLong(4),
        attempts = cursor.getInt(5),
        nextAttemptAt = cursor.getLong(6),
        lastError = cursor.getString(7)
    )

    // ------------------------------------------------------------------ attachments

    fun insertAttachment(record: AttachmentRecord) = db.exec(
        """
        INSERT OR REPLACE INTO attachments
            (id, transfer_id, message_id, conversation_id, file_name, mime, size, chunk_count, sha256,
             file_key, outgoing, path, received_chunks, status)
        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """.trimIndent(),
        arrayOf<Any?>(
            record.id,
            record.transferId,
            record.messageId,
            record.conversationId,
            vault.sealString(record.fileName),
            vault.sealString(record.mimeType),
            record.size,
            record.chunkCount,
            record.sha256,
            vault.seal(record.fileKey),
            if (record.outgoing) 1 else 0,
            record.path?.let { vault.sealString(it) },
            record.receivedChunks,
            record.status
        )
    )

    fun attachment(id: String): AttachmentRecord? = db.queryOne(
        "SELECT id, transfer_id, message_id, conversation_id, file_name, mime, size, chunk_count, sha256, file_key, outgoing, path, received_chunks, status FROM attachments WHERE id = ?",
        arrayOf(id)
    ) { cursorToAttachment(it) }

    fun attachmentByTransferId(transferId: String): AttachmentRecord? = db.queryOne(
        "SELECT id, transfer_id, message_id, conversation_id, file_name, mime, size, chunk_count, sha256, file_key, outgoing, path, received_chunks, status FROM attachments WHERE transfer_id = ?",
        arrayOf(transferId)
    ) { cursorToAttachment(it) }

    fun setAttachmentPath(id: String, path: String) =
        db.exec("UPDATE attachments SET path = ? WHERE id = ?", arrayOf<Any?>(vault.sealString(path), id))

    fun setAttachmentReceivedChunks(id: String, count: Int, status: Int = 0) =
        db.exec("UPDATE attachments SET received_chunks = ?, status = ? WHERE id = ?", arrayOf<Any?>(count, status, id))

    fun setAttachmentStatus(id: String, status: Int) =
        db.exec("UPDATE attachments SET status = ? WHERE id = ?", arrayOf<Any?>(status, id))

    fun attachmentCount(): Int = db.queryOne("SELECT COUNT(*) FROM attachments") { it.getInt(0) } ?: 0

    private fun cursorToAttachment(cursor: Cursor) = AttachmentRecord(
        id = cursor.getString(0),
        transferId = cursor.getString(1),
        messageId = cursor.getString(2),
        conversationId = cursor.getString(3),
        fileName = cursor.getBlob(4)?.let { vault.openString(it) } ?: "file",
        mimeType = cursor.getBlob(5)?.let { vault.openString(it) } ?: "application/octet-stream",
        size = cursor.getInt(6),
        chunkCount = cursor.getInt(7),
        sha256 = cursor.getBlob(8),
        fileKey = vault.open(cursor.getBlob(9)),
        outgoing = cursor.getInt(10) == 1,
        path = cursor.getBlob(11)?.let { vault.openString(it) },
        receivedChunks = cursor.getInt(12),
        status = cursor.getInt(13)
    )

    // ------------------------------------------------------------------ relay acks

    fun queueAck(envelopeId: String) =
        db.exec("INSERT OR IGNORE INTO pending_acks(envelope_id, created_at) VALUES(?,?)", arrayOf<Any?>(envelopeId, System.currentTimeMillis()))

    fun pendingAcks(limit: Int = 50): List<String> =
        db.query("SELECT envelope_id FROM pending_acks ORDER BY created_at LIMIT ?", arrayOf<Any?>(limit)) { it.getString(0) }

    fun clearAcks(ids: List<String>) = db.transaction {
        for (id in ids) db.exec("DELETE FROM pending_acks WHERE envelope_id = ?", arrayOf(id))
    }

    fun sessionCount(): Long = db.tableCount("sessions")
}
