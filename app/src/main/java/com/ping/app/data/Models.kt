package com.ping.app.data

import com.ping.app.core.crypto.Bytes

/** A person you can talk to, as known locally. Keys are the source of truth, names are decoration. */
data class Contact(
    val hash: String,
    val signingKey: ByteArray,
    val dhKey: ByteArray,
    val displayName: String,
    val mailbox: ByteArray,
    val relayUrl: String,
    val addedAt: Long,
    val verified: Boolean = false,
    val blocked: Boolean = false,
    val disappearingSeconds: Long = 0L,
    val lastSeenAt: Long? = null
) {
    override fun equals(other: Any?): Boolean = other is Contact && other.hash == hash
    override fun hashCode(): Int = hash.hashCode()
}

data class Conversation(
    val id: String,
    val peerHash: String,
    /**
     * Set for a group chat. A group conversation uses the message pipeline of a direct one, so
     * everything built on it (unread counts, disappearing messages, replies, search) works for
     * groups too; only the addressing on send and the title differ.
     */
    val groupId: String? = null,
    val createdAt: Long,
    val lastMessageAt: Long,
    val unreadCount: Int,
    val pinned: Boolean,
    val muted: Boolean,
    val archived: Boolean,
    val disappearingSeconds: Long,
    /** Filled in by the UI layer for the list. */
    val preview: String = "",
    val lastMessageOutgoing: Boolean = false
)

enum class MessageStatus { PENDING, SENT, DELIVERED, READ, FAILED }

/** A local message. `body` is only ever held in memory in the clear. */
data class ChatMessage(
    val id: String,
    val conversationId: String,
    val peerHash: String,
    val outgoing: Boolean,
    val kind: Int,
    val body: String?,
    val replyToId: String?,
    val createdAt: Long,
    val sentAt: Long?,
    val deliveredAt: Long?,
    val readAt: Long?,
    val status: MessageStatus,
    val attachmentId: String?,
    val expiresAt: Long?,
    val editedAt: Long?,
    val reactions: String?
) {
    val isText: Boolean get() = body != null
}

data class AttachmentRecord(
    val id: String,
    val transferId: String,
    val messageId: String,
    val conversationId: String,
    val fileName: String,
    val mimeType: String,
    val size: Int,
    val chunkCount: Int,
    val sha256: ByteArray,
    val fileKey: ByteArray,
    val outgoing: Boolean,
    val path: String?,
    /** One bit per chunk: sent (outgoing transfers) or received (incoming). See [ChunkBits]. */
    val chunkBits: ByteArray = ByteArray(0),
    /** Why the last attempt to move the rest of this file stopped, while it is still stuck. */
    val transferError: String? = null,
    val status: Int
) {
    /** Chunks sent (outgoing) or received (incoming) so far. */
    val doneChunks: Int get() = ChunkBits.count(chunkBits, chunkCount)

    /** Whether the file can be opened: an outgoing copy is local from the start, an incoming one once it is whole. */
    val complete: Boolean get() = path != null && (outgoing || doneChunks >= chunkCount)
}

/** A group chat: a name, a roster and the epoch key currently in use. */
data class GroupRecord(
    val id: String,
    val name: String,
    val epoch: Int,
    val epochKey: ByteArray,
    val createdAt: Long,
    val members: List<String> = emptyList()
) {
    val memberCount: Int get() = members.size

    override fun equals(other: Any?): Boolean =
        other is GroupRecord && other.id == id && other.epoch == epoch && other.name == name

    override fun hashCode(): Int = id.hashCode() * 31 + epoch
}

data class IdentityRecord(
    val signingPrivateKey: ByteArray,
    val signingPublicKey: ByteArray,
    val dhPrivateKey: ByteArray,
    val dhPublicKey: ByteArray,
    val dhBinding: ByteArray,
    val displayName: String,
    val createdAt: Long
)

data class SessionRecord(
    val peerHash: String,
    val state: ByteArray,
    val tag: ByteArray,
    val updatedAt: Long,
    val peerIdentityKey: ByteArray?,
    val handshakeSent: Boolean
)

/** Result of a delivery attempt, used to drive retry policy. */
sealed class DeliveryResult {
    object Delivered : DeliveryResult()
    object QueuedForRetry : DeliveryResult()
    data class Failed(val reason: String, val permanent: Boolean = false) : DeliveryResult()
}

fun ByteArray.shortHash(): String = Bytes.toHex(this).take(16)

fun ByteArray.toB64(): String = Bytes.toBase64Url(this)

fun String.b64ToBytes(): ByteArray = Bytes.fromBase64Url(this)
