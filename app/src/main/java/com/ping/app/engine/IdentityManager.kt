package com.ping.app.engine

import com.ping.app.core.crypto.IdentityKeyPair
import com.ping.app.core.crypto.OneTimePreKey
import com.ping.app.core.crypto.PreKeyBundle
import com.ping.app.core.protocol.ContactCard
import com.ping.app.data.CryptoStore
import com.ping.app.data.IdentityRecord
import com.ping.app.data.PingStore
import com.ping.app.data.Settings

/**
 * Owns this device's identity and the pre-key material published to the relay.
 *
 * The identity is created once, on first launch, and never leaves the device: nothing here talks
 * to a server, the relay only ever sees the public bundle produced by [publishableBundle].
 */
class IdentityManager(
    private val store: PingStore,
    private val cryptoStore: CryptoStore,
    private val settings: Settings
) {

    companion object {
        /** How many one-time pre-keys to keep published. */
        const val TARGET_PRE_KEYS = 60
        /** Refill when fewer than this many are left. */
        const val LOW_WATER_MARK = 20
        /** How often the bundle is re-published even when nothing changed. */
        const val REPUBLISH_INTERVAL_MS = 24L * 60 * 60 * 1000
    }

    fun record(): IdentityRecord? = store.loadIdentity()

    fun keyPair(): IdentityKeyPair? = record()?.let { store.identityKeyPair(it) }

    fun hasIdentity(): Boolean = record() != null

    /** Creates the identity. Only ever called from onboarding. */
    fun create(displayName: String): IdentityRecord {
        val existing = record()
        if (existing != null) return existing
        val keys = IdentityKeyPair.generate()
        store.saveIdentity(keys, displayName, System.currentTimeMillis())
        return store.loadIdentity()!!
    }

    /**
     * The card another person needs to reach us: identity keys, display name, mailbox and the
     * relay we are reachable through. Self-signed, so a hostile QR code cannot be edited.
     */
    fun contactCard(): ContactCard? {
        val identity = keyPair() ?: return null
        return ContactCard.create(
            identity = identity,
            displayName = settings.current.displayName,
            relayUrl = settings.current.relayUrl,
            createdAt = System.currentTimeMillis()
        )
    }

    /** Ensures the pool of one-time pre-keys is healthy and the signed pre-key is fresh. */
    fun refreshPreKeys(now: Long = System.currentTimeMillis()): Boolean {
        val identity = keyPair() ?: return false
        var changed = false

        val signedPreKey = cryptoStore.rotateSignedPreKeyIfNeeded(identity, now)
        if (settings.current.signedPreKeyId != signedPreKey.id) {
            settings.setPreKeyPublication(now, signedPreKey.id)
            changed = true
        }

        var available = cryptoStore.unconsumedPreKeyCount()
        if (available < LOW_WATER_MARK) {
            val nextId = cryptoStore.nextPreKeyId()
            val toCreate = (TARGET_PRE_KEYS - available).coerceAtLeast(1)
            val fresh = (0 until toCreate).map { PreKeyBundle.newOneTimePreKey(nextId + it) }
            cryptoStore.addOneTimePreKeys(fresh)
            available += fresh.size
            changed = true
        }
        return changed
    }

    /** Builds the bundle a sender needs, optionally attached to one one-time pre-key. */
    fun publishableBundle(identity: IdentityKeyPair, signedPreKey: com.ping.app.data.StoredSignedPreKey, oneTime: OneTimePreKey?): PreKeyBundle =
        PreKeyBundle(
            signingKey = identity.signing.publicKey,
            dhIdentityKey = identity.dh.publicKey,
            dhBindingSignature = identity.dhBinding,
            signedPreKeyId = signedPreKey.id,
            signedPreKey = signedPreKey.keyPair.publicKey,
            signedPreKeySignature = signedPreKey.signature,
            oneTimePreKeyId = oneTime?.id,
            oneTimePreKey = oneTime?.keyPair?.publicKey,
            publishedAt = System.currentTimeMillis()
        )

    fun signedPreKey(): com.ping.app.data.StoredSignedPreKey? = cryptoStore.activeSignedPreKey()

    /** Rotation happens here so both the relay registration and local lookups agree. */
    fun ensureSignedPreKey(now: Long = System.currentTimeMillis()): com.ping.app.data.StoredSignedPreKey? {
        val identity = keyPair() ?: return null
        return cryptoStore.rotateSignedPreKeyIfNeeded(identity, now)
    }

    fun displayName(): String = settings.current.displayName

    /** Called when the user renames themselves: contacts are told through a profile payload. */
    fun updateDisplayName(name: String) {
        settings.setDisplayName(name)
        store.updateIdentityDisplayName(name)
    }
}
