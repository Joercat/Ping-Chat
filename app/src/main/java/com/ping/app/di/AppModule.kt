// di/AppModule.kt
package com.ping.app.di

import android.content.Context
import com.ping.app.core.encryption.CryptoManager
import com.ping.app.core.encryption.KeyExchange
import com.ping.app.core.encryption.SecureStorage
import com.ping.app.core.network.ConnectionManager
import com.ping.app.core.network.MessageRouter
import com.ping.app.core.network.P2PManager
import com.ping.app.core.network.PeerDiscovery
import com.ping.app.core.sync.ConflictResolver
import com.ping.app.core.sync.SyncManager
import com.ping.app.data.database.PingDatabase
import com.ping.app.data.database.dao.*
import com.ping.app.data.repository.ConversationRepository
import com.ping.app.data.repository.MessageRepository
import com.ping.app.data.repository.UserRepository
import com.ping.app.services.NotificationService
import com.ping.app.utils.FileUtils
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    
    @Provides
    @Singleton
    fun provideSecureStorage(
        @ApplicationContext context: Context
    ): SecureStorage = SecureStorage(context)
    
    @Provides
    @Singleton
    fun provideCryptoManager(
        secureStorage: SecureStorage
    ): CryptoManager = CryptoManager(secureStorage)
    
    @Provides
    @Singleton
    fun provideKeyExchange(
        cryptoManager: CryptoManager,
        secureStorage: SecureStorage
    ): KeyExchange = KeyExchange(cryptoManager, secureStorage)
    
    @Provides
    @Singleton
    fun provideConnectionManager(
        @ApplicationContext context: Context,
        secureStorage: SecureStorage
    ): ConnectionManager = ConnectionManager(context, secureStorage)
    
    @Provides
    @Singleton
    fun providePeerDiscovery(
        @ApplicationContext context: Context,
        connectionManager: ConnectionManager
    ): PeerDiscovery = PeerDiscovery(context, connectionManager)
    
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        secureStorage: SecureStorage
    ): PingDatabase = PingDatabase.getInstance(
        context,
        secureStorage.getDatabasePassphrase()
    )
    
    @Provides
    @Singleton
    fun provideMessageDao(database: PingDatabase): MessageDao = database.messageDao()
    
    @Provides
    @Singleton
    fun provideConversationDao(database: PingDatabase): ConversationDao = database.conversationDao()
    
    @Provides
    @Singleton
    fun provideUserDao(database: PingDatabase): UserDao = database.userDao()
    
    @Provides
    @Singleton
    fun provideAttachmentDao(database: PingDatabase): AttachmentDao = database.attachmentDao()
    
    @Provides
    @Singleton
    fun provideMessageRepository(
        messageDao: MessageDao,
        attachmentDao: AttachmentDao
    ): MessageRepository = MessageRepository(messageDao, attachmentDao)
    
    @Provides
    @Singleton
    fun provideConversationRepository(
        conversationDao: ConversationDao
    ): ConversationRepository = ConversationRepository(conversationDao)
    
    @Provides
    @Singleton
    fun provideUserRepository(
        userDao: UserDao
    ): UserRepository = UserRepository(userDao)
    
    @Provides
    @Singleton
    fun provideFileUtils(
        @ApplicationContext context: Context
    ): FileUtils = FileUtils(context)
    
    @Provides
    @Singleton
    fun provideNotificationService(
        @ApplicationContext context: Context,
        userRepository: UserRepository
    ): NotificationService = NotificationService(context, userRepository)
    
    @Provides
    @Singleton
    fun provideP2PManager(
        cryptoManager: CryptoManager,
        keyExchange: KeyExchange,
        connectionManager: ConnectionManager
    ): P2PManager = P2PManager(cryptoManager, keyExchange, connectionManager)
    
    @Provides
    @Singleton
    fun provideConflictResolver(): ConflictResolver = ConflictResolver()
    
    @Provides
    @Singleton
    fun provideSyncManager(
        messageRepository: MessageRepository,
        p2pManager: P2PManager,
        conflictResolver: ConflictResolver
    ): SyncManager = SyncManager(messageRepository, p2pManager, conflictResolver)
    
    @Provides
    @Singleton
    fun provideMessageRouter(
        p2pManager: P2PManager,
        cryptoManager: CryptoManager,
        keyExchange: KeyExchange,
        messageRepository: MessageRepository,
        userRepository: UserRepository,
        fileUtils: FileUtils,
        notificationService: NotificationService
    ): MessageRouter = MessageRouter(
        p2pManager,
        cryptoManager,
        keyExchange,
        messageRepository,
        userRepository,
        fileUtils,
        notificationService
    )
}
