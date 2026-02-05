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
        @Volatile
        private var INSTANCE: PingDatabase? = null
        
        fun getInstance(context: Context, passphrase: ByteArray): PingDatabase {
            return INSTANCE ?: synchronized(this) {
                val factory = SupportFactory(passphrase)
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    PingDatabase::class.java,
                    "ping_encrypted.db"
                )
                    .openHelperFactory(factory)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
