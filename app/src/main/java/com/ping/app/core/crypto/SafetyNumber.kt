package com.ping.app.core.crypto

/**
 * The safety number is the out-of-band proof that two people really talk to each other and not to
 * a machine in the middle: both sides compute the same 60 digits from the two identity keys and
 * compare them over a different channel (in person, on a call, over the QR code).
 */
object SafetyNumber {

    private const val ITERATIONS = 5200

    /** 60 digits, in 12 groups of 5. Deterministic and order independent. */
    fun compute(identityA: ByteArray, identityB: ByteArray): String {
        require(identityA.size == 32 && identityB.size == 32) { "identities are 32-byte Ed25519 keys" }
        val (first, second) = if (compare(identityA, identityB) <= 0) {
            identityA to identityB
        } else {
            identityB to identityA
        }

        var hash = Bytes.sha512(Bytes.utf8("PingSafetyNumber/v1"), first, second)
        repeat(ITERATIONS) {
            hash = Bytes.sha512(hash, first, second)
        }

        val digits = StringBuilder(60)
        var block = hash
        while (digits.length < 60) {
            var offset = 0
            while (offset + 5 <= block.size && digits.length < 60) {
                // 5 bytes -> an integer < 2^40 -> 5 decimal digits (zero padded)
                var value = 0L
                for (i in 0 until 5) {
                    value = (value shl 8) or (block[offset + i].toLong() and 0xff)
                }
                digits.append((value % 100000L).toString().padStart(5, '0'))
                offset += 5
            }
            if (digits.length < 60) block = Bytes.sha512(block)
        }

        val out = digits.toString()
        return buildString {
            for (i in 0 until 12) {
                if (i > 0) append(' ')
                append(out, i * 5, i * 5 + 5)
            }
        }
    }

    /** Compact 8-byte fingerprint used in the UI to identify a key at a glance. */
    fun shortFingerprint(identity: ByteArray): String =
        Bytes.toHex(Bytes.sha256(Bytes.utf8("PingFingerprint/v1"), identity).copyOfRange(0, 8))

    private fun compare(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val x = a[i].toInt() and 0xff
            val y = b[i].toInt() and 0xff
            if (x != y) return x - y
        }
        return a.size - b.size
    }
}
