package com.ping.app.engine

import android.content.Context
import com.ping.app.core.crypto.Aead
import com.ping.app.core.crypto.AttachmentCrypto
import com.ping.app.core.crypto.Bytes
import com.ping.app.core.protocol.Wire
import java.io.File
import java.io.RandomAccessFile

/**
 * Attachments on disk.
 *
 * The only thing ever written is ciphertext: each 64 KiB chunk is sealed with the attachment's own
 * key before it is stored, and the key lives in the encrypted database. Displaying an image
 * decrypts it into memory; nothing is ever written back to disk in the clear.
 */
class AttachmentStore(context: Context) {

    private val directory = File(context.noBackupFilesDir, "attachments").apply { mkdirs() }

    fun fileFor(attachmentId: String): File = File(directory, "$attachmentId.enc")

    /**
     * Verifies one incoming chunk against its position, then stores it exactly as it arrived.
     *
     * The sender sealed it with the attachment key, so there is nothing to encrypt again: the
     * plaintext exists only in memory, for the length of the check.
     */
    fun writeIncomingChunk(
        file: File,
        transferId: String,
        fileKey: ByteArray,
        index: Int,
        chunkCount: Int,
        ciphertext: ByteArray
    ): Long {
        val transferIdBytes = Bytes.fromBase64Url(transferId)
        val plaintext = AttachmentCrypto.decryptChunk(fileKey, transferIdBytes, index, chunkCount, ciphertext)
        Bytes.wipe(plaintext)
        RandomAccessFile(file, "rw").use { handle ->
            // Full chunks are exactly 64 KiB plus the AEAD tag; the last one may be shorter.
            val offset = index.toLong() * (Wire.ATTACHMENT_CHUNK_BYTES + Aead.TAG_LEN)
            handle.seek(offset)
            handle.write(ciphertext)
        }
        return ciphertext.size.toLong()
    }

    /** Verifies the whole file against the digest the sender announced. */
    fun verifyDigest(
        file: File,
        transferId: String,
        fileKey: ByteArray,
        chunkCount: Int,
        expectedSha256: ByteArray
    ): Boolean {
        val accumulator = AttachmentCrypto.Sha256Accumulator()
        val id = Bytes.fromBase64Url(transferId)
        RandomAccessFile(file, "r").use { handle ->
            val buffer = ByteArray(Wire.ATTACHMENT_CHUNK_BYTES + Aead.TAG_LEN)
            for (index in 0 until chunkCount) {
                handle.seek(index.toLong() * buffer.size)
                val read = handle.read(buffer)
                if (read <= 0) return false
                val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                val plaintext = try {
                    AttachmentCrypto.decryptChunk(fileKey, id, index, chunkCount, chunk)
                } catch (e: Exception) {
                    return false
                }
                accumulator.update(plaintext)
                Bytes.wipe(plaintext)
            }
        }
        return accumulator.finish().contentEquals(expectedSha256)
    }

    /** Decrypts one chunk back to plaintext bytes (used by the image viewer). */
    fun readPlaintextChunk(
        file: File,
        transferId: String,
        fileKey: ByteArray,
        index: Int,
        chunkCount: Int
    ): ByteArray {
        val id = Bytes.fromBase64Url(transferId)
        RandomAccessFile(file, "r").use { handle ->
            val size = Wire.ATTACHMENT_CHUNK_BYTES + Aead.TAG_LEN
            handle.seek(index.toLong() * size)
            val buffer = ByteArray(size)
            val read = handle.read(buffer)
            if (read <= 0) throw IllegalStateException("attachment chunk $index is missing")
            val chunk = if (read == size) buffer else buffer.copyOf(read)
            return AttachmentCrypto.decryptChunk(fileKey, id, index, chunkCount, chunk)
        }
    }

    /** Decrypts the whole attachment into memory. Small files only (images, voice notes). */
    fun readAll(file: File, transferId: String, fileKey: ByteArray, chunkCount: Int, size: Int): ByteArray {
        val out = ByteArray(size)
        var offset = 0
        for (index in 0 until chunkCount) {
            val plain = readPlaintextChunk(file, transferId, fileKey, index, chunkCount)
            val copy = minOf(plain.size, out.size - offset)
            if (copy > 0) System.arraycopy(plain, 0, out, offset, copy)
            offset += copy
            Bytes.wipe(plain)
        }
        return out
    }

    fun delete(attachmentId: String) {
        fileFor(attachmentId).delete()
    }

    fun deleteAll() {
        directory.listFiles()?.forEach { it.delete() }
    }

    fun totalBytes(): Long = directory.listFiles()?.sumOf { it.length() } ?: 0L

    /** True when every chunk slot has been written. */
    fun isComplete(file: File, chunkCount: Int, expectedSize: Int): Boolean {
        val expected = expectedSize.toLong() + chunkCount.toLong() * Aead.TAG_LEN
        return file.exists() && file.length() >= expected
    }
}
