package com.ping.app.core.protocol

import com.ping.app.core.crypto.Bytes
import com.ping.app.core.crypto.Ed25519
import com.ping.app.core.crypto.PreKeyBundle
import com.ping.app.core.crypto.WireFormatException

/**
 * The relay protocol, client side.
 *
 * The relay is intentionally dumb: it stores public pre-keys so people can reach each other, and
 * it holds end-to-end encrypted envelopes in memory until they are collected. It can read none of
 * it. Every request and response is built and validated here.
 */
object RelayApi {

    const val AUTH_PREFIX = "PingRelayAuth/v1"
    const val MAX_POLL_WAIT_MS = 30_000L
    const val DEFAULT_POLL_WAIT_MS = 25_000L

    /** Message that the client signs to prove it owns a mailbox. */
    fun authMessage(action: String, mailbox: ByteArray, nonce: ByteArray): ByteArray =
        Bytes.concat(Bytes.utf8(AUTH_PREFIX), Bytes.utf8(action), mailbox, nonce)

    fun healthRequest(): Pair<String, String> = "GET" to "/v1/health"

    fun registerRequest(
        signingKey: ByteArray,
        dhIdentityKey: ByteArray,
        dhBinding: ByteArray,
        signedPreKeyId: Int,
        signedPreKey: ByteArray,
        signedPreKeySignature: ByteArray,
        publishedAt: Long,
        oneTimePreKeys: List<Pair<Int, ByteArray>>
    ): String {
        val preKeys = oneTimePreKeys.map { (id, key) ->
            Json.obj(
                "id" to Json.num(id),
                "key" to Json.str(Bytes.toBase64Url(key))
            )
        }
        return Json.write(
            Json.obj(
                "mailbox" to Json.str(Bytes.toBase64Url(Wire.mailboxId(dhIdentityKey))),
                "signingKey" to Json.str(Bytes.toBase64Url(signingKey)),
                "dhIdentityKey" to Json.str(Bytes.toBase64Url(dhIdentityKey)),
                "dhBinding" to Json.str(Bytes.toBase64Url(dhBinding)),
                "signedPreKeyId" to Json.num(signedPreKeyId),
                "signedPreKey" to Json.str(Bytes.toBase64Url(signedPreKey)),
                "signedPreKeySignature" to Json.str(Bytes.toBase64Url(signedPreKeySignature)),
                "publishedAt" to Json.num(publishedAt),
                "oneTimePreKeys" to Json.arr(preKeys)
            )
        )
    }

    fun bundlePath(mailbox: ByteArray): String = "/v1/bundle?mailbox=" + Bytes.toBase64Url(mailbox)

    /**
     * Parses a bundle response.
     *
     * Returns null when the relay does not know the mailbox yet. Every signature is checked again
     * on the client: a hostile relay can withhold a bundle (denial of service) but can never make
     * the client talk to the wrong keys.
     */
    fun parseBundleResponse(body: String): PreKeyBundle? {
        val json = JsonObject(body)
        if (!json.bool("ok", true)) return null
        val fields = json.objectOrNull("bundle") ?: return null
        val bundle = PreKeyBundle(
            signingKey = Bytes.fromBase64Url(fields.str("signingKey")),
            dhIdentityKey = Bytes.fromBase64Url(fields.str("dhIdentityKey")),
            dhBindingSignature = Bytes.fromBase64Url(fields.str("dhBinding")),
            signedPreKeyId = fields.int("signedPreKeyId"),
            signedPreKey = Bytes.fromBase64Url(fields.str("signedPreKey")),
            signedPreKeySignature = Bytes.fromBase64Url(fields.str("signedPreKeySignature")),
            oneTimePreKeyId = fields.intOrNull("oneTimePreKeyId"),
            oneTimePreKey = fields.optStr("oneTimePreKey")?.let { Bytes.fromBase64Url(it) },
            publishedAt = fields.long("publishedAt")
        )
        if (!bundle.verify()) throw WireFormatException("relay returned a bundle with an invalid signature")
        if ((bundle.oneTimePreKeyId == null) != (bundle.oneTimePreKey == null)) {
            throw WireFormatException("relay returned a half one-time pre-key")
        }
        return bundle
    }

    /** The relay hands out a fresh single-use nonce with each successful poll. */
    fun parseNextNonce(body: String): ByteArray? = try {
        JsonObject(body).optStr("nextNonce")?.let { Bytes.fromBase64Url(it) }
    } catch (e: Exception) {
        null
    }

    fun sendRequest(envelope: Wire.Envelope): String = Json.write(
        Json.obj(
            "envelope" to Json.str(Bytes.toBase64Url(envelope.encode()))
        )
    )

    fun challengePath(mailbox: ByteArray): String = "/v1/challenge?mailbox=" + Bytes.toBase64Url(mailbox)

    fun parseChallenge(body: String): ByteArray {
        val json = JsonObject(body)
        if (!json.bool("ok", true)) throw WireFormatException("relay refused the challenge")
        return Bytes.fromBase64Url(json.str("nonce"))
    }

    fun pollRequest(
        mailbox: ByteArray,
        nonce: ByteArray,
        signature: ByteArray,
        waitMs: Long
    ): String = Json.write(
        Json.obj(
            "mailbox" to Json.str(Bytes.toBase64Url(mailbox)),
            "nonce" to Json.str(Bytes.toBase64Url(nonce)),
            "signature" to Json.str(Bytes.toBase64Url(signature)),
            "waitMs" to Json.num(waitMs.coerceIn(0, MAX_POLL_WAIT_MS))
        )
    )

    /** One envelope as the relay handed it over, with the id needed to acknowledge it. */
    class PolledEnvelope(val id: String, val bytes: ByteArray) {
        fun decode(): Wire.Envelope = Wire.Envelope.decode(bytes)
    }

    /**
     * The relay's envelope id is the first 22 characters of the base64url of the SHA-256 of the
     * envelope, so a client can calculate it even if the relay omits it.
     */
    fun envelopeId(bytes: ByteArray): String = Bytes.toBase64Url(Bytes.sha256(bytes)).take(22)

    /**
     * Returns the envelopes the relay handed over.
     *
     * Both shapes are accepted: `{"id": ..., "data": ...}` objects (what current relays send) and
     * plain base64url strings (older relays), in which case the id is derived from the bytes.
     */
    fun parsePollResponse(body: String): List<PolledEnvelope> {
        val json = JsonObject(body)
        if (!json.bool("ok", true)) throw WireFormatException("relay refused the poll")
        return json.array("envelopes").map { value ->
            if (value is JsonValue.Obj) {
                val bytes = Bytes.fromBase64Url(value["data"]?.optString() ?: throw WireFormatException("malformed envelope entry"))
                val id = value["id"]?.optString() ?: envelopeId(bytes)
                PolledEnvelope(id, bytes)
            } else {
                val encoded = value.optString() ?: throw WireFormatException("malformed envelope entry")
                val bytes = Bytes.fromBase64Url(encoded)
                PolledEnvelope(envelopeId(bytes), bytes)
            }
        }
    }

    fun ackRequest(
        mailbox: ByteArray,
        nonce: ByteArray,
        signature: ByteArray,
        envelopeIds: List<String>
    ): String = Json.write(
        Json.obj(
            "mailbox" to Json.str(Bytes.toBase64Url(mailbox)),
            "nonce" to Json.str(Bytes.toBase64Url(nonce)),
            "signature" to Json.str(Bytes.toBase64Url(signature)),
            "ids" to Json.arr(envelopeIds.map { Json.str(it) })
        )
    )

    fun preKeysRequest(
        mailbox: ByteArray,
        nonce: ByteArray,
        signature: ByteArray,
        oneTimePreKeys: List<Pair<Int, ByteArray>>
    ): String {
        val preKeys = oneTimePreKeys.map { (id, key) ->
            Json.obj("id" to Json.num(id), "key" to Json.str(Bytes.toBase64Url(key)))
        }
        return Json.write(
            Json.obj(
                "mailbox" to Json.str(Bytes.toBase64Url(mailbox)),
                "nonce" to Json.str(Bytes.toBase64Url(nonce)),
                "signature" to Json.str(Bytes.toBase64Url(signature)),
                "oneTimePreKeys" to Json.arr(preKeys)
            )
        )
    }

    fun isOk(body: String): Boolean = try {
        JsonObject(body).bool("ok", false)
    } catch (e: Exception) {
        false
    }

    fun errorCode(body: String): String? = try {
        JsonObject(body).optStr("error")
    } catch (e: Exception) {
        null
    }

    /** Verifies a signature produced by a peer (used for relay challenges, kept here for tests). */
    fun verifyAuth(signingKey: ByteArray, action: String, mailbox: ByteArray, nonce: ByteArray, signature: ByteArray): Boolean =
        Ed25519.verify(signingKey, authMessage(action, mailbox, nonce), signature)
}
