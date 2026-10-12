package com.ping.app.core.checks

import com.ping.app.core.crypto.Bytes
import com.ping.app.core.crypto.IdentityKeyPair
import com.ping.app.core.crypto.OneTimePreKey
import com.ping.app.core.crypto.PreKeyBundle
import com.ping.app.core.crypto.RatchetSession
import com.ping.app.core.crypto.SignedPreKey
import com.ping.app.core.crypto.X25519KeyPair
import com.ping.app.core.crypto.X3dh
import com.ping.app.core.protocol.JsonObject
import com.ping.app.core.protocol.RelayApi
import com.ping.app.core.protocol.Wire
import java.net.HttpURLConnection
import java.net.URL

/**
 * End-to-end check of the client core against a **running relay**.
 *
 * This is the half of the test suite that a unit test cannot fake: it registers two identities,
 * fetches a bundle, walks a real X3DH handshake and Double Ratchet conversation through
 * `POST /v1/send` and `POST /v1/poll`, acknowledges the envelope, and then tries to break the
 * relay (forged signatures, a hijacked mailbox, a replayed nonce, a tampered-by-definition
 * envelope). Both relay implementations — `server/node` and `server/cloudflare` — are expected to
 * pass every check, which is what keeps them drop-in compatible.
 *
 * It is skipped automatically when no relay is reachable, so `./gradlew test` stays green on a
 * laptop with nothing running:
 *
 * ```bash
 * node server/node/server.js --port 8080 &          # or: npx wrangler dev --port 8080
 * PING_RELAY=http://127.0.0.1:8080 ./gradlew testDebugUnitTest
 * ```
 */
object RelayE2E {

    var passed = 0
        private set
    val failures = mutableListOf<String>()

    private fun check(condition: Boolean, what: String) {
        if (condition) passed++ else failures.add(what)
    }

    private fun http(method: String, url: String, body: String? = null): Pair<Int, String> {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = 40_000
            if (body != null) {
                doOutput = true
                setRequestProperty("content-type", "application/json")
            }
        }
        if (body != null) connection.outputStream.use { it.write(body.toByteArray()) }
        val status = connection.responseCode
        val stream = if (status >= 400) connection.errorStream else connection.inputStream
        val text = stream?.readBytes()?.toString(Charsets.UTF_8) ?: ""
        connection.disconnect()
        return status to text
    }

    private class Registered(
        val identity: IdentityKeyPair,
        val signedPreKey: SignedPreKey,
        val preKeys: List<OneTimePreKey>
    ) {
        val mailbox = Wire.mailboxId(identity.dh.publicKey)
    }

    private fun register(base: String, identity: IdentityKeyPair, preKeyCount: Int): Registered {
        val spk = PreKeyBundle.newSignedPreKey(identity, 1, System.currentTimeMillis())
        val preKeys = (1..preKeyCount).map { PreKeyBundle.newOneTimePreKey(it) }
        val body = RelayApi.registerRequest(
            identity.signing.publicKey, identity.dh.publicKey, identity.dhBinding,
            spk.id, spk.keyPair.publicKey, spk.signature, System.currentTimeMillis(),
            preKeys.map { it.id to it.keyPair.publicKey }
        )
        val (status, response) = http("POST", "$base/v1/register", body)
        check(status == 200, "register -> HTTP $status: $response")
        return Registered(identity, spk, preKeys)
    }

    private fun fetchBundle(base: String, mailbox: ByteArray): PreKeyBundle? {
        val (status, response) = http("GET", base + RelayApi.bundlePath(mailbox))
        if (status == 404) return null
        check(status == 200, "fetch bundle -> HTTP $status: $response")
        return RelayApi.parseBundleResponse(response)
    }

    private fun auth(base: String, identity: IdentityKeyPair, action: String): Pair<ByteArray, ByteArray> {
        val mailbox = Wire.mailboxId(identity.dh.publicKey)
        val (status, response) = http("GET", base + RelayApi.challengePath(mailbox))
        check(status == 200, "challenge -> HTTP $status: $response")
        val nonce = RelayApi.parseChallenge(response)
        return nonce to identity.signing.sign(RelayApi.authMessage(action, mailbox, nonce))
    }

    /** Runs every check; returns the failure descriptions (empty == all good). */
    fun runAll(base: String): List<String> {
        passed = 0
        failures.clear()

        val (healthStatus, health) = http("GET", "$base/v1/health")
        check(healthStatus == 200, "health -> HTTP $healthStatus")
        check(health.contains("\"storesMessagesOnDisk\":false"), "relay advertises memory-only storage")

        val alice = register(base, IdentityKeyPair.generate(), 3)
        val bob = register(base, IdentityKeyPair.generate(), 3)

        check(
            fetchBundle(base, Wire.mailboxId(IdentityKeyPair.generate().dh.publicKey)) == null,
            "unknown mailbox has no bundle"
        )

        val bobBundle = fetchBundle(base, bob.mailbox)
        check(bobBundle != null && bobBundle.verify(), "bob's bundle verifies on the client")
        check(bobBundle!!.oneTimePreKeyId == 1, "relay handed out the first one-time pre-key")
        val secondFetch = fetchBundle(base, bob.mailbox)!!
        check(
            secondFetch.oneTimePreKeyId == 2,
            "one-time pre-keys are handed out once (got ${secondFetch.oneTimePreKeyId})"
        )

        // ---- Alice starts the session -----------------------------------------------------
        val initiation = X3dh.initiate(alice.identity, bobBundle)
        val aliceSession = RatchetSession.initiator(
            initiation.masterSecret, bobBundle.signedPreKey, initiation.associatedData
        )
        val text = "hello bob, this is end-to-end encrypted ✨"
        val firstMessage = aliceSession.encrypt(Wire.Payload.Text(text, sentAt = 1_700_000_000_000L).encode())
        val handshake = Wire.HandshakeInit(
            senderSigningKey = alice.identity.signing.publicKey,
            senderDhIdentity = alice.identity.dh.publicKey,
            senderBindingSignature = alice.identity.dhBinding,
            ephemeralKey = initiation.ephemeral.publicKey,
            signedPreKeyId = bobBundle.signedPreKeyId,
            oneTimePreKeyId = bobBundle.oneTimePreKeyId,
            firstMessage = firstMessage
        )
        val envelope = Wire.Envelope(
            Wire.EnvelopeKind.HANDSHAKE, bob.mailbox, null, alice.identity.dh.publicKey,
            Wire.pad(handshake.encode())
        )
        val (sendStatus, sendResponse) = http("POST", "$base/v1/send", RelayApi.sendRequest(envelope))
        check(sendStatus == 202, "send -> HTTP $sendStatus: $sendResponse")
        val queuedId = JsonObject(sendResponse).str("id")

        // ---- Bob collects and decrypts ----------------------------------------------------
        val (nonce, signature) = auth(base, bob.identity, "poll")
        val (pollStatus, pollResponse) = http(
            "POST", "$base/v1/poll", RelayApi.pollRequest(bob.mailbox, nonce, signature, 1000)
        )
        check(pollStatus == 200, "poll -> HTTP $pollStatus: $pollResponse")
        val polled = RelayApi.parsePollResponse(pollResponse)
        check(polled.size == 1, "bob received exactly one envelope")
        check(polled.first().id == queuedId, "poll hands back the envelope id the relay accepted")
        val incoming = polled.first().decode()
        check(incoming.kind == Wire.EnvelopeKind.HANDSHAKE, "envelope is a handshake")
        check(
            incoming.senderIdentity!!.contentEquals(alice.identity.dh.publicKey),
            "handshake identifies the sender"
        )

        val received = Wire.HandshakeInit.decode(Wire.unpad(incoming.payload))
        check(received.signedPreKeyId == bob.signedPreKey.id, "handshake names bob's signed pre-key")
        val usedPreKey = bob.preKeys.first { it.id == received.oneTimePreKeyId }
        val bobMaster = X3dh.respond(
            identity = bob.identity,
            signedPreKey = bob.signedPreKey.keyPair,
            oneTimePreKey = usedPreKey.keyPair,
            initiatorSigningKey = received.senderSigningKey,
            initiatorDhKey = received.senderDhIdentity,
            initiatorEphemeral = received.ephemeralKey
        )
        check(bobMaster.contentEquals(initiation.masterSecret), "both sides derived the same master secret")

        val bobSession = RatchetSession.responder(bobMaster, bob.signedPreKey.keyPair, initiation.associatedData)
        val decrypted = Wire.Payload.decode(bobSession.decrypt(received.firstMessage.header, received.firstMessage.ciphertext))
        check(decrypted is Wire.Payload.Text && decrypted.text == text, "bob decrypted the message")

        // ---- Acknowledge: the relay forgets the envelope ----------------------------------
        val nextNonce = RelayApi.parseNextNonce(pollResponse)
        check(nextNonce != null, "relay handed out a follow-up nonce")
        val ackSignature = bob.identity.signing.sign(RelayApi.authMessage("ack", bob.mailbox, nextNonce!!))
        val (ackStatus, ackResponse) = http(
            "POST", "$base/v1/ack",
            RelayApi.ackRequest(bob.mailbox, nextNonce, ackSignature, listOf(polled.first().id))
        )
        check(ackStatus == 200, "ack -> HTTP $ackStatus: $ackResponse")
        check(JsonObject(ackResponse).int("removed") >= 1, "ack removed the envelope")

        val (pollNonce2, pollSig2) = auth(base, bob.identity, "poll")
        val (_, emptyPoll) = http("POST", "$base/v1/poll", RelayApi.pollRequest(bob.mailbox, pollNonce2, pollSig2, 0))
        check(RelayApi.parsePollResponse(emptyPoll).isEmpty(), "acknowledged envelope is gone from the relay")

        // ---- Bob replies, sealed-sender style --------------------------------------------
        val replyText = "hi alice, got it"
        val reply = bobSession.encrypt(Wire.Payload.Text(replyText).encode())
        val sealed = Wire.Envelope(
            Wire.EnvelopeKind.SEALED, alice.mailbox, aliceSession.sessionTag(), null, Wire.pad(reply.encode())
        )
        val (replyStatus, replyResponse) = http("POST", "$base/v1/send", RelayApi.sendRequest(sealed))
        check(replyStatus == 202, "reply -> HTTP $replyStatus: $replyResponse")

        val (aliceNonce, aliceSignature) = auth(base, alice.identity, "poll")
        val (_, alicePoll) = http(
            "POST", "$base/v1/poll", RelayApi.pollRequest(alice.mailbox, aliceNonce, aliceSignature, 1000)
        )
        val aliceEnvelopes = RelayApi.parsePollResponse(alicePoll)
        check(aliceEnvelopes.size == 1, "alice received the reply")
        val delivered = aliceEnvelopes.first().decode()
        check(
            delivered.kind == Wire.EnvelopeKind.SEALED && delivered.senderIdentity == null,
            "reply hides the sender from the relay"
        )
        check(delivered.sessionTag!!.contentEquals(aliceSession.sessionTag()), "session tag matches the established session")

        val replyMessage = Wire.decodeRatchet(Wire.unpad(delivered.payload))
        val replyPayload = Wire.Payload.decode(aliceSession.decrypt(replyMessage.header, replyMessage.ciphertext))
        check(replyPayload is Wire.Payload.Text && replyPayload.text == replyText, "alice decrypted the reply")

        // ---- Long polling actually waits --------------------------------------------------
        val start = System.currentTimeMillis()
        val (lpNonce, lpSig) = auth(base, bob.identity, "poll")
        val (_, longPoll) = http("POST", "$base/v1/poll", RelayApi.pollRequest(bob.mailbox, lpNonce, lpSig, 1200))
        val elapsed = System.currentTimeMillis() - start
        check(elapsed >= 1000, "long poll held the connection for ${elapsed}ms")
        check(RelayApi.parsePollResponse(longPoll).isEmpty(), "long poll returned nothing new")

        // ---- Pre-key replenishment --------------------------------------------------------
        val (keysNonce, keysSignature) = auth(base, bob.identity, "keys")
        val fresh = PreKeyBundle.newOneTimePreKey(50)
        val (keysStatus, keysResponse) = http(
            "POST", "$base/v1/prekeys",
            RelayApi.preKeysRequest(bob.mailbox, keysNonce, keysSignature, listOf(fresh.id to fresh.keyPair.publicKey))
        )
        check(keysStatus == 200, "prekeys -> HTTP $keysStatus: $keysResponse")
        check(fetchBundle(base, bob.mailbox)!!.oneTimePreKeyId == 3, "pool still had one pre-key")
        check(fetchBundle(base, bob.mailbox)!!.oneTimePreKeyId == 50, "replenished pre-key is handed out next")
        check(fetchBundle(base, bob.mailbox)!!.oneTimePreKeyId == null, "exhausted pool falls back to the signed pre-key only")

        // ---- Abuse cases ------------------------------------------------------------------
        val (badBundleStatus, badBundle) = http(
            "POST", "$base/v1/register",
            RelayApi.registerRequest(
                alice.identity.signing.publicKey, alice.identity.dh.publicKey, alice.identity.dhBinding,
                1, X25519KeyPair.generate().publicKey, ByteArray(64), System.currentTimeMillis(), emptyList()
            )
        )
        check(badBundleStatus == 400, "relay rejects an invalid pre-key signature (got $badBundleStatus: $badBundle)")

        val (hijackStatus, _) = http(
            "POST", "$base/v1/register",
            RelayApi.registerRequest(
                alice.identity.signing.publicKey, X25519KeyPair.generate().publicKey, alice.identity.dhBinding,
                1, alice.signedPreKey.keyPair.publicKey, alice.signedPreKey.signature,
                System.currentTimeMillis(), emptyList()
            )
        )
        check(hijackStatus == 400, "relay refuses a mailbox that does not match the identity key (got $hijackStatus)")

        val (unknownStatus, _) = http(
            "POST", "$base/v1/send",
            RelayApi.sendRequest(
                Wire.Envelope(Wire.EnvelopeKind.SEALED, Bytes.random(16), Bytes.random(8), null, Wire.pad(ByteArray(20)))
            )
        )
        check(unknownStatus == 404, "relay rejects envelopes for unknown mailboxes (got $unknownStatus)")

        val (replayStatus, _) = http("POST", "$base/v1/poll", RelayApi.pollRequest(bob.mailbox, pollNonce2, pollSig2, 0))
        check(replayStatus == 401, "nonce cannot be replayed (got $replayStatus)")

        val forgedSignature = bob.identity.signing.sign(RelayApi.authMessage("ack", bob.mailbox, nonce))
        val (badSigStatus, _) = http(
            "POST", "$base/v1/ack", RelayApi.ackRequest(bob.mailbox, nonce, forgedSignature, emptyList())
        )
        check(badSigStatus == 401, "consumed nonce is rejected (got $badSigStatus)")

        val (tamperedStatus, _) = http("POST", "$base/v1/send", RelayApi.sendRequest(forgedEnvelopeFor(bob.mailbox)))
        check(tamperedStatus == 202, "anyone may deliver to a mailbox (sealed sender), got $tamperedStatus")

        val (malformedStatus, _) = http("POST", "$base/v1/send", """{"envelope":"AAAA"}""")
        check(malformedStatus == 400, "malformed envelopes are rejected (got $malformedStatus)")

        return failures.toList()
    }

    private fun forgedEnvelopeFor(mailbox: ByteArray) =
        Wire.Envelope(Wire.EnvelopeKind.SEALED, mailbox, Bytes.random(8), null, Wire.pad(ByteArray(30)))
}
