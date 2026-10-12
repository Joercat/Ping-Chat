package com.ping.app.data

import android.content.Context
import android.database.Cursor
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

/**
 * The local database.
 *
 * Two independent layers of protection:
 *  1. the whole file is encrypted by SQLCipher with a random 256-bit key that is itself sealed by
 *     the Android Keystore ([Vault]), so the file is opaque without this device;
 *  2. individual columns that carry personal data (message bodies, names, session keys) are
 *     additionally sealed with the Keystore key before they are written.
 *
 * Everything runs on a single dedicated thread: SQLite plus one writer is simpler to reason about
 * than connection pools, and there is no performance need for parallelism at this scale.
 */
class Db(context: Context, passphrase: ByteArray) : AutoCloseable {

    private val database: SQLiteDatabase

    init {
        // SQLCipher ships its own native library; loading it is the only setup required.
        System.loadLibrary("sqlcipher")
        val file = databaseFile(context)
        database = open(file, passphrase)
        database.enableWriteAheadLogging()
        createSchema()
        migrate()
    }

    /**
     * Brings an existing database up to the current schema.
     *
     * `CREATE TABLE IF NOT EXISTS` never changes a table that already exists, so a new column has to
     * be added here as well — otherwise an app that is *updated* (rather than freshly installed)
     * would run against the old shape and every query naming the new column would fail.
     */
    private fun migrate() {
        val columns = query("PRAGMA table_info(conversations)") { it.getString(1) }
        if ("group_id" !in columns) {
            exec("ALTER TABLE conversations ADD COLUMN group_id TEXT")
        }
        val attachmentColumns = columnsOf("attachments")
        val addedBits = "chunk_bits" !in attachmentColumns
        if (addedBits) exec("ALTER TABLE attachments ADD COLUMN chunk_bits BLOB")
        if ("transfer_error" !in attachmentColumns) exec("ALTER TABLE attachments ADD COLUMN transfer_error TEXT")
        if (addedBits) backfillChunkBits()
    }

    private data class LegacyTransfer(val id: String, val chunkCount: Int, val received: Int, val outgoing: Boolean)

    /**
     * Transfers from before the bit map kept a count of received chunks. A finished one (or any
     * outgoing one, which was then always shown as sent) becomes all-done; a partial incoming one
     * keeps its first chunks.
     */
    private fun backfillChunkBits() {
        val legacy = query(
            "SELECT id, chunk_count, received_chunks, outgoing FROM attachments WHERE chunk_bits IS NULL"
        ) { LegacyTransfer(it.getString(0), it.getInt(1), it.getInt(2), it.getInt(3) == 1) }
        for (transfer in legacy) {
            var bits = ChunkBits.empty(transfer.chunkCount)
            if (transfer.outgoing || transfer.received >= transfer.chunkCount) {
                bits = ChunkBits.full(transfer.chunkCount)
            } else {
                for (index in 0 until transfer.received) bits = ChunkBits.withBit(bits, transfer.chunkCount, index)
            }
            exec("UPDATE attachments SET chunk_bits = ? WHERE id = ?", arrayOf<Any?>(bits, transfer.id))
        }
    }

    /** Column names of a table, for the migration above and for tests. */
    internal fun columnsOf(table: String): List<String> = query("PRAGMA table_info($table)") { it.getString(1) }

    private fun open(file: File, passphrase: ByteArray): SQLiteDatabase {
        return try {
            SQLiteDatabase.openOrCreateDatabase(file, passphrase, null, null, null)
        } catch (e: Exception) {
            // Either the file is not a SQLCipher database, or the key changed (for example the
            // Keystore entry was wiped). Both mean the old contents are unrecoverable, so start
            // over rather than leaving the user with an app that cannot open.
            file.delete()
            File(file.path + "-wal").delete()
            File(file.path + "-shm").delete()
            SQLiteDatabase.openOrCreateDatabase(file, passphrase, null, null, null)
        }
    }

    private fun createSchema() {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS identity (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                sealed BLOB NOT NULL
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS contacts (
                hash TEXT PRIMARY KEY,
                signing_key BLOB NOT NULL,
                dh_key BLOB NOT NULL,
                display_name BLOB,
                mailbox BLOB,
                relay_url BLOB,
                added_at INTEGER NOT NULL,
                verified INTEGER NOT NULL DEFAULT 0,
                blocked INTEGER NOT NULL DEFAULT 0,
                disappearing INTEGER NOT NULL DEFAULT 0,
                last_seen_at INTEGER
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS conversations (
                id TEXT PRIMARY KEY,
                peer_hash TEXT NOT NULL,
                group_id TEXT,
                created_at INTEGER NOT NULL,
                last_message_at INTEGER NOT NULL DEFAULT 0,
                unread INTEGER NOT NULL DEFAULT 0,
                pinned INTEGER NOT NULL DEFAULT 0,
                muted INTEGER NOT NULL DEFAULT 0,
                archived INTEGER NOT NULL DEFAULT 0,
                disappearing INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS messages (
                id TEXT PRIMARY KEY,
                conversation_id TEXT NOT NULL,
                peer_hash TEXT NOT NULL,
                outgoing INTEGER NOT NULL,
                kind INTEGER NOT NULL,
                body BLOB,
                reply_to TEXT,
                created_at INTEGER NOT NULL,
                sent_at INTEGER,
                delivered_at INTEGER,
                read_at INTEGER,
                status INTEGER NOT NULL,
                attachment_id TEXT,
                expires_at INTEGER,
                edited_at INTEGER,
                reactions BLOB
            )
            """.trimIndent()
        )
        database.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_conv ON messages(conversation_id, created_at)")
        database.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_expiry ON messages(expires_at)")
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sessions (
                peer_hash TEXT PRIMARY KEY,
                state BLOB NOT NULL,
                tag BLOB NOT NULL,
                updated_at INTEGER NOT NULL,
                peer_identity BLOB,
                handshake_sent INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS prekeys (
                id INTEGER PRIMARY KEY,
                secret BLOB NOT NULL,
                public BLOB NOT NULL,
                created_at INTEGER NOT NULL,
                published INTEGER NOT NULL DEFAULT 0,
                consumed INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS signed_prekeys (
                id INTEGER PRIMARY KEY,
                secret BLOB NOT NULL,
                public BLOB NOT NULL,
                signature BLOB NOT NULL,
                created_at INTEGER NOT NULL,
                active INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS outbox (
                id TEXT PRIMARY KEY,
                peer_hash TEXT NOT NULL,
                conversation_id TEXT NOT NULL,
                payload BLOB NOT NULL,
                created_at INTEGER NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0,
                next_attempt_at INTEGER NOT NULL,
                last_error TEXT
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS attachments (
                id TEXT PRIMARY KEY,
                transfer_id TEXT NOT NULL,
                message_id TEXT NOT NULL,
                conversation_id TEXT NOT NULL,
                file_name BLOB,
                mime BLOB,
                size INTEGER NOT NULL,
                chunk_count INTEGER NOT NULL,
                sha256 BLOB NOT NULL,
                file_key BLOB NOT NULL,
                outgoing INTEGER NOT NULL,
                path BLOB,
                received_chunks INTEGER NOT NULL DEFAULT 0,
                status INTEGER NOT NULL DEFAULT 0,
                chunk_bits BLOB,
                transfer_error TEXT
            )
            """.trimIndent()
        )
        database.execSQL("CREATE INDEX IF NOT EXISTS idx_attachments_message ON attachments(message_id)")
        database.execSQL("CREATE INDEX IF NOT EXISTS idx_attachments_transfer ON attachments(transfer_id)")
        // ------------------------------------------------------------------ groups
        //
        // A group is a name, a roster, and one epoch key per epoch. The roster and the name are
        // personal data, so they are sealed with the vault like every other private column; the
        // epoch keys are sealed the same way. Nothing about a group is derivable from the relay.
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS groups (
                id TEXT PRIMARY KEY,
                sealed_name BLOB NOT NULL,
                epoch INTEGER NOT NULL,
                sealed_epoch_key BLOB NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS group_members (
                group_id TEXT NOT NULL,
                contact_hash TEXT NOT NULL,
                joined_at INTEGER NOT NULL,
                PRIMARY KEY(group_id, contact_hash)
            )
            """.trimIndent()
        )
        // Replay protection and counter bookkeeping: the highest counter seen per sender, and the
        // counter this device last used (so a counter can never repeat, even across restarts).
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS group_state (
                group_id TEXT NOT NULL,
                sender_hash TEXT NOT NULL,
                highest_counter INTEGER NOT NULL,
                PRIMARY KEY(group_id, sender_hash)
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS pending_acks (
                envelope_id TEXT PRIMARY KEY,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    // ------------------------------------------------------------------ query helpers

    fun <T> query(sql: String, args: Array<Any?> = emptyArray(), map: (Cursor) -> T): List<T> {
        val results = ArrayList<T>()
        database.rawQuery(sql, *args).use { cursor ->
            while (cursor.moveToNext()) results.add(map(cursor))
        }
        return results
    }

    fun <T> queryOne(sql: String, args: Array<Any?> = emptyArray(), map: (Cursor) -> T): T? =
        query(sql, args, map).firstOrNull()

    fun exec(sql: String, args: Array<Any?> = emptyArray()) {
        database.execSQL(sql, args)
    }

    fun <T> transaction(block: () -> T): T {
        database.beginTransaction()
        return try {
            val result = block()
            database.setTransactionSuccessful()
            result
        } finally {
            database.endTransaction()
        }
    }

    /** Rows deleted by the engine; used for "storage used" reporting and tests. */
    fun tableCount(table: String): Long =
        queryOne("SELECT COUNT(*) FROM $table") { it.getLong(0) } ?: 0L

    /** Overwrites every free page, so nothing readable is left in the file after a wipe. */
    fun secureErase() {
        try {
            database.execSQL("VACUUM")
        } catch (e: Exception) {
            // best effort
        }
    }

    override fun close() {
        try {
            database.close()
        } catch (e: Exception) {
            // already closed
        }
    }

    companion object {
        private const val DATABASE_NAME = "ping.db"

        fun databaseFile(context: Context): File =
            File(context.getNoBackupFilesDir(), DATABASE_NAME)

        fun deleteFiles(context: Context) {
            val file = databaseFile(context)
            file.delete()
            File(file.path + "-wal").delete()
            File(file.path + "-shm").delete()
            File(file.path + "-journal").delete()
        }
    }
}
