// core/network/P2PManager.kt
package com.ping.app.core.network

import com.ping.app.core.encryption.CryptoManager
import com.ping.app.core.encryption.EncryptedData
import com.ping.app.core.encryption.KeyExchange
import com.ping.app.data.models.*
import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.LengthFieldBasedFrameDecoder
import io.netty.handler.codec.LengthFieldPrepender
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class P2PManager @Inject constructor(
    private val cryptoManager: CryptoManager,
    private val keyExchange: KeyExchange,
    private val connectionManager: ConnectionManager
) {
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { 
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }
    
    private var serverChannel: Channel? = null
    private val clientChannels = mutableMapOf<String, Channel>()
    
    private val bossGroup = NioEventLoopGroup(1)
    private val workerGroup = NioEventLoopGroup()
    
    private val _incomingMessages = MutableSharedFlow<P2PPacket>(replay = 0, extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<P2PPacket> = _incomingMessages.asSharedFlow()
    
    private val _connectionStatus = MutableStateFlow<Map<String, ConnectionStatus>>(emptyMap())
    val connectionStatus: StateFlow<Map<String, ConnectionStatus>> = _connectionStatus.asStateFlow()
    
    private var myPort: Int = 0
    
    companion object {
        private const val DEFAULT_PORT = 9876
        private const val MAX_FRAME_LENGTH = 16 * 1024 * 1024 // 16MB for files
        private const val CONNECTION_TIMEOUT_MS = 10000
    }
    
    /**
     * Gets the current user's ID
     */
    fun getMyUserId(): String = connectionManager.getMyUserId()
    
    /**
     * Gets the server port
     */
    fun getServerPort(): Int = myPort
    
    /**
     * Starts the P2P server
     */
    fun startServer(port: Int = DEFAULT_PORT): Int {
        val bootstrap = ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel::class.java)
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    ch.pipeline().apply {
                        addLast(LengthFieldBasedFrameDecoder(MAX_FRAME_LENGTH, 0, 4, 0, 4))
                        addLast(LengthFieldPrepender(4))
                        addLast(P2PServerHandler())
                    }
                }
            })
            .option(ChannelOption.SO_BACKLOG, 128)
            .childOption(ChannelOption.SO_KEEPALIVE, true)
            .childOption(ChannelOption.TCP_NODELAY, true)
        
        return try {
            val channelFuture = bootstrap.bind(port).sync()
            serverChannel = channelFuture.channel()
            myPort = (channelFuture.channel().localAddress() as InetSocketAddress).port
            myPort
        } catch (e: Exception) {
            // Try alternate port if default is in use
            try {
                val altPort = port + 1
                val channelFuture = bootstrap.bind(altPort).sync()
                serverChannel = channelFuture.channel()
                myPort = altPort
                myPort
            } catch (e2: Exception) {
                e2.printStackTrace()
                0
            }
        }
    }
    
    /**
     * Connects to a peer
     */
    suspend fun connectToPeer(userId: String, address: String, port: Int): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Check if already connected
                if (isConnectedTo(userId)) {
                    return@withContext true
                }
                
                updateConnectionStatus(userId, ConnectionStatus.CONNECTING)
                
                val bootstrap = Bootstrap()
                    .group(workerGroup)
                    .channel(NioSocketChannel::class.java)
                    .handler(object : ChannelInitializer<SocketChannel>() {
                        override fun initChannel(ch: SocketChannel) {
                            ch.pipeline().apply {
                                addLast(LengthFieldBasedFrameDecoder(MAX_FRAME_LENGTH, 0, 4, 0, 4))
                                addLast(LengthFieldPrepender(4))
                                addLast(P2PClientHandler(userId))
                            }
                        }
                    })
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECTION_TIMEOUT_MS)
                
                val future = bootstrap.connect(address, port).sync()
                if (future.isSuccess) {
                    clientChannels[userId] = future.channel()
                    updateConnectionStatus(userId, ConnectionStatus.CONNECTED)
                    
                    // Send handshake with public key
                    sendHandshake(userId)
                    true
                } else {
                    updateConnectionStatus(userId, ConnectionStatus.FAILED)
                    false
                }
            } catch (e: Exception) {
                e.printStackTrace()
                updateConnectionStatus(userId, ConnectionStatus.FAILED)
                false
            }
        }
    }
    
    /**
     * Sends handshake to establish encryption
     */
    private suspend fun sendHandshake(userId: String) {
        val handshake = HandshakePacket(
            publicKey = keyExchange.getMyPublicKeyString(),
            senderId = connectionManager.getMyUserId()
        )
        sendPacket(userId, P2PPacket.Handshake(handshake))
    }
    
    /**
     * Checks if connected to a specific peer
     */
    fun isConnectedTo(userId: String): Boolean {
        return clientChannels[userId]?.isActive == true
    }
    
    /**
     * Sends an encrypted message to a peer
     */
    suspend fun sendMessage(userId: String, message: Message): Boolean {
        val sessionKey = keyExchange.getSessionKey(userId) ?: return false
        
        return try {
            val messageJson = json.encodeToString(message.toTransport())
            val encrypted = cryptoManager.encryptMessage(messageJson.toByteArray(), sessionKey)
            
            val packet = P2PPacket.EncryptedMessage(
                senderId = connectionManager.getMyUserId(),
                encryptedData = encrypted.toByteArray()
            )
            
            sendPacket(userId, packet)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    /**
     * Sends a file to a peer
     */
    suspend fun sendFile(userId: String, attachment: Attachment, fileData: ByteArray): Boolean {
        val sessionKey = keyExchange.getSessionKey(userId) ?: return false
        
        // Check file size
        if (fileData.size > Attachment.MAX_FILE_SIZE) {
            return false
        }
        
        return try {
            val encrypted = cryptoManager.encryptFile(fileData, sessionKey)
            val checksum = cryptoManager.calculateChecksum(fileData)
            
            val packet = P2PPacket.FileTransfer(
                attachmentId = attachment.id,
                messageId = attachment.messageId,
                fileName = attachment.fileName,
                mimeType = attachment.mimeType,
                fileSize = fileData.size.toLong(),
                encryptedData = encrypted.toByteArray(),
                checksum = checksum
            )
            
            sendPacket(userId, packet)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    /**
     * Sends typing indicator to a peer
     */
    suspend fun sendTypingIndicator(userId: String, conversationId: String, isTyping: Boolean): Boolean {
        val packet = P2PPacket.TypingIndicator(
            userId = connectionManager.getMyUserId(),
            conversationId = conversationId,
            isTyping = isTyping
        )
        return sendPacket(userId, packet)
    }
    
    /**
     * Sends presence update to a peer
     */
    suspend fun sendPresenceUpdate(userId: String, presence: PresenceInfo): Boolean {
        val packet = P2PPacket.PresenceUpdate(presence)
        return sendPacket(userId, packet)
    }
    
    /**
     * Sends message status update to a peer
     */
    suspend fun sendMessageStatus(userId: String, messageId: String, status: MessageStatus): Boolean {
        val packet = P2PPacket.MessageStatusUpdate(
            messageId = messageId,
            status = status.name,
            timestamp = System.currentTimeMillis()
        )
        return sendPacket(userId, packet)
    }
    
    /**
     * Sends read receipt to a peer
     */
    suspend fun sendReadReceipt(userId: String, messageIds: List<String>): Boolean {
        if (messageIds.isEmpty()) return true
        
        val packet = P2PPacket.ReadReceipt(
            messageIds = messageIds,
            timestamp = System.currentTimeMillis()
        )
        return sendPacket(userId, packet)
    }
    
    /**
     * Sends reaction packet to a peer
     */
    suspend fun sendReactionPacket(userId: String, packet: P2PPacket.Reaction): Boolean {
        return sendPacket(userId, packet)
    }
    
    /**
     * Sends message edit packet to a peer
     */
    suspend fun sendEditPacket(userId: String, packet: P2PPacket.MessageEdit): Boolean {
        return sendPacket(userId, packet)
    }
    
    /**
     * Sends message delete packet to a peer
     */
    suspend fun sendDeletePacket(userId: String, packet: P2PPacket.MessageDelete): Boolean {
        return sendPacket(userId, packet)
    }
    
    /**
     * Generic method to send any packet to a peer
     */
    private suspend fun sendPacket(userId: String, packet: P2PPacket): Boolean {
        return withContext(Dispatchers.IO) {
            val channel = clientChannels[userId]
            
            if (channel == null || !channel.isActive) {
                updateConnectionStatus(userId, ConnectionStatus.DISCONNECTED)
                return@withContext false
            }
            
            try {
                val wrapper = P2PPacketWrapper(packet)
                val packetJson = json.encodeToString(wrapper)
                val buffer = Unpooled.copiedBuffer(packetJson.toByteArray(Charsets.UTF_8))
                channel.writeAndFlush(buffer).sync()
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }
    
    /**
     * Broadcasts presence to all connected peers
     */
    fun broadcastPresence(presence: PresenceInfo) {
        scope.launch {
            clientChannels.keys.toList().forEach { userId ->
                try {
                    sendPresenceUpdate(userId, presence)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
    
    /**
     * Updates connection status for a peer
     */
    private fun updateConnectionStatus(userId: String, status: ConnectionStatus) {
        _connectionStatus.value = _connectionStatus.value + (userId to status)
    }
    
    /**
     * Gets all connected peer IDs
     */
    fun getConnectedPeers(): List<String> {
        return clientChannels.filter { it.value.isActive }.keys.toList()
    }
    
    /**
     * Disconnects from a specific peer
     */
    fun disconnect(userId: String) {
        try {
            clientChannels[userId]?.close()
            clientChannels.remove(userId)
            updateConnectionStatus(userId, ConnectionStatus.DISCONNECTED)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    /**
     * Disconnects from all peers and shuts down
     */
    fun shutdown() {
        try {
            scope.cancel()
            serverChannel?.close()
            clientChannels.values.forEach { 
                try {
                    it.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            clientChannels.clear()
            bossGroup.shutdownGracefully()
            workerGroup.shutdownGracefully()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    /**
     * Server handler for incoming connections
     */
    inner class P2PServerHandler : SimpleChannelInboundHandler<ByteBuf>() {
        private var connectedUserId: String? = null
        
        override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
            val bytes = ByteArray(msg.readableBytes())
            msg.readBytes(bytes)
            
            scope.launch {
                try {
                    val packetJson = String(bytes, Charsets.UTF_8)
                    val wrapper = json.decodeFromString<P2PPacketWrapper>(packetJson)
                    handleIncomingPacket(ctx.channel(), wrapper.packet)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        
        private suspend fun handleIncomingPacket(channel: Channel, packet: P2PPacket) {
            when (packet) {
                is P2PPacket.Handshake -> {
                    connectedUserId = packet.data.senderId
                    clientChannels[packet.data.senderId] = channel
                    keyExchange.establishSession(packet.data.senderId, packet.data.publicKey)
                    updateConnectionStatus(packet.data.senderId, ConnectionStatus.CONNECTED)
                    
                    // Send our handshake back
                    val response = HandshakePacket(
                        publicKey = keyExchange.getMyPublicKeyString(),
                        senderId = connectionManager.getMyUserId()
                    )
                    val responsePacket = P2PPacket.Handshake(response)
                    val wrapper = P2PPacketWrapper(responsePacket)
                    val responseJson = json.encodeToString(wrapper)
                    channel.writeAndFlush(Unpooled.copiedBuffer(responseJson.toByteArray(Charsets.UTF_8)))
                }
                else -> _incomingMessages.emit(packet)
            }
        }
        
        override fun channelActive(ctx: ChannelHandlerContext) {
            super.channelActive(ctx)
        }
        
        override fun channelInactive(ctx: ChannelHandlerContext) {
            connectedUserId?.let { userId ->
                updateConnectionStatus(userId, ConnectionStatus.DISCONNECTED)
                clientChannels.remove(userId)
            }
            super.channelInactive(ctx)
        }
        
        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            cause.printStackTrace()
            ctx.close()
        }
    }
    
    /**
     * Client handler for outgoing connections
     */
    inner class P2PClientHandler(private val peerId: String) : SimpleChannelInboundHandler<ByteBuf>() {
        
        override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
            val bytes = ByteArray(msg.readableBytes())
            msg.readBytes(bytes)
            
            scope.launch {
                try {
                    val packetJson = String(bytes, Charsets.UTF_8)
                    val wrapper = json.decodeFromString<P2PPacketWrapper>(packetJson)
                    
                    when (val packet = wrapper.packet) {
                        is P2PPacket.Handshake -> {
                            keyExchange.establishSession(peerId, packet.data.publicKey)
                            updateConnectionStatus(peerId, ConnectionStatus.CONNECTED)
                        }
                        else -> _incomingMessages.emit(packet)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        
        override fun channelActive(ctx: ChannelHandlerContext) {
            super.channelActive(ctx)
        }
        
        override fun channelInactive(ctx: ChannelHandlerContext) {
            updateConnectionStatus(peerId, ConnectionStatus.DISCONNECTED)
            clientChannels.remove(peerId)
            super.channelInactive(ctx)
        }
        
        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            cause.printStackTrace()
            updateConnectionStatus(peerId, ConnectionStatus.FAILED)
            ctx.close()
        }
    }
}

/**
 * Connection status enum
 */
enum class ConnectionStatus {
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    FAILED
}

/**
 * Wrapper for serializing packets
 */
@Serializable
data class P2PPacketWrapper(
    val packet: P2PPacket
)

/**
 * Sealed class for all P2P packet types
 */
@Serializable
sealed class P2PPacket {
    
    @Serializable
    data class Handshake(val data: HandshakePacket) : P2PPacket()
    
    @Serializable
    data class EncryptedMessage(
        val senderId: String,
        val encryptedData: ByteArray
    ) : P2PPacket() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as EncryptedMessage
            return senderId == other.senderId && encryptedData.contentEquals(other.encryptedData)
        }
        override fun hashCode(): Int = 31 * senderId.hashCode() + encryptedData.contentHashCode()
    }
    
    @Serializable
    data class FileTransfer(
        val attachmentId: String,
        val messageId: String,
        val fileName: String,
        val mimeType: String,
        val fileSize: Long,
        val encryptedData: ByteArray,
        val checksum: String
    ) : P2PPacket() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as FileTransfer
            return attachmentId == other.attachmentId
        }
        override fun hashCode(): Int = attachmentId.hashCode()
    }
    
    @Serializable
    data class TypingIndicator(
        val userId: String,
        val conversationId: String,
        val isTyping: Boolean
    ) : P2PPacket()
    
    @Serializable
    data class PresenceUpdate(
        val presence: PresenceInfo
    ) : P2PPacket()
    
    @Serializable
    data class MessageStatusUpdate(
        val messageId: String,
        val status: String,
        val timestamp: Long
    ) : P2PPacket()
    
    @Serializable
    data class ReadReceipt(
        val messageIds: List<String>,
        val timestamp: Long
    ) : P2PPacket()
    
    @Serializable
    data class Reaction(
        val messageId: String,
        val senderId: String,
        val emoji: String,
        val isRemoved: Boolean = false
    ) : P2PPacket()
    
    @Serializable
    data class MessageEdit(
        val messageId: String,
        val newContent: String,
        val timestamp: Long
    ) : P2PPacket()
    
    @Serializable
    data class MessageDelete(
        val messageId: String,
        val forEveryone: Boolean
    ) : P2PPacket()
}

/**
 * Handshake packet data
 */
@Serializable
data class HandshakePacket(
    val publicKey: String,
    val senderId: String
)

/**
 * Transport message for serialization
 */
@Serializable
data class TransportMessage(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val receiverId: String,
    val content: String,
    val type: String,
    val replyToId: String? = null,
    val createdAt: Long
)

/**
 * Extension function to convert Message to TransportMessage
 */
fun Message.toTransport() = TransportMessage(
    id = id,
    conversationId = conversationId,
    senderId = senderId,
    receiverId = receiverId,
    content = content,
    type = type.name,
    replyToId = replyToId,
    createdAt = createdAt
)
