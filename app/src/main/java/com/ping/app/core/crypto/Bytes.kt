package com.ping.app.core.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays

/**
 * Byte helpers used across the cryptographic core.
 *
 * Everything in `com.ping.app.core.*` is plain JVM Kotlin (no Android APIs) so that the
 * cryptographic core can be unit-tested on the JVM and audited in isolation.
 */
object Bytes {

    private val random = SecureRandom()

    /** Cryptographically strong random bytes. */
    fun random(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    /** Overwrites a buffer. Used to limit the lifetime of key material in memory. */
    fun wipe(vararg buffers: ByteArray?) {
        for (b in buffers) {
            if (b != null) Arrays.fill(b, 0)
        }
    }

    /** Length-independent, timing-safe comparison (no early exit). */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    /** SHA-256 */
    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        for (p in parts) md.update(p)
        return md.digest()
    }

    /** SHA-512 */
    fun sha512(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-512")
        for (p in parts) md.update(p)
        return md.digest()
    }

    /** HMAC-SHA-256 */
    fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
        for (p in parts) mac.update(p)
        return mac.doFinal()
    }

    /** Concatenation helper. */
    fun concat(vararg parts: ByteArray): ByteArray {
        var size = 0
        for (p in parts) size += p.size
        val out = ByteArray(size)
        var off = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, off, p.size)
            off += p.size
        }
        return out
    }

    fun utf8(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)

    // ---------------------------------------------------------------- encoding

    private const val B64URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** URL-safe base64 without padding (compact, safe inside URLs and QR codes). */
    fun toBase64Url(data: ByteArray): String {
        val sb = StringBuilder((data.size * 4 + 2) / 3)
        var i = 0
        while (i + 2 < data.size) {
            val n = ((data[i].toInt() and 0xff) shl 16) or ((data[i + 1].toInt() and 0xff) shl 8) or (data[i + 2].toInt() and 0xff)
            sb.append(B64URL[(n ushr 18) and 63]).append(B64URL[(n ushr 12) and 63])
                .append(B64URL[(n ushr 6) and 63]).append(B64URL[n and 63])
            i += 3
        }
        when (data.size - i) {
            1 -> {
                val n = (data[i].toInt() and 0xff) shl 16
                sb.append(B64URL[(n ushr 18) and 63]).append(B64URL[(n ushr 12) and 63])
            }
            2 -> {
                val n = ((data[i].toInt() and 0xff) shl 16) or ((data[i + 1].toInt() and 0xff) shl 8)
                sb.append(B64URL[(n ushr 18) and 63]).append(B64URL[(n ushr 12) and 63])
                    .append(B64URL[(n ushr 6) and 63])
            }
        }
        return sb.toString()
    }

    fun fromBase64Url(text: String): ByteArray {
        val clean = text.trim().replace("\n", "").replace("\r", "").replace("=", "").replace('+', '-').replace('/', '_')
        val out = java.io.ByteArrayOutputStream(clean.length * 3 / 4 + 1)
        var buffer = 0
        var bits = 0
        for (ch in clean) {
            val v = when (ch) {
                in 'A'..'Z' -> ch - 'A'
                in 'a'..'z' -> ch - 'a' + 26
                in '0'..'9' -> ch - '0' + 52
                '-' -> 62
                '_' -> 63
                else -> throw IllegalArgumentException("invalid base64url character: '$ch'")
            }
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer ushr bits) and 0xff)
            }
        }
        return out.toByteArray()
    }

    private const val HEX = "0123456789abcdef"

    fun toHex(data: ByteArray): String {
        val sb = StringBuilder(data.size * 2)
        for (b in data) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    fun fromHex(text: String): ByteArray {
        val clean = text.trim().removePrefix("0x")
        require(clean.length % 2 == 0) { "hex length must be even" }
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = ((hexValue(clean[i * 2]) shl 4) or hexValue(clean[i * 2 + 1])).toByte()
        }
        return out
    }

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw IllegalArgumentException("not a hex character: '$c'")
    }
}

// ---------------------------------------------------------------------- buffer

/** Growable big-endian writer used by every binary wire format in Ping. */
class BufWriter(initialCapacity: Int = 256) {
    private var buffer = ByteArray(initialCapacity)
    private var size = 0

    private fun ensure(extra: Int) {
        if (size + extra > buffer.size) {
            var capacity = buffer.size * 2
            while (capacity < size + extra) capacity *= 2
            buffer = buffer.copyOf(capacity)
        }
    }

    fun u8(value: Int): BufWriter {
        ensure(1); buffer[size++] = value.toByte(); return this
    }

    fun u16(value: Int): BufWriter {
        ensure(2); buffer[size++] = (value ushr 8).toByte(); buffer[size++] = value.toByte(); return this
    }

    fun u32(value: Int): BufWriter {
        ensure(4)
        buffer[size++] = (value ushr 24).toByte(); buffer[size++] = (value ushr 16).toByte()
        buffer[size++] = (value ushr 8).toByte(); buffer[size++] = value.toByte()
        return this
    }

    fun u64(value: Long): BufWriter {
        ensure(8)
        for (shift in 56 downTo 0 step 8) buffer[size++] = (value ushr shift).toByte()
        return this
    }

    /** Unsigned LEB128 — compact varints for lengths and counters. */
    fun varint(value: Int): BufWriter {
        require(value >= 0) { "varint must be unsigned" }
        var v = value
        while (true) {
            if (v and 0x7f.inv() == 0) {
                u8(v); break
            }
            u8((v and 0x7f) or 0x80)
            v = v ushr 7
        }
        return this
    }

    fun bytes(data: ByteArray): BufWriter {
        ensure(data.size); System.arraycopy(data, 0, buffer, size, data.size); size += data.size; return this
    }

    /** Length-prefixed byte string. */
    fun blob(data: ByteArray): BufWriter {
        varint(data.size); bytes(data); return this
    }

    fun string(value: String): BufWriter = blob(value.toByteArray(Charsets.UTF_8))

    fun toByteArray(): ByteArray = buffer.copyOf(size)

    val length: Int get() = size
}

/** Strict big-endian reader matching [BufWriter]. Throws on truncation or overflow. */
class BufReader(private val data: ByteArray, private var position: Int = 0) {

    val remaining: Int get() = data.size - position

    fun u8(): Int {
        if (remaining < 1) throw WireFormatException("buffer underflow reading byte")
        return data[position++].toInt() and 0xff
    }

    fun u16(): Int = (u8() shl 8) or u8()

    fun u32(): Int {
        var v = 0
        repeat(4) { v = (v shl 8) or u8() }
        return v
    }

    fun u64(): Long {
        var v = 0L
        repeat(8) { v = (v shl 8) or u8().toLong() }
        return v
    }

    fun varint(): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = u8()
            result = result or ((b and 0x7f) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 28) throw WireFormatException("varint too long")
        }
        if (result < 0) throw WireFormatException("varint overflow")
        return result
    }

    fun bytes(count: Int): ByteArray {
        if (count < 0) throw WireFormatException("negative length")
        if (remaining < count) throw WireFormatException("buffer underflow: want $count, have $remaining")
        val out = data.copyOfRange(position, position + count)
        position += count
        return out
    }

    fun blob(): ByteArray = bytes(varint())

    fun string(): String = blob().toString(Charsets.UTF_8)

    fun expectEnd() {
        if (remaining != 0) throw WireFormatException("$remaining trailing byte(s) in payload")
    }
}

/** Raised whenever attacker-controlled or corrupt data does not match the expected format. */
class WireFormatException(message: String) : Exception(message)

/** Raised when an AEAD tag does not verify — the message was tampered with or the key is wrong. */
class AuthenticationFailedException(message: String = "message authentication failed") : Exception(message)
