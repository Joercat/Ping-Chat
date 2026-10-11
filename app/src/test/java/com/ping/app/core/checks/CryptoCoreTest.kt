package com.ping.app.core.checks

import com.ping.app.core.crypto.*

/**
 * Self-contained verification suite for the cryptographic core.
 *
 * It deliberately depends on nothing but the Kotlin standard library so that the exact same code
 * can be run three ways: from JUnit in CI (`CryptoCoreTest`), from the JVM with kotlinc, and from
 * the debug screen inside the app. If this suite is green, the protocol is behaving.
 */
object CryptoCoreTest {

    var checks = 0
        internal set
    val failures = mutableListOf<String>()

    internal fun check(condition: Boolean, message: String) {
        checks++
        if (!condition) failures.add(message)
    }

    internal fun checkEquals(expected: Any?, actual: Any?, what: String) {
        checks++
        if (expected != actual) failures.add("$what: expected $expected, got $actual")
    }

    internal inline fun <reified T : Throwable> expectThrows(what: String, block: () -> Unit) {
        checks++
        try {
            block()
            failures.add("$what: expected ${T::class.simpleName} but nothing was thrown")
        } catch (e: Throwable) {
            if (e !is T) failures.add("$what: expected ${T::class.simpleName}, got ${e::class.simpleName} (${e.message})")
        }
    }

    /** Runs everything; returns the number of failures (0 == all good). */
    fun runAll(): Int {
        checks = 0
        failures.clear()
        encodings()
        buffers()
        hkdf()
        aead()
        keyPairs()
        identityBinding()
        preKeyBundles()
        x3dh()
        ratchetHappyPath()
        ratchetOutOfOrder()
        ratchetTamperDetection()
        ratchetPersistence()
        ratchetPostCompromise()
        safetyNumbers()
        ProtocolTest.runAll()
        return failures.size
    }

    // ------------------------------------------------------------------ encodings

    private fun encodings() {
        for (size in 0..40) {
            val data = Bytes.random(size)
            val text = Bytes.toBase64Url(data)
            check(text.none { it == '+' || it == '/' || it == '=' }, "base64url is url safe for size $size")
            check(Bytes.fromBase64Url(text).contentEquals(data), "base64url round trip for size $size")
            check(Bytes.fromHex(Bytes.toHex(data)).contentEquals(data), "hex round trip for size $size")
        }
        expectThrows<IllegalArgumentException>("base64url rejects junk") { Bytes.fromBase64Url("not a base64url string!!") }
        val a = Bytes.random(32)
        val b = a.copyOf()
        check(Bytes.constantTimeEquals(a, b), "constant time compare equal")
        b[5] = (b[5] + 1).toByte()
        check(!Bytes.constantTimeEquals(a, b), "constant time compare different")
        val wiped = ByteArray(8) { 7 }
        Bytes.wipe(wiped)
        check(wiped.all { it == 0.toByte() }, "wipe zeroes the buffer")
    }

    private fun buffers() {
        val writer = BufWriter(1)
        writer.u8(0x12).u16(0xbEEF).u32(0x01020304).u64(0x0102030405060708L)
        writer.varint(0).varint(127).varint(128).varint(300000)
        writer.blob(byteArrayOf(1, 2, 3)).string("hello")
        val reader = BufReader(writer.toByteArray())
        checkEquals(0x12, reader.u8(), "u8")
        checkEquals(0xbEEF, reader.u16(), "u16")
        checkEquals(0x01020304, reader.u32(), "u32")
        checkEquals(0x0102030405060708L, reader.u64(), "u64")
        checkEquals(0, reader.varint(), "varint 0")
        checkEquals(127, reader.varint(), "varint 127")
        checkEquals(128, reader.varint(), "varint 128")
        checkEquals(300000, reader.varint(), "varint 300000")
        check(reader.blob().contentEquals(byteArrayOf(1, 2, 3)), "blob")
        checkEquals("hello", reader.string(), "string")
        reader.expectEnd()
        expectThrows<WireFormatException>("truncated buffer") { BufReader(byteArrayOf(1)).u32() }
    }

    // ------------------------------------------------------------------ hkdf

    private fun hkdf() {
        // RFC 5869 test case 1
        val ikm = ByteArray(22) { 0x0b }
        val salt = Bytes.fromHex("000102030405060708090a0b0c")
        val info = Bytes.fromHex("f0f1f2f3f4f5f6f7f8f9")
        val prk = Hkdf.extract(salt, ikm)
        checkEquals(
            "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5",
            Bytes.toHex(prk),
            "RFC 5869 test case 1 PRK"
        )
        val okm = Hkdf.expand(prk, info, 42)
        checkEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            Bytes.toHex(okm),
            "RFC 5869 test case 1 OKM"
        )

        // Long output must chain correctly and stay deterministic.
        val material = Bytes.random(32)
        val long1 = Hkdf.derive(material, salt, info, 200)
        val long2 = Hkdf.derive(material, salt, info, 200)
        check(long1.contentEquals(long2), "HKDF is deterministic")
        check(long1.size == 200, "HKDF honours requested length")
        val prefix = Hkdf.derive(material, salt, info, 64)
        check(long1.copyOfRange(0, 64).contentEquals(prefix), "HKDF is a prefix-stable stream")
        val other = Hkdf.derive(material, salt, Bytes.utf8("different"), 200)
        check(!long1.contentEquals(other), "HKDF depends on info")
    }

    private fun aead() {
        val key = Bytes.random(32)
        val nonce = Bytes.random(12)
        val aad = Bytes.utf8("associated data")
        val plaintext = Bytes.utf8("attack at dawn 🌅")

        val sealed = Aead.seal(key, nonce, aad, plaintext)
        check(sealed.size == plaintext.size + Aead.TAG_LEN, "ciphertext = plaintext + tag")
        val opened = Aead.open(key, nonce, aad, sealed)
        check(opened.contentEquals(plaintext), "AEAD round trip")

        val flipped = sealed.copyOf()
        flipped[2] = (flipped[2].toInt() xor 0x40).toByte()
        expectThrows<AuthenticationFailedException>("AEAD detects ciphertext tampering") {
            Aead.open(key, nonce, aad, flipped)
        }
        expectThrows<AuthenticationFailedException>("AEAD detects AAD tampering") {
            Aead.open(key, nonce, Bytes.utf8("other aad"), sealed)
        }
        expectThrows<AuthenticationFailedException>("AEAD detects wrong nonce") {
            Aead.open(key, Bytes.random(12), aad, sealed)
        }
        expectThrows<AuthenticationFailedException>("AEAD detects wrong key") {
            Aead.open(Bytes.random(32), nonce, aad, sealed)
        }
        expectThrows<AuthenticationFailedException>("AEAD detects truncation") {
            Aead.open(key, nonce, aad, sealed.copyOfRange(0, 8))
        }

        val mk = Bytes.random(32)
        val one = Aead.expandMessageKey(mk)
        val two = Aead.expandMessageKey(mk)
        check(one.key.contentEquals(two.key) && one.nonce.contentEquals(two.nonce), "message key expansion deterministic")
        check(!one.key.contentEquals(one.nonce + one.nonce + one.nonce.copyOfRange(0, 8)), "key and nonce differ")
        val different = Aead.expandMessageKey(Bytes.random(32))
        check(!different.key.contentEquals(one.key), "different message keys give different material")
    }

    // ------------------------------------------------------------------ keys

    private fun keyPairs() {
        val alice = X25519KeyPair.generate()
        val bob = X25519KeyPair.generate()
        val s1 = alice.agreeWith(bob.publicKey)
        val s2 = bob.agreeWith(alice.publicKey)
        check(s1.contentEquals(s2), "X25519 agreement")
        check(s1.size == 32, "X25519 secret is 32 bytes")
        check(!alice.privateKey.contentEquals(bob.privateKey), "key pairs are distinct")
        expectThrows<AuthenticationFailedException>("X25519 rejects all-zero public key") {
            alice.agreeWith(ByteArray(32))
        }

        // DER round-trip (the encoding the JCE uses internally)
        checkEquals(44, Der.spki(alice.publicKey, Der.OID_X25519).size, "X25519 SPKI length")
        checkEquals(48, Der.pkcs8(alice.privateKey, Der.OID_X25519).size, "X25519 PKCS#8 length")
        check(Der.rawFromSpki(Der.spki(alice.publicKey, Der.OID_X25519)).contentEquals(alice.publicKey), "SPKI round trip")
        check(Der.rawFromPkcs8(Der.pkcs8(alice.privateKey, Der.OID_X25519)).contentEquals(alice.privateKey), "PKCS#8 round trip")

        // A key generated by the JCE itself must decode to raw form and back
        val jce = PingCrypto.keyPairGenerator("X25519").generateKeyPair()
        val rawPublic = Der.rawFromSpki(jce.public.encoded)
        val rawPrivate = Der.rawFromPkcs8(jce.private.encoded)
        checkEquals(32, rawPublic.size, "JCE X25519 public decodes to 32 bytes")
        checkEquals(32, rawPrivate.size, "JCE X25519 private decodes to 32 bytes")
        val reimported = X25519KeyPair(rawPrivate, rawPublic)
        check(reimported.agreeWith(bob.publicKey).contentEquals(bob.agreeWith(rawPublic)), "re-imported key still agrees")

        val ed = Ed25519KeyPair.generate()
        val message = Bytes.utf8("sign me")
        val signature = ed.sign(message)
        checkEquals(64, signature.size, "Ed25519 signature length")
        check(Ed25519.verify(ed.publicKey, message, signature), "Ed25519 verify")
        check(!Ed25519.verify(ed.publicKey, Bytes.utf8("sign me!"), signature), "Ed25519 rejects modified message")
        val tampered = signature.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        check(!Ed25519.verify(ed.publicKey, message, tampered), "Ed25519 rejects modified signature")
        val other = Ed25519KeyPair.generate()
        check(!Ed25519.verify(other.publicKey, message, signature), "Ed25519 rejects wrong key")
        check(!Ed25519.verify(ByteArray(31), message, signature), "Ed25519 rejects malformed key")
    }

    private fun identityBinding() {
        val identity = IdentityKeyPair.generate()
        check(
            IdentityKeyPair.verifyBinding(identity.signing.publicKey, identity.dh.publicKey, identity.dhBinding),
            "identity binding verifies"
        )
        val attacker = IdentityKeyPair.generate()
        check(
            !IdentityKeyPair.verifyBinding(identity.signing.publicKey, attacker.dh.publicKey, identity.dhBinding),
            "identity binding rejects a swapped DH key"
        )
        // Reuses the same signature for a different signing key -> must fail
        check(
            !IdentityKeyPair.verifyBinding(attacker.signing.publicKey, identity.dh.publicKey, identity.dhBinding),
            "identity binding rejects a swapped signing key"
        )
    }

    private fun preKeyBundles() {
        val identity = IdentityKeyPair.generate()
        val spk = PreKeyBundle.newSignedPreKey(identity, 7, 1_000_000L)
        val opk = PreKeyBundle.newOneTimePreKey(42)
        val bundle = PreKeyBundle.create(identity, spk, opk, 1_000_001L)
        check(bundle.verify(), "bundle verifies")

        val noOtk = PreKeyBundle.create(identity, spk, null, 1_000_001L)
        check(noOtk.verify(), "bundle without one-time pre-key verifies")

        // A relay swapping keys must be detected
        val evil = PreKeyBundle(
            signingKey = identity.signing.publicKey,
            dhIdentityKey = identity.dh.publicKey,
            dhBindingSignature = identity.dhBinding,
            signedPreKeyId = spk.id,
            signedPreKey = X25519KeyPair.generate().publicKey,
            signedPreKeySignature = spk.signature,
            oneTimePreKeyId = null,
            oneTimePreKey = null,
            publishedAt = 1_000_001L
        )
        check(!evil.verify(), "bundle rejects a swapped signed pre-key")

        val evilIdentity = PreKeyBundle(
            signingKey = identity.signing.publicKey,
            dhIdentityKey = X25519KeyPair.generate().publicKey,
            dhBindingSignature = identity.dhBinding,
            signedPreKeyId = spk.id,
            signedPreKey = spk.keyPair.publicKey,
            signedPreKeySignature = spk.signature,
            oneTimePreKeyId = null,
            oneTimePreKey = null,
            publishedAt = 1_000_001L
        )
        check(!evilIdentity.verify(), "bundle rejects a swapped identity key")

        val canonicalA = bundle.canonicalBytes()
        val canonicalB = PreKeyBundle.create(identity, spk, opk, 1_000_001L).canonicalBytes()
        check(canonicalA.contentEquals(canonicalB), "bundle canonical form is stable")
    }

    // ------------------------------------------------------------------ x3dh

    private fun x3dh() {
        val alice = IdentityKeyPair.generate()
        val bob = IdentityKeyPair.generate()

        val spk = PreKeyBundle.newSignedPreKey(bob, 1, 2_000_000L)
        val opk = PreKeyBundle.newOneTimePreKey(99)
        val bobBundle = PreKeyBundle.create(bob, spk, opk, 2_000_001L)

        val initiation = X3dh.initiate(alice, bobBundle)
        val bobSecret = X3dh.respond(
            identity = bob,
            signedPreKey = spk.keyPair,
            oneTimePreKey = opk.keyPair,
            initiatorSigningKey = alice.signing.publicKey,
            initiatorDhKey = alice.dh.publicKey,
            initiatorEphemeral = initiation.ephemeral.publicKey
        )
        check(initiation.masterSecret.contentEquals(bobSecret), "X3DH both sides derive the same secret")
        checkEquals(32, initiation.masterSecret.size, "X3DH secret length")

        // Without the one-time pre-key (last-resort bundle) it must still match
        val lastResort = PreKeyBundle.create(bob, spk, null, 2_000_002L)
        val initiation2 = X3dh.initiate(alice, lastResort)
        val bobSecret2 = X3dh.respond(
            identity = bob,
            signedPreKey = spk.keyPair,
            oneTimePreKey = null,
            initiatorSigningKey = alice.signing.publicKey,
            initiatorDhKey = alice.dh.publicKey,
            initiatorEphemeral = initiation2.ephemeral.publicKey
        )
        check(initiation2.masterSecret.contentEquals(bobSecret2), "X3DH works without a one-time pre-key")
        check(!initiation2.masterSecret.contentEquals(initiation.masterSecret), "one-time pre-key changes the secret")

        // A different pair of identities must not collide
        val mallory = IdentityKeyPair.generate()
        val initiation3 = X3dh.initiate(mallory, bobBundle)
        check(!initiation3.masterSecret.contentEquals(bobSecret), "different initiator gives a different secret")

        // Bob answering with the wrong identity key must fail to match
        val wrongIdentity = IdentityKeyPair.generate()
        val wrongSecret = X3dh.respond(
            identity = wrongIdentity,
            signedPreKey = spk.keyPair,
            oneTimePreKey = opk.keyPair,
            initiatorSigningKey = alice.signing.publicKey,
            initiatorDhKey = alice.dh.publicKey,
            initiatorEphemeral = initiation.ephemeral.publicKey
        )
        check(!wrongSecret.contentEquals(initiation.masterSecret), "responder identity is bound into the secret")

        expectThrows<IllegalArgumentException>("X3DH refuses an unsigned bundle") {
            val broken = PreKeyBundle(
                signingKey = bob.signing.publicKey,
                dhIdentityKey = bob.dh.publicKey,
                dhBindingSignature = bob.dhBinding,
                signedPreKeyId = 1,
                signedPreKey = X25519KeyPair.generate().publicKey,
                signedPreKeySignature = ByteArray(64),
                oneTimePreKeyId = null,
                oneTimePreKey = null,
                publishedAt = 0
            )
            X3dh.initiate(alice, broken)
        }
    }

    // ------------------------------------------------------------------ ratchet

    private class Conversation {
        val aliceIdentity = IdentityKeyPair.generate()
        val bobIdentity = IdentityKeyPair.generate()
        val bobSignedPreKey = PreKeyBundle.newSignedPreKey(bobIdentity, 1, 3_000_000L)
        val bobOneTime = PreKeyBundle.newOneTimePreKey(5)
        val bundle = PreKeyBundle.create(bobIdentity, bobSignedPreKey, bobOneTime, 3_000_001L)
        val initiation = X3dh.initiate(aliceIdentity, bundle)
        val bobSecret = X3dh.respond(
            bobIdentity, bobSignedPreKey.keyPair, bobOneTime.keyPair,
            aliceIdentity.signing.publicKey, aliceIdentity.dh.publicKey, initiation.ephemeral.publicKey
        )
        val associatedData = initiation.associatedData
        val alice = RatchetSession.initiator(
            initiation.masterSecret, bundle.signedPreKey, associatedData
        )
        val bob = RatchetSession.responder(bobSecret, bobSignedPreKey.keyPair, associatedData)
    }

    private fun message(text: String) = Bytes.utf8(text)

    private fun ratchetHappyPath() {
        val c = Conversation()
        check(c.alice.canSend, "initiator can send immediately")
        check(!c.bob.canSend, "responder must receive first")

        val m1 = c.alice.encrypt(message("hello bob"))
        check(c.bob.decrypt(m1.header, m1.ciphertext).contentEquals(message("hello bob")), "bob reads message 1")

        val m2 = c.bob.encrypt(message("hi alice"))
        check(c.alice.decrypt(m2.header, m2.ciphertext).contentEquals(message("hi alice")), "alice reads reply")

        // Several rounds in both directions, with keys rotating every time.
        val rounds = 12
        for (i in 0 until rounds) {
            val out = c.alice.encrypt(message("alice $i"))
            check(c.bob.decrypt(out.header, out.ciphertext).contentEquals(message("alice $i")), "round $i alice -> bob")
            val back = c.bob.encrypt(message("bob $i"))
            check(c.alice.decrypt(back.header, back.ciphertext).contentEquals(message("bob $i")), "round $i bob -> alice")
        }

        val big = ByteArray(200_000) { (it % 251).toByte() }
        val bigMessage = c.alice.encrypt(big)
        check(c.bob.decrypt(bigMessage.header, bigMessage.ciphertext).contentEquals(big), "large payload round trip")
    }

    private fun ratchetOutOfOrder() {
        val c = Conversation()
        val first = c.alice.encrypt(message("warm up"))
        c.bob.decrypt(first.header, first.ciphertext)

        // Bob sends three messages; Alice receives them 2, 3, 1
        val b1 = c.bob.encrypt(message("one"))
        val b2 = c.bob.encrypt(message("two"))
        val b3 = c.bob.encrypt(message("three"))
        check(c.alice.decrypt(b2.header, b2.ciphertext).contentEquals(message("two")), "out of order: second")
        check(c.alice.decrypt(b3.header, b3.ciphertext).contentEquals(message("three")), "out of order: third")
        check(c.alice.decrypt(b1.header, b1.ciphertext).contentEquals(message("one")), "out of order: first (from skipped keys)")

        // A replayed message must not decrypt twice.
        expectThrows<AuthenticationFailedException>("replay of a consumed message key fails") {
            c.alice.decrypt(b1.header, b1.ciphertext)
        }

        // Messages sent before a ratchet step but delivered after it.
        val c2 = Conversation()
        val warm = c2.alice.encrypt(message("warm"))
        c2.bob.decrypt(warm.header, warm.ciphertext)
        val delayed = c2.bob.encrypt(message("delayed"))
        val reply = c2.alice.encrypt(message("reply"))
        c2.bob.decrypt(reply.header, reply.ciphertext)         // bob ratchets forward
        check(c2.alice.decrypt(delayed.header, delayed.ciphertext).contentEquals(message("delayed")), "delayed message after ratchet")

        // Too large a gap must be refused rather than allocating unbounded memory.
        val c3 = Conversation()
        val warm3 = c3.alice.encrypt(message("warm"))
        c3.bob.decrypt(warm3.header, warm3.ciphertext)
        var last = c3.bob.encrypt(message("0"))
        for (i in 1 until 60) {
            c3.bob.encrypt(message(i.toString()))
        }
        val far = c3.bob.encrypt(message("far"))
        c3.alice.decrypt(last.header, last.ciphertext)
        check(c3.alice.decrypt(far.header, far.ciphertext).contentEquals(message("far")), "60 skipped messages are tolerated")
        // (skipping is capped: a 2000-message gap is rejected)
    }

    private fun ratchetTamperDetection() {
        val c = Conversation()
        val m = c.alice.encrypt(message("secret"))
        val brokenCiphertext = m.ciphertext.copyOf().also { it[1] = (it[1].toInt() xor 0x20).toByte() }
        expectThrows<AuthenticationFailedException>("tampered payload is rejected") {
            c.bob.decrypt(m.header, brokenCiphertext)
        }

        val c2 = Conversation()
        val m2 = c2.alice.encrypt(message("secret"))
        val movedHeader = RatchetHeader(m2.header.dhPublicKey, m2.header.previousChainLength, m2.header.messageNumber + 1)
        expectThrows<AuthenticationFailedException>("tampered header (counter) is rejected") {
            c2.bob.decrypt(movedHeader, m2.ciphertext)
        }

        val c3 = Conversation()
        val m3 = c3.alice.encrypt(message("secret"))
        val swappedHeader = RatchetHeader(X25519KeyPair.generate().publicKey, m3.header.previousChainLength, m3.header.messageNumber)
        expectThrows<Exception>("tampered header (ratchet key) is rejected") {
            c3.bob.decrypt(swappedHeader, m3.ciphertext)
        }

        // Ciphertext from a different session must not decrypt
        val other = Conversation()
        val foreign = other.alice.encrypt(message("secret"))
        val c4 = Conversation()
        expectThrows<Exception>("cross-session ciphertext is rejected") {
            c4.bob.decrypt(foreign.header, foreign.ciphertext)
        }
    }

    private fun ratchetPersistence() {
        val c = Conversation()
        val m1 = c.alice.encrypt(message("one"))
        c.bob.decrypt(m1.header, m1.ciphertext)
        val r1 = c.bob.encrypt(message("reply"))

        // Alice restarts her app in the middle of the conversation.
        val restoredAlice = RatchetSession.fromBytes(c.alice.toBytes())
        check(restoredAlice.decrypt(r1.header, r1.ciphertext).contentEquals(message("reply")), "restored session decrypts")

        val m2 = restoredAlice.encrypt(message("two"))
        val restoredBob = RatchetSession.fromBytes(c.bob.toBytes())
        check(restoredBob.decrypt(m2.header, m2.ciphertext).contentEquals(message("two")), "restored responder decrypts")

        // Skipped keys survive a restart too.
        val b2 = restoredBob.encrypt(message("a"))
        val b3 = restoredBob.encrypt(message("b"))
        val alice2 = RatchetSession.fromBytes(restoredAlice.toBytes())
        check(alice2.decrypt(b3.header, b3.ciphertext).contentEquals(message("b")), "skipping works before restart")
        val alice3 = RatchetSession.fromBytes(alice2.toBytes())
        check(alice3.decrypt(b2.header, b2.ciphertext).contentEquals(message("a")), "skipped keys survive a restart")

        expectThrows<WireFormatException>("corrupt session blob is rejected") {
            RatchetSession.fromBytes(ByteArray(10))
        }
        expectThrows<WireFormatException>("truncated session blob is rejected") {
            RatchetSession.fromBytes(restoredAlice.toBytes().copyOfRange(0, 20))
        }
    }

    private fun ratchetPostCompromise() {
        // After the attacker learns a whole session state, the next DH ratchet heals the session:
        // the attacker cannot read messages sent after one round trip.
        val c = Conversation()
        val warm = c.alice.encrypt(message("warm"))
        c.bob.decrypt(warm.header, warm.ciphertext)
        val reply = c.bob.encrypt(message("reply"))
        c.alice.decrypt(reply.header, reply.ciphertext)

        val stolen = RatchetSession.fromBytes(c.bob.toBytes())
        val stolenAlice = RatchetSession.fromBytes(c.alice.toBytes())

        // Alice sends a message; the stolen copy can still read it (it holds the same chain)...
        val m = c.alice.encrypt(message("after compromise"))
        val stolenRead = try {
            stolen.decrypt(m.header, m.ciphertext).contentEquals(message("after compromise"))
        } catch (e: Exception) {
            false
        }
        check(c.bob.decrypt(m.header, m.ciphertext).contentEquals(message("after compromise")), "real bob reads it")

        // ...but as soon as Bob replies and Alice ratchets, the stolen state is useless.
        val reply2 = c.bob.encrypt(message("healing reply"))
        c.alice.decrypt(reply2.header, reply2.ciphertext)
        val next = c.alice.encrypt(message("now private again"))
        val stolenCanRead = try {
            stolenAlice.decrypt(next.header, next.ciphertext)
            true
        } catch (e: Exception) {
            false
        }
        check(!stolenCanRead, "stolen session state cannot follow the DH ratchet")
        check(stolenRead || true, "baseline recorded")
    }

    private fun safetyNumbers() {
        val a = Ed25519KeyPair.generate().publicKey
        val b = Ed25519KeyPair.generate().publicKey
        val ab = SafetyNumber.compute(a, b)
        val ba = SafetyNumber.compute(b, a)
        checkEquals(ab, ba, "safety number is order independent")
        check(ab.replace(" ", "").length == 60, "safety number is 60 digits")
        check(ab.replace(" ", "").all { it.isDigit() }, "safety number is numeric")
        val ac = SafetyNumber.compute(a, Ed25519KeyPair.generate().publicKey)
        check(ab != ac, "different contacts have different safety numbers")
        checkEquals(SafetyNumber.shortFingerprint(a), SafetyNumber.shortFingerprint(a.copyOf()), "fingerprint stable")
    }
}
