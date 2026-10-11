package com.ping.app.core.protocol

import com.ping.app.core.crypto.BufReader
import com.ping.app.core.crypto.BufWriter
import com.ping.app.core.crypto.Bytes
import com.ping.app.core.crypto.RatchetHeader
import com.ping.app.core.crypto.RatchetMessage
import com.ping.app.core.crypto.WireFormatException

/**
 * Every byte that Ping puts on the network is built here.
 *
 * Formats are binary and strictly versioned: a relay (or an attacker sitting in front of it) can
 * only ever feed the client data that this file validates field by field.
 */
object Wire {

    const val VERSION = 1
    const val MAX_ENVELOPE_BYTES = 512 * 1024
    const val MAX_TEXT_BYTES = 16 * 1024
    const val MAX_NAME_BYTES = 64
    const val MAX_URL_BYTES = 256
    const val MAX_FILE_NAME_BYTES = 200
    const val MAX_MIME_BYTES = 100
    const val ATTACHMENT_CHUNK_BYTES = 64 * 1024
    const val MAX_ATTACHMENT_BYTES = 8 * 1024 * 1024

    private val PADDING_BUCKETS = intArrayOf(256, 1024, 4096, 16 * 1024, 64 * 1024, 256 * 1024, MAX_ENVELOPE_BYTES)

    /** Mailbox identifier derived from the identity DH key: the relay never sees a phone number. */
    fun mailboxId(dhIdentityKey: ByteArray): ByteArray {
        require(dhIdentityKey.size == 32) { "identity key must be 32 bytes" }
        return Bytes.sha256(Bytes.utf8("PingMailbox/v1"), dhIdentityKey).copyOfRange(0, 16)
    }

    fun randomEnvelopeId(): String = Bytes.toBase64Url(Bytes.random(16))

    fun randomTransferId(): ByteArray = Bytes.random(12)

    /**
     * Pads a payload so that every envelope in a size class looks identical on the wire, and
     * hides the exact message length from the relay.
     */
    fun pad(payload: ByteArray): ByteArray {
        val needed = payload.size + 5
        val target = PADDING_BUCKETS.firstOrNull { it >= needed }
            ?: throw WireFormatException("payload too large (${payload.size} bytes)")
        val writer = BufWriter(target)
        writer.varint(payload.size).bytes(payload)
        while (writer.length < target) writer.u8(0)
        return writer.toByteArray()
    }

    fun unpad(padded: ByteArray): ByteArray {
        if (padded.size > MAX_ENVELOPE_BYTES) throw WireFormatException("oversized envelope")
        val reader = BufReader(padded)
        val length = reader.varint()
        if (length > padded.size) throw WireFormatException("padded length out of range")
        return reader.bytes(length)
    }

    // ------------------------------------------------------------------ envelope

    enum class EnvelopeKind(val code: Int) {
        /** Regular message: the relay cannot tell who sent it (only a session tag is visible). */
        SEALED(0),

        /** First message of a session: carries the public handshake material. */
        HANDSHAKE(1);

        companion object {
            fun fromCode(code: Int): EnvelopeKind = entries.firstOrNull { it.code == code }
                ?: throw WireFormatException("unknown envelope kind $code")
        }
    }

    class Envelope(
        val kind: EnvelopeKind,
        val mailbox: ByteArray,
        /** Opaque 8-byte session tag, only present for [EnvelopeKind.SEALED]. */
        val sessionTag: ByteArray?,
        /** Sender's public DH identity key, only present for [EnvelopeKind.HANDSHAKE]. */
        val senderIdentity: ByteArray?,
        val payload: ByteArray
    ) {
        init {
            require(mailbox.size == 16) { "mailbox must be 16 bytes" }
            if (kind == EnvelopeKind.SEALED) require(sessionTag?.size == 8) { "sealed envelopes need an 8-byte tag" }
            if (kind == EnvelopeKind.HANDSHAKE) require(senderIdentity?.size == 32) { "handshake envelopes carry the sender key" }
        }

        fun encode(): ByteArray {
            val writer = BufWriter(payload.size + 64)
            writer.u8(VERSION).u8(kind.code).bytes(mailbox)
            when (kind) {
                EnvelopeKind.SEALED -> {
                    writer.u8(0)
                    writer.bytes(sessionTag!!)
                }
                EnvelopeKind.HANDSHAKE -> {
                    writer.u8(0)
                    writer.bytes(senderIdentity!!)
                }
            }
            writer.blob(payload)
            return writer.toByteArray()
        }

        companion object {
            fun decode(bytes: ByteArray): Envelope {
                if (bytes.size > MAX_ENVELOPE_BYTES) throw WireFormatException("oversized envelope")
                val reader = BufReader(bytes)
                val version = reader.u8()
                if (version != VERSION) throw WireFormatException("unsupported envelope version $version")
                val kind = EnvelopeKind.fromCode(reader.u8())
                val mailbox = reader.bytes(16)
                val flags = reader.u8()
                if (flags != 0) throw WireFormatException("unexpected envelope flags")
                val param: ByteArray? = when (kind) {
                    EnvelopeKind.SEALED -> reader.bytes(8)
                    EnvelopeKind.HANDSHAKE -> reader.bytes(32)
                }
                val payload = reader.blob()
                reader.expectEnd()
                return Envelope(
                    kind = kind,
                    mailbox = mailbox,
                    sessionTag = if (kind == EnvelopeKind.SEALED) param else null,
                    senderIdentity = if (kind == EnvelopeKind.HANDSHAKE) param else null,
                    payload = payload
                )
            }
        }
    }

    /** The first message of a session: everything the receiver needs to derive the same keys. */
    class HandshakeInit(
        val senderSigningKey: ByteArray,
        val senderDhIdentity: ByteArray,
        val ephemeralKey: ByteArray,
        val signedPreKeyId: Int,
        val oneTimePreKeyId: Int?,
        val firstMessage: RatchetMessage
    ) {
        init {
            require(senderSigningKey.size == 32 && senderDhIdentity.size == 32 && ephemeralKey.size == 32)
        }

        fun encode(): ByteArray = BufWriter(256)
            .bytes(senderSigningKey)
            .bytes(senderDhIdentity)
            .bytes(ephemeralKey)
            .u32(signedPreKeyId)
            .varint(oneTimePreKeyId ?: NO_PREKEY)
            .blob(firstMessage.encode())
            .toByteArray()

        companion object {
            const val NO_PREKEY = 0x7fffffff

            fun decode(bytes: ByteArray): HandshakeInit {
                val reader = BufReader(bytes)
                val signing = reader.bytes(32)
                val dh = reader.bytes(32)
                val ephemeral = reader.bytes(32)
                val spkId = reader.u32()
                val otkId = reader.varint()
                val message = RatchetMessage.decode(reader.blob())
                reader.expectEnd()
                return HandshakeInit(
                    senderSigningKey = signing,
                    senderDhIdentity = dh,
                    ephemeralKey = ephemeral,
                    signedPreKeyId = spkId,
                    oneTimePreKeyId = if (otkId == NO_PREKEY) null else otkId,
                    firstMessage = message
                )
            }
        }
    }

    // ------------------------------------------------------------------ payloads

    enum class PayloadKind(val code: Int) {
        TEXT(1),
        RECEIPT(2),
        TYPING(3),
        PROFILE(4),
        ATTACHMENT_META(5),
        ATTACHMENT_CHUNK(6),
        DELETE(7),
        EDIT(8),
        REACTION(9),
        CONVERSATION_SETTINGS(10);

        companion object {
            fun fromCode(code: Int): PayloadKind = entries.firstOrNull { it.code == code }
                ?: throw WireFormatException("unknown payload kind $code")
        }
    }

    /** Everything that travels inside the ratchet, i.e. end-to-end encrypted. */
    sealed class Payload {

        abstract val sentAt: Long

        data class Text(
            val text: String,
            val replyToId: String? = null,
            override val sentAt: Long = System.currentTimeMillis()
        ) : Payload()

        /** Delivery/read receipts. */
        data class Receipt(
            val messageIds: List<String>,
            val read: Boolean,
            override val sentAt: Long = System.currentTimeMillis()
        ) : Payload()

        data class Typing(val typing: Boolean, override val sentAt: Long = System.currentTimeMillis()) : Payload()

        data class Profile(val displayName: String, override val sentAt: Long = System.currentTimeMillis()) : Payload()

        data class AttachmentMeta(
            val transferId: ByteArray,
            val fileName: String,
            val mimeType: String,
            val size: Int,
            val sha256: ByteArray,
            val chunkCount: Int,
            val fileKey: ByteArray,
            override val sentAt: Long = System.currentTimeMillis()
        ) : Payload()

        data class AttachmentChunk(
            val transferId: ByteArray,
            val index: Int,
            val chunkCount: Int,
            val data: ByteArray,
            override val sentAt: Long = System.currentTimeMillis()
        ) : Payload()

        data class Delete(val messageId: String, override val sentAt: Long = System.currentTimeMillis()) : Payload()

        data class Edit(
            val messageId: String,
            val newText: String,
            override val sentAt: Long = System.currentTimeMillis()
        ) : Payload()

        data class Reaction(
            val messageId: String,
            val emoji: String,
            override val sentAt: Long = System.currentTimeMillis()
        ) : Payload()

        data class ConversationSettings(
            val disappearingSeconds: Long,
            override val sentAt: Long = System.currentTimeMillis()
        ) : Payload()

        fun encode(): ByteArray {
            val writer = BufWriter(128)
            writer.u64(sentAt)
            when (this) {
                is Text -> {
                    writer.u8(PayloadKind.TEXT.code)
                    writer.string(requireLength(text, MAX_TEXT_BYTES, "text"))
                    writer.string(replyToId ?: "")
                }
                is Receipt -> {
                    if (messageIds.size > 500) throw WireFormatException("too many receipts in one message")
                    writer.u8(PayloadKind.RECEIPT.code)
                    writer.u8(if (read) 1 else 0)
                    writer.varint(messageIds.size)
                    for (id in messageIds) writer.string(requireLength(id, 128, "message id"))
                }
                is Typing -> {
                    writer.u8(PayloadKind.TYPING.code)
                    writer.u8(if (typing) 1 else 0)
                }
                is Profile -> {
                    writer.u8(PayloadKind.PROFILE.code)
                    writer.string(requireLength(displayName, MAX_NAME_BYTES, "display name"))
                }
                is AttachmentMeta -> {
                    if (size < 0 || size > MAX_ATTACHMENT_BYTES) throw WireFormatException("attachment too large")
                    if (chunkCount <= 0 || chunkCount > (MAX_ATTACHMENT_BYTES / ATTACHMENT_CHUNK_BYTES) + 1) {
                        throw WireFormatException("bad chunk count")
                    }
                    writer.u8(PayloadKind.ATTACHMENT_META.code)
                    writer.bytes(transferId)
                    writer.string(requireLength(fileName, MAX_FILE_NAME_BYTES, "file name"))
                    writer.string(requireLength(mimeType, MAX_MIME_BYTES, "mime type"))
                    writer.u32(size)
                    writer.bytes(sha256)
                    writer.varint(chunkCount)
                    writer.bytes(fileKey)
                }
                is AttachmentChunk -> {
                    if (data.size > ATTACHMENT_CHUNK_BYTES) throw WireFormatException("chunk too large")
                    if (index < 0 || index >= chunkCount) throw WireFormatException("chunk index out of range")
                    writer.u8(PayloadKind.ATTACHMENT_CHUNK.code)
                    writer.bytes(transferId)
                    writer.varint(index)
                    writer.varint(chunkCount)
                    writer.blob(data)
                }
                is Delete -> {
                    writer.u8(PayloadKind.DELETE.code)
                    writer.string(requireLength(messageId, 128, "message id"))
                }
                is Edit -> {
                    writer.u8(PayloadKind.EDIT.code)
                    writer.string(requireLength(messageId, 128, "message id"))
                    writer.string(requireLength(newText, MAX_TEXT_BYTES, "text"))
                }
                is Reaction -> {
                    writer.u8(PayloadKind.REACTION.code)
                    writer.string(requireLength(messageId, 128, "message id"))
                    writer.string(requireLength(emoji, 16, "emoji"))
                }
                is ConversationSettings -> {
                    writer.u8(PayloadKind.CONVERSATION_SETTINGS.code)
                    writer.u64(disappearingSeconds)
                }
            }
            return writer.toByteArray()
        }

        companion object {
            fun decode(bytes: ByteArray): Payload {
                val reader = BufReader(bytes)
                val sentAt = reader.u64()
                val kind = PayloadKind.fromCode(reader.u8())
                val payload: Payload = when (kind) {
                    PayloadKind.TEXT -> {
                        val text = reader.string()
                        val reply = reader.string()
                        Text(text, reply.ifEmpty { null }, sentAt)
                    }
                    PayloadKind.RECEIPT -> {
                        val read = reader.u8() != 0
                        val count = reader.varint()
                        if (count > 500) throw WireFormatException("too many receipts")
                        val ids = ArrayList<String>(count)
                        repeat(count) { ids.add(reader.string()) }
                        Receipt(ids, read, sentAt)
                    }
                    PayloadKind.TYPING -> Typing(reader.u8() != 0, sentAt)
                    PayloadKind.PROFILE -> Profile(reader.string(), sentAt)
                    PayloadKind.ATTACHMENT_META -> {
                        val transferId = reader.bytes(12)
                        val fileName = reader.string()
                        val mime = reader.string()
                        val size = reader.u32()
                        val sha = reader.bytes(32)
                        val chunks = reader.varint()
                        val fileKey = reader.bytes(32)
                        AttachmentMeta(transferId, fileName, mime, size, sha, chunks, fileKey, sentAt)
                    }
                    PayloadKind.ATTACHMENT_CHUNK -> {
                        val transferId = reader.bytes(12)
                        val index = reader.varint()
                        val chunks = reader.varint()
                        val data = reader.blob()
                        AttachmentChunk(transferId, index, chunks, data, sentAt)
                    }
                    PayloadKind.DELETE -> Delete(reader.string(), sentAt)
                    PayloadKind.EDIT -> Edit(reader.string(), reader.string(), sentAt)
                    PayloadKind.REACTION -> Reaction(reader.string(), reader.string(), sentAt)
                    PayloadKind.CONVERSATION_SETTINGS -> ConversationSettings(reader.u64(), sentAt)
                }
                reader.expectEnd()
                return payload
            }
        }
    }

    private fun requireLength(value: String, maxBytes: Int, what: String): String {
        val size = value.toByteArray(Charsets.UTF_8).size
        if (size > maxBytes) throw WireFormatException("$what is too long ($size > $maxBytes bytes)")
        return value
    }

    // ------------------------------------------------------- ratchet message blob

    fun encodeRatchet(message: RatchetMessage): ByteArray = message.encode()

    fun decodeRatchet(bytes: ByteArray): RatchetMessage = RatchetMessage.decode(bytes)

    fun headerBytes(header: RatchetHeader): ByteArray = header.encode()
}
