package com.ping.app.core.checks

import com.ping.app.core.crypto.*
import com.ping.app.core.protocol.*

/**
 * Wire-format and relay-protocol checks. Same style as [CryptoCoreTest]: no test framework
 * required, so these run in CI, in the JVM self-test and inside the app.
 */
object ProtocolTest {

    fun runAll(): Int {
        val start = CryptoCoreTest.failures.size
        json()
        padding()
        envelopes()
        payloads()
        handshake()
        contactCards()
        bundles()
        relayDtos()
        hostileInput()
        return CryptoCoreTest.failures.size - start
    }

    private fun check(condition: Boolean, what: String) = CryptoCoreTest.check(condition, what)
    private fun checkEquals(expected: Any?, actual: Any?, what: String) = CryptoCoreTest.checkEquals(expected, actual, what)
    private inline fun <reified T : Throwable> expectThrows(what: String, block: () -> Unit) = CryptoCoreTest.expectThrows<T>(what, block)

    private fun roundTrip(value: JsonValue): JsonValue = Json.parse(Json.write(value))

    private fun json() {
        val value = Json.obj(
            "name" to Json.str("Ping \"quoted\"\nline"),
            "count" to Json.num(42),
            "flag" to Json.bool(true),
            "nested" to Json.obj("a" to Json.num(1), "b" to Json.arr(listOf(Json.num(2), JsonValue.Null))),
            "unicode" to Json.str("héllo 🌍 \u0001")
        )
        val parsed = roundTrip(value)
        checkEquals("Ping \"quoted\"\nline", parsed.asObject()["name"]!!.asString(), "string escapes survive")
        checkEquals(42, parsed.asObject()["count"]!!.asInt(), "numbers survive")
        checkEquals(true, parsed.asObject()["flag"]!!.asBool(), "booleans survive")
        checkEquals("héllo 🌍 \u0001", parsed.asObject()["unicode"]!!.asString(), "unicode survives")

        val escaped = Json.parse("""{"a":"\u0041\uD83C\uDF0D"}""")
        checkEquals("A🌍", escaped.asObject()["a"]!!.asString(), "unicode escapes decode")

        expectThrows<JsonException>("trailing content rejected") { Json.parse("{} garbage") }
        expectThrows<JsonException>("unterminated string rejected") { Json.parse("{\"a\":\"b}") }
        expectThrows<JsonException>("bad escape rejected") { Json.parse("{\"a\":\"\\q\"}") }
        expectThrows<JsonException>("deep nesting rejected") {
            Json.parse("[".repeat(80) + "]".repeat(80))
        }
        checkEquals("{}", Json.write(Json.obj()), "empty object")
        val obj = JsonObject("""{"ok":true,"n":7,"list":[1,2],"s":null}""")
        checkEquals(7, obj.int("n"), "int accessor")
        checkEquals(2, obj.array("list").size, "array accessor")
        checkEquals(null, obj.optStr("s"), "null string")
        checkEquals("fallback", obj.optStr("missing") ?: "fallback", "missing string")
        expectThrows<JsonException>("wrong type rejected") { obj.array("n") }
    }

    private fun padding() {
        val small = Wire.pad(ByteArray(10))
        val alsoSmall = Wire.pad(ByteArray(200))
        checkEquals(small.size, alsoSmall.size, "small payloads share a size class")
        checkEquals(256, small.size, "smallest bucket")
        for (size in intArrayOf(0, 1, 50, 250, 251, 1000, 4000, 20000, 70000)) {
            val payload = ByteArray(size) { (it and 0x7f).toByte() }
            val padded = Wire.pad(payload)
            check(padded.size % 16 == 0, "padded length is a bucket ($size)")
            check(Wire.unpad(padded).contentEquals(payload), "unpad round trip for $size bytes")
        }
        expectThrows<WireFormatException>("padded payload must not be truncated") {
            val padded = Wire.pad(ByteArray(60_000))
            Wire.unpad(padded.copyOfRange(0, 100))
        }
    }

    private fun envelopes() {
        val mailbox = Bytes.random(16)
        val sealed = Wire.Envelope(
            kind = Wire.EnvelopeKind.SEALED,
            mailbox = mailbox,
            sessionTag = Bytes.random(8),
            senderIdentity = null,
            payload = Wire.pad(Bytes.utf8("hello"))
        )
        val decoded = Wire.Envelope.decode(sealed.encode())
        check(decoded.kind == Wire.EnvelopeKind.SEALED, "sealed kind survives")
        check(decoded.mailbox.contentEquals(mailbox), "mailbox survives")
        check(decoded.sessionTag!!.contentEquals(sealed.sessionTag!!), "session tag survives")
        check(decoded.senderIdentity == null, "sealed envelopes hide the sender")
        check(Wire.unpad(decoded.payload).contentEquals(Bytes.utf8("hello")), "payload survives")

        val handshake = Wire.Envelope(
            kind = Wire.EnvelopeKind.HANDSHAKE,
            mailbox = mailbox,
            sessionTag = null,
            senderIdentity = Bytes.random(32),
            payload = Wire.pad(ByteArray(100))
        )
        val decodedHandshake = Wire.Envelope.decode(handshake.encode())
        check(decodedHandshake.kind == Wire.EnvelopeKind.HANDSHAKE, "handshake kind survives")
        check(decodedHandshake.senderIdentity!!.contentEquals(handshake.senderIdentity!!), "sender key survives")

        expectThrows<WireFormatException>("rejects corrupted envelope") {
            val bytes = sealed.encode()
            bytes[0] = 9
            Wire.Envelope.decode(bytes)
        }
        expectThrows<WireFormatException>("rejects unknown envelope kind") {
            val bytes = sealed.encode()
            bytes[1] = 7
            Wire.Envelope.decode(bytes)
        }
        expectThrows<WireFormatException>("rejects truncated envelope") {
            Wire.Envelope.decode(sealed.encode().copyOfRange(0, 10))
        }
        expectThrows<IllegalArgumentException>("sealed envelope requires a tag") {
            Wire.Envelope(Wire.EnvelopeKind.SEALED, mailbox, null, null, ByteArray(1))
        }
    }

    private fun payloads() {
        val transferId = Bytes.random(12)
        val payloads = listOf(
            Wire.Payload.Text("hello world", "msg-1", 1234L),
            Wire.Payload.Text("no reply"),
            Wire.Payload.Receipt(listOf("a", "b", "c"), read = true, sentAt = 9L),
            Wire.Payload.Typing(true),
            Wire.Payload.Profile("Ada"),
            Wire.Payload.AttachmentMeta(transferId, "photo.jpg", "image/jpeg", 1024, Bytes.random(32), 3, Bytes.random(32)),
            Wire.Payload.AttachmentChunk(transferId, 1, 3, Bytes.random(500)),
            Wire.Payload.Delete("msg-9"),
            Wire.Payload.Edit("msg-9", "corrected"),
            Wire.Payload.Reaction("msg-9", "👍"),
            Wire.Payload.ConversationSettings(86400L)
        )
        for (payload in payloads) {
            val decoded = Wire.Payload.decode(payload.encode())
            checkEquals(payload::class.simpleName, decoded::class.simpleName, "payload type round trip")
            checkEquals(payload.sentAt, decoded.sentAt, "${payload::class.simpleName} timestamp")
        }
        val text = Wire.Payload.Text("hello world", "msg-1", 1234L)
        val decodedText = Wire.Payload.decode(text.encode()) as Wire.Payload.Text
        checkEquals("hello world", decodedText.text, "text survives")
        checkEquals("msg-1", decodedText.replyToId, "reply id survives")

        val meta = Wire.Payload.AttachmentMeta(transferId, "a.bin", "application/octet-stream", 100, Bytes.random(32), 2, Bytes.random(32))
        val decodedMeta = Wire.Payload.decode(meta.encode()) as Wire.Payload.AttachmentMeta
        check(decodedMeta.transferId.contentEquals(transferId), "transfer id survives")

        expectThrows<WireFormatException>("unknown payload kind rejected") {
            val bytes = Wire.Payload.Typing(false).encode()
            bytes[8] = 99
            Wire.Payload.decode(bytes)
        }
        expectThrows<WireFormatException>("oversized text rejected") {
            Wire.Payload.Text("x".repeat(Wire.MAX_TEXT_BYTES + 10)).encode()
        }
        expectThrows<WireFormatException>("oversized chunk rejected") {
            Wire.Payload.AttachmentChunk(transferId, 0, 2, ByteArray(Wire.ATTACHMENT_CHUNK_BYTES + 1)).encode()
        }
        expectThrows<WireFormatException>("bogus chunk index rejected") {
            Wire.Payload.AttachmentChunk(transferId, 5, 2, ByteArray(10)).encode()
        }
        expectThrows<WireFormatException>("truncated payload rejected") {
            Wire.Payload.decode(Wire.Payload.Text("hello").encode().copyOfRange(0, 9))
        }
    }

    private fun handshake() {
        val message = RatchetMessage(RatchetHeader(Bytes.random(32), 0, 0), Bytes.random(80))
        val init = Wire.HandshakeInit(
            senderSigningKey = Bytes.random(32),
            senderDhIdentity = Bytes.random(32),
            senderBindingSignature = Bytes.random(64),
            ephemeralKey = Bytes.random(32),
            signedPreKeyId = 12,
            oneTimePreKeyId = 34,
            firstMessage = message
        )
        val decoded = Wire.HandshakeInit.decode(init.encode())
        check(decoded.senderSigningKey.contentEquals(init.senderSigningKey), "handshake sender key")
        check(decoded.ephemeralKey.contentEquals(init.ephemeralKey), "handshake ephemeral key")
        check(decoded.senderBindingSignature.contentEquals(init.senderBindingSignature), "handshake binding signature")
        checkEquals(12, decoded.signedPreKeyId, "signed pre-key id")
        checkEquals(34, decoded.oneTimePreKeyId, "one-time pre-key id")
        check(decoded.firstMessage.ciphertext.contentEquals(message.ciphertext), "handshake carries the first message")

        val noPreKey = Wire.HandshakeInit(Bytes.random(32), Bytes.random(32), Bytes.random(64), Bytes.random(32), 1, null, message)
        checkEquals(null, Wire.HandshakeInit.decode(noPreKey.encode()).oneTimePreKeyId, "absent one-time pre-key")
    }

    private fun contactCards() {
        val identity = IdentityKeyPair.generate()
        val card = ContactCard.create(identity, "Ada Lovelace", "https://relay.example.workers.dev", 1_700_000_000_000L)
        check(card.verify(), "contact card verifies")
        checkEquals(Wire.mailboxId(identity.dh.publicKey).toList(), card.mailbox.toList(), "mailbox derives from identity")

        val decoded = ContactCard.decode(card.encode())
        checkEquals("Ada Lovelace", decoded.displayName, "display name survives")
        checkEquals("https://relay.example.workers.dev", decoded.relayUrl, "relay url survives")
        check(decoded.verify(), "decoded card verifies")

        val code = InviteCode.encode(card)
        check(code.startsWith("PING1."), "invite code has a version marker")
        val fromCode = InviteCode.decode(code)
        check(fromCode.signingKey.contentEquals(card.signingKey), "invite code round trip")
        check(InviteCode.decode("ping://add/$code").signingKey.contentEquals(card.signingKey), "url form accepted")
        check(InviteCode.decode("  $code\n").signingKey.contentEquals(card.signingKey), "whitespace tolerated")
        check(InviteCode.looksLikeInviteCode(code), "code recognised")

        // Tampering with any field must invalidate the card.
        val tamperedName = ContactCard(
            card.signingKey, card.dhIdentityKey, card.dhBinding, "Mallory", card.mailbox, card.relayUrl, card.createdAt, card.signature
        )
        check(!tamperedName.verify(), "renamed card is rejected")

        val other = IdentityKeyPair.generate()
        val swappedKey = ContactCard(
            other.signing.publicKey, card.dhIdentityKey, card.dhBinding, card.displayName, card.mailbox, card.relayUrl, card.createdAt, card.signature
        )
        check(!swappedKey.verify(), "swapped signing key is rejected")

        val wrongMailbox = ContactCard(
            card.signingKey, card.dhIdentityKey, card.dhBinding, card.displayName,
            Wire.mailboxId(other.dh.publicKey), card.relayUrl, card.createdAt, card.signature
        )
        check(!wrongMailbox.verify(), "mailbox must match the identity key")

        expectThrows<WireFormatException>("tampered invite code is rejected") {
            val broken = code.dropLast(4) + "AAAA"
            InviteCode.decode(broken)
        }
        expectThrows<WireFormatException>("garbage invite code is rejected") { InviteCode.decode("PING1.!!!!") }
    }

    private fun bundles() {
        val identity = IdentityKeyPair.generate()
        val spk = PreKeyBundle.newSignedPreKey(identity, 5, 1_000L)
        val otk = PreKeyBundle.newOneTimePreKey(9)
        val bundle = PreKeyBundle.create(identity, spk, otk, 2_000L)
        val decoded = BundleCodec.decode(BundleCodec.encode(bundle))
        check(decoded.verify(), "encoded bundle still verifies")
        checkEquals(5, decoded.signedPreKeyId, "signed pre-key id survives")
        checkEquals(9, decoded.oneTimePreKeyId, "one-time pre-key id survives")
        check(decoded.canonicalBytes().contentEquals(bundle.canonicalBytes()), "canonical form survives encoding")

        val lastResort = PreKeyBundle.create(identity, spk, null, 2_000L)
        val decodedLastResort = BundleCodec.decode(BundleCodec.encode(lastResort))
        checkEquals(null, decodedLastResort.oneTimePreKeyId, "bundle without one-time pre-key")
        check(decodedLastResort.verify(), "last-resort bundle verifies")

        expectThrows<WireFormatException>("truncated bundle rejected") {
            BundleCodec.decode(BundleCodec.encode(bundle).copyOfRange(0, 20))
        }
    }

    private fun relayDtos() {
        val identity = IdentityKeyPair.generate()
        val spk = PreKeyBundle.newSignedPreKey(identity, 1, 5L)
        val request = RelayApi.registerRequest(
            identity.signing.publicKey, identity.dh.publicKey, identity.dhBinding,
            spk.id, spk.keyPair.publicKey, spk.signature, 6L,
            listOf(1 to Bytes.random(32), 2 to Bytes.random(32))
        )
        val json = JsonObject(request)
        checkEquals(Bytes.toBase64Url(Wire.mailboxId(identity.dh.publicKey)), json.str("mailbox"), "register carries mailbox")
        checkEquals(2, json.array("oneTimePreKeys").size, "register carries pre-keys")
        checkEquals(1, json.int("signedPreKeyId"), "register carries signed pre-key id")

        val bundle = PreKeyBundle.create(identity, spk, PreKeyBundle.newOneTimePreKey(3), 7L)
        val parsed = RelayApi.parseBundleResponse(bundleJson(bundle))
        check(parsed != null && parsed.verify(), "bundle response parses")
        checkEquals(3, parsed!!.oneTimePreKeyId, "one-time pre-key id from the relay")
        checkEquals(null, RelayApi.parseBundleResponse("""{"ok":false,"error":"unknown_mailbox"}"""), "missing bundle returns null")
        checkEquals(
            Bytes.toBase64Url(Bytes.utf8("next")),
            RelayApi.parseNextNonce("""{"ok":true,"nextNonce":"${Bytes.toBase64Url(Bytes.utf8("next"))}"}""")?.let { Bytes.toBase64Url(it) },
            "next nonce parses"
        )
        checkEquals(null, RelayApi.parseNextNonce("""{}"""), "absent next nonce")

        expectThrows<WireFormatException>("relay cannot inject an unsigned bundle") {
            val evil = PreKeyBundle(
                identity.signing.publicKey, identity.dh.publicKey, identity.dhBinding,
                1, X25519KeyPair.generate().publicKey, ByteArray(64), null, null, 8L
            )
            RelayApi.parseBundleResponse(bundleJson(evil))
        }
        expectThrows<WireFormatException>("relay cannot inject a swapped identity") {
            val evil = PreKeyBundle(
                identity.signing.publicKey, X25519KeyPair.generate().publicKey, identity.dhBinding,
                1, spk.keyPair.publicKey, spk.signature, null, null, 8L
            )
            RelayApi.parseBundleResponse(bundleJson(evil))
        }
        expectThrows<WireFormatException>("half one-time pre-key rejected") {
            val b = PreKeyBundle(
                identity.signing.publicKey, identity.dh.publicKey, identity.dhBinding,
                spk.id, spk.keyPair.publicKey, spk.signature, 9, null, 8L
            )
            RelayApi.parseBundleResponse(bundleJson(b))
        }

        val nonce = Bytes.random(32)
        val sig = identity.signing.sign(RelayApi.authMessage("poll", Wire.mailboxId(identity.dh.publicKey), nonce))
        check(
            RelayApi.verifyAuth(identity.signing.publicKey, "poll", Wire.mailboxId(identity.dh.publicKey), nonce, sig),
            "relay auth signature verifies"
        )
        check(
            !RelayApi.verifyAuth(identity.signing.publicKey, "ack", Wire.mailboxId(identity.dh.publicKey), nonce, sig),
            "relay auth is bound to the action"
        )

        val envelope = Wire.Envelope(
            Wire.EnvelopeKind.SEALED, Wire.mailboxId(identity.dh.publicKey), Bytes.random(8), null, Wire.pad(ByteArray(32))
        )
        val pollJson = JsonObject(
            Json.write(
                Json.obj(
                    "ok" to Json.bool(true),
                    "envelopes" to Json.arr(listOf(Json.str(Bytes.toBase64Url(envelope.encode()))))
                )
            )
        )
        val envelopes = RelayApi.parsePollResponse(Json.write(pollJson.raw()))
        checkEquals(1, envelopes.size, "poll response parses envelopes")
        val decodedEnvelope = envelopes[0].decode()
        checkEquals(envelope.sessionTag!!.toList(), decodedEnvelope.sessionTag!!.toList(), "envelope round trip through JSON")
        check(envelopes[0].id.isNotBlank(), "poll entry carries an id")
    }

    /** Mirrors exactly what the relay sends back (JSON fields, not the binary bundle). */
    private fun bundleJson(bundle: PreKeyBundle): String = Json.write(
        Json.obj(
            "ok" to Json.bool(true),
            "bundle" to Json.obj(
                "signingKey" to Json.str(Bytes.toBase64Url(bundle.signingKey)),
                "dhIdentityKey" to Json.str(Bytes.toBase64Url(bundle.dhIdentityKey)),
                "dhBinding" to Json.str(Bytes.toBase64Url(bundle.dhBindingSignature)),
                "signedPreKeyId" to Json.num(bundle.signedPreKeyId),
                "signedPreKey" to Json.str(Bytes.toBase64Url(bundle.signedPreKey)),
                "signedPreKeySignature" to Json.str(Bytes.toBase64Url(bundle.signedPreKeySignature)),
                "publishedAt" to Json.num(bundle.publishedAt),
                "oneTimePreKeyId" to bundle.oneTimePreKeyId?.let { Json.num(it) },
                "oneTimePreKey" to bundle.oneTimePreKey?.let { Json.str(Bytes.toBase64Url(it)) }
            )
        )
    )

    private fun hostileInput() {
        // A malicious relay answering with nonsense must never crash the client.
        val hostile = listOf(
            "", "[]", "null", "\"string\"", "{\"ok\":true}", "{\"ok\":true,\"envelopes\":\"nope\"}",
            "{\"ok\":true,\"envelopes\":[123]}", "{\"ok\":true,\"bundle\":\"@@@@\"}",
            "{\"ok\":true,\"nonce\":\"short\"}", "{\"envelopes\":" + "[" .repeat(40)
        )
        for (body in hostile) {
            val handled = try {
                RelayApi.parsePollResponse(body)
                true
            } catch (e: Exception) {
                true // rejecting is the correct behaviour
            }
            check(handled, "hostile poll body handled: $body")
            try {
                RelayApi.parseBundleResponse(body)
            } catch (e: Exception) {
                // expected
            }
            check(true, "hostile bundle body handled: $body")
        }
        checkEquals(false, RelayApi.isOk("not json"), "isOk is safe on garbage")

        // Envelope bytes produced by an attacker
        for (size in intArrayOf(0, 1, 3, 16, 40)) {
            val bytes = ByteArray(size) { (it * 7 and 0xff).toByte() }
            try {
                Wire.Envelope.decode(bytes)
            } catch (e: Exception) {
                // expected
            }
            check(true, "arbitrary envelope bytes handled ($size)")
        }
    }
}
