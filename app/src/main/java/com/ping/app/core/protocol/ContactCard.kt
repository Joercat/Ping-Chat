package com.ping.app.core.protocol

import com.ping.app.core.crypto.BufReader
import com.ping.app.core.crypto.BufWriter
import com.ping.app.core.crypto.Bytes
import com.ping.app.core.crypto.Ed25519
import com.ping.app.core.crypto.IdentityKeyPair
import com.ping.app.core.crypto.PreKeyBundle
import com.ping.app.core.crypto.WireFormatException

/**
 * The contact card is what one person hands to another to become reachable — it is the Ping
 * equivalent of exchanging phone numbers, except that it is self-signed public key material and
 * contains no personal data.
 *
 * It travels as a QR code or as a short text code that can be sent over any channel.
 */
class ContactCard(
    val signingKey: ByteArray,
    val dhIdentityKey: ByteArray,
    val dhBinding: ByteArray,
    val displayName: String,
    val mailbox: ByteArray,
    val relayUrl: String,
    val createdAt: Long,
    val signature: ByteArray
) {

    init {
        require(signingKey.size == 32) { "signing key must be 32 bytes" }
        require(dhIdentityKey.size == 32) { "identity DH key must be 32 bytes" }
        require(dhBinding.size == 64) { "identity binding must be 64 bytes" }
        require(mailbox.size == 16) { "mailbox must be 16 bytes" }
    }

    /** Exact bytes covered by the signature. */
    fun signedBytes(): ByteArray = BufWriter(256)
        .bytes(Bytes.utf8(SIGNING_PREFIX))
        .bytes(signingKey)
        .bytes(dhIdentityKey)
        .blob(dhBinding)
        .string(displayName)
        .bytes(mailbox)
        .string(relayUrl)
        .u64(createdAt)
        .toByteArray()

    fun encode(): ByteArray = BufWriter(384)
        .u8(Wire.VERSION)
        .bytes(signingKey)
        .bytes(dhIdentityKey)
        .blob(dhBinding)
        .string(displayName)
        .bytes(mailbox)
        .string(relayUrl)
        .u64(createdAt)
        .blob(signature)
        .toByteArray()

    /** Verifies that the card really belongs to the holder of the identity key it advertises. */
    fun verify(): Boolean {
        if (signature.size != 64) return false
        if (!IdentityKeyPair.verifyBinding(signingKey, dhIdentityKey, dhBinding)) return false
        if (!Ed25519.verify(signingKey, signedBytes(), signature)) return false
        // The card must not claim a mailbox that is not derived from its own identity key.
        if (!Wire.mailboxId(dhIdentityKey).contentEquals(mailbox)) return false
        return true
    }

    val shortId: String get() = Bytes.toHex(Bytes.sha256(signingKey).copyOfRange(0, 8))

    companion object {
        private const val SIGNING_PREFIX = "PingContactCard/v1"

        fun create(
            identity: IdentityKeyPair,
            displayName: String,
            relayUrl: String,
            createdAt: Long
        ): ContactCard {
            val mailbox = Wire.mailboxId(identity.dh.publicKey)
            val draft = ContactCard(
                signingKey = identity.signing.publicKey,
                dhIdentityKey = identity.dh.publicKey,
                dhBinding = identity.dhBinding,
                displayName = displayName.take(64),
                mailbox = mailbox,
                relayUrl = relayUrl.take(256),
                createdAt = createdAt,
                signature = ByteArray(0)
            )
            return ContactCard(
                signingKey = draft.signingKey,
                dhIdentityKey = draft.dhIdentityKey,
                dhBinding = draft.dhBinding,
                displayName = draft.displayName,
                mailbox = draft.mailbox,
                relayUrl = draft.relayUrl,
                createdAt = draft.createdAt,
                signature = identity.signing.sign(draft.signedBytes())
            )
        }

        fun decode(bytes: ByteArray): ContactCard {
            val reader = BufReader(bytes)
            val version = reader.u8()
            if (version != Wire.VERSION) throw WireFormatException("unsupported contact card version $version")
            val signing = reader.bytes(32)
            val dh = reader.bytes(32)
            val binding = reader.blob()
            val name = reader.string()
            val mailbox = reader.bytes(16)
            val relay = reader.string()
            val createdAt = reader.u64()
            val signature = reader.blob()
            reader.expectEnd()
            if (name.toByteArray(Charsets.UTF_8).size > Wire.MAX_NAME_BYTES) throw WireFormatException("name too long")
            if (relay.toByteArray(Charsets.UTF_8).size > Wire.MAX_URL_BYTES) throw WireFormatException("relay url too long")
            return ContactCard(signing, dh, binding, name, mailbox, relay, createdAt, signature)
        }
    }
}

/**
 * Text form of a contact card. Accepts the code with or without the `PING1.` marker and with a
 * `ping://add/` URL prefix, so links pasted from any messenger work.
 */
object InviteCode {

    private const val PREFIX = "PING1."
    private const val URL_PREFIX = "ping://add/"

    fun encode(card: ContactCard): String = PREFIX + Bytes.toBase64Url(card.encode())

    fun decode(text: String): ContactCard {
        var clean = text.trim().replace("\n", "").replace("\r", "").replace(" ", "")
        if (clean.startsWith(URL_PREFIX, ignoreCase = true)) clean = clean.substring(URL_PREFIX.length)
        if (clean.startsWith("ping://", ignoreCase = true)) clean = clean.substringAfterLast('/')
        if (clean.startsWith(PREFIX, ignoreCase = true)) clean = clean.substring(PREFIX.length)
        if (clean.isEmpty()) throw WireFormatException("empty invite code")
        val raw = try {
            Bytes.fromBase64Url(clean)
        } catch (e: IllegalArgumentException) {
            throw WireFormatException("invite code is not valid base64url")
        }
        val card = ContactCard.decode(raw)
        if (!card.verify()) throw WireFormatException("invite code signature is invalid")
        return card
    }

    fun looksLikeInviteCode(text: String): Boolean {
        val clean = text.trim().replace(" ", "")
        return clean.startsWith(PREFIX) || clean.startsWith(URL_PREFIX) || clean.startsWith("ping://")
    }
}

/** Compact binary form of a pre-key bundle, used when talking to the relay. */
object BundleCodec {

    private const val NO_PREKEY = 0x7fffffff

    fun encode(bundle: PreKeyBundle): ByteArray = BufWriter(256)
        .u8(Wire.VERSION)
        .bytes(bundle.signingKey)
        .bytes(bundle.dhIdentityKey)
        .blob(bundle.dhBindingSignature)
        .u32(bundle.signedPreKeyId)
        .bytes(bundle.signedPreKey)
        .blob(bundle.signedPreKeySignature)
        .u32(bundle.oneTimePreKeyId ?: NO_PREKEY)
        .blob(bundle.oneTimePreKey ?: ByteArray(0))
        .u64(bundle.publishedAt)
        .toByteArray()

    fun decode(bytes: ByteArray): PreKeyBundle {
        val reader = BufReader(bytes)
        val version = reader.u8()
        if (version != Wire.VERSION) throw WireFormatException("unsupported bundle version $version")
        val signing = reader.bytes(32)
        val dh = reader.bytes(32)
        val binding = reader.blob()
        val spkId = reader.u32()
        val spk = reader.bytes(32)
        val spkSignature = reader.blob()
        val otkId = reader.u32()
        val otk = reader.blob()
        val publishedAt = reader.u64()
        reader.expectEnd()
        var oneTimeKey: ByteArray? = null
        if (otkId != NO_PREKEY) {
            if (otk.size != 32) throw WireFormatException("one-time pre-key must be 32 bytes")
            oneTimeKey = otk
        }
        return PreKeyBundle(
            signingKey = signing,
            dhIdentityKey = dh,
            dhBindingSignature = binding,
            signedPreKeyId = spkId,
            signedPreKey = spk,
            signedPreKeySignature = spkSignature,
            oneTimePreKeyId = if (otkId == NO_PREKEY) null else otkId,
            oneTimePreKey = oneTimeKey,
            publishedAt = publishedAt
        )
    }
}
