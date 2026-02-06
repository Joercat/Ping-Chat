// data/database/PingDatabase.kt
package com.ping.app.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.ping.app.data.database.dao.*
import com.ping.app.data.database.entities.*
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory

@Database(
    entities = [
        MessageEntity::class,
        ConversationEntity::class,
        UserEntity::class,
        AttachmentEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class PingDatabase : RoomDatabase() {
    
    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao
    abstract fun userDao(): UserDao
    abstract fun attachmentDao(): AttachmentDao
    
    companion object {
        private const val DATABASE_NAME = "ping_encrypted.db"
        
        @Volatile
        private var INSTANCE: PingDatabase? = null
        
        /**
         * Gets the database instance with encryption.
         * FIXED: Added proper null safety and error handling.
         */
        fun getInstance(context: Context, passphrase: ByteArray): PingDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context, passphrase).also { INSTANCE = it }
            }
        }
        
        private fun buildDatabase(context: Context, passphrase: ByteArray): PingDatabase {
            // Validate passphrase
            require(passphrase.isNotEmpty()) { "Database passphrase cannot be empty" }
            
            val factory = SupportFactory(passphrase)
            
            return Room.databaseBuilder(
                context.applicationContext,
                PingDatabase::class.java,
                DATABASE_NAME
            )
                .openHelperFactory(factory)
                .fallbackToDestructiveMigration()
                .build()
        }
        
        /**
         * Closes the database instance.
         */
        fun closeDatabase() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }
        
        /**
         * Checks if database is initialized.
         */
        fun isInitialized(): Boolean {
            return INSTANCE != null
        }
    }
}
