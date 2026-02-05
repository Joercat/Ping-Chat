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
    private val json = Json { ignoreUnknownKeys = true }
    
    private var serverChannel: Channel? = null
    private val clientChannels = mutableMapOf<String, Channel>()
    
    private val bossGroup = NioEventLoopGroup(1)
    private val workerGroup = NioEventLoopGroup()
    
    private val _incomingMessages = MutableSharedFlow<P2PPacket>()
    val incomingMessages: SharedFlow<P2PPacket> = _incomingMessages.asSharedFlow()
    
    private val _connectionStatus = MutableStateFlow<Map<String, ConnectionStatus>>(emptyMap())
    val connectionStatus: StateFlow<Map<String, ConnectionStatus>> = _connectionStatus.asStateFlow()
    
    private var myPort: Int = 0
    
    companion object {
        private const val DEFAULT_PORT = 9876
        private const val MAX_FRAME_LENGTH = 16 * 1024 * 1024 // 16MB for files
    }
    
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
        
        return try {
            val channelFuture = bootstrap.bind(port).sync()
            serverChannel = channelFuture.channel()
            myPort = (channelFuture.channel().localAddress() as InetSocketAddress).port
            myPort
        } catch (e: Exception) {
            // Try alternate port if default is in use
            val altPort = port + 1
            val channelFuture = bootstrap.bind(altPort).sync()
            serverChannel = channelFuture.channel()
            myPort = altPort
            myPort
        }
    }
    
    suspend fun connectToPeer(userId: String, address: String, port: Int): Boolean {
        return withContext(Dispatchers.IO) {
            try {
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
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                
                val future = bootstrap.connect(address, port).sync()
                if (future.isSuccess) {
                    clientChannels[userId] = future.channel()
                    updateConnectionStatus(userId, ConnectionStatus.CONNECTED)
                    
                    // Send handshake with public key
                    sendHandshake(userId)
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                updateConnectionStatus(userId, ConnectionStatus.FAILED)
                false
            }
        }
    }
    
    private suspend fun sendHandshake(userId: String) {
        val handshake = HandshakePacket(
            publicKey = keyExchange.getMyPublicKeyString(),
            userId = connectionManager.getMyUserId()
        )
        sendPacket(userId, P2PPacket.Handshake(handshake))
    }
    
    suspend fun sendMessage(userId: String, message: Message): Boolean {
        val sessionKey = keyExchange.getSessionKey(userId) ?: return false
        
        val messageJson = json.encodeToString(message.toTransport())
        val encrypted = cryptoManager.encryptMessage(messageJson.toByteArray(), sessionKey)
        
        val packet = P2PPacket.EncryptedMessage(
            senderId = connectionManager.getMyUserId(),
            encryptedData = encrypted.toByteArray()
        )
        
        return sendPacket(userId, packet)
    }
    
    suspend fun sendFile(userId: String, attachment: Attachment, fileData: ByteArray): Boolean {
        val sessionKey = keyExchange.getSessionKey(userId) ?: return false
        
        // Check file size
        if (fileData.size > Attachment.MAX_FILE_SIZE) {
            return false
        }
        
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
        
        return sendPacket(userId, packet)
    }
    
    suspend fun sendTypingIndicator(userId: String, conversationId: String, isTyping: Boolean): Boolean {
        val packet = P2PPacket.TypingIndicator(
            userId = connectionManager.getMyUserId(),
            conversationId = conversationId,
            isTyping = isTyping
        )
        return sendPacket(userId, packet)
    }
    
    suspend fun sendPresenceUpdate(userId: String, presence: PresenceInfo): Boolean {
        val packet = P2PPacket.PresenceUpdate(presence)
        return sendPacket(userId, packet)
    }
    
    suspend fun sendMessageStatus(userId: String, messageId: String, status: MessageStatus): Boolean {
        val packet = P2PPacket.MessageStatusUpdate(
            messageId = messageId,
            status = status.name,
            timestamp = System.currentTimeMillis()
        )
        return sendPacket(userId, packet)
    }
    
    suspend fun sendReadReceipt(userId: String, messageIds: List<String>): Boolean {
        val packet = P2PPacket.ReadReceipt(
            messageIds = messageIds,
            timestamp = System.currentTimeMillis()
        )
        return sendPacket(userId, packet)
    }
    
    private suspend fun sendPacket(userId: String, packet: P2PPacket): Boolean {
        return withContext(Dispatchers.IO) {
            val channel = clientChannels[userId] ?: return@withContext false
            
            if (!channel.isActive) {
                updateConnectionStatus(userId, ConnectionStatus.DISCONNECTED)
                return@withContext false
            }
            
            try {
                val packetJson = json.encodeToString(packet)
                val buffer = Unpooled.copiedBuffer(packetJson.toByteArray())
                channel.writeAndFlush(buffer).sync()
                true
            } catch (e: Exception) {
                false
            }
        }
    }
    
    fun broadcastPresence(presence: PresenceInfo) {
        scope.launch {
            clientChannels.keys.forEach { userId ->
                sendPresenceUpdate(userId, presence)
            }
        }
    }
    
    private fun updateConnectionStatus(userId: String, status: ConnectionStatus) {
        _connectionStatus.value = _connectionStatus.value + (userId to status)
    }
    
    fun disconnect(userId: String) {
        clientChannels[userId]?.close()
        clientChannels.remove(userId)
        updateConnectionStatus(userId, ConnectionStatus.DISCONNECTED)
    }
    
    fun shutdown() {
        scope.cancel()
        serverChannel?.close()
        clientChannels.values.forEach { it.close() }
        clientChannels.clear()
        bossGroup.shutdownGracefully()
        workerGroup.shutdownGracefully()
    }
    
    inner class P2PServerHandler : SimpleChannelInboundHandler<ByteBuf>() {
        private var connectedUserId: String? = null
        
        override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
            val bytes = ByteArray(msg.readableBytes())
            msg.readBytes(bytes)
            
            scope.launch {
                val packetJson = String(bytes)
                val packet = json.decodeFromString<P2PPacket>(packetJson)
                handleIncomingPacket(ctx.channel(), packet)
            }
        }
        
        private suspend fun handleIncomingPacket(channel: Channel, packet: P2PPacket) {
            when (packet) {
                is P2PPacket.Handshake -> {
                    connectedUserId = packet.data.userId
                    clientChannels[packet.data.userId] = channel
                    keyExchange.establishSession(packet.data.userId, packet.data.publicKey)
                    updateConnectionStatus(packet.data.userId, ConnectionStatus.CONNECTED)
                    
                    // Send our handshake back
                    val response = HandshakePacket(
                        publicKey = keyExchange.getMyPublicKeyString(),
                        userId = connectionManager.getMyUserId()
                    )
                    val responsePacket = P2PPacket.Handshake(response)
                    val responseJson = json.encodeToString(responsePacket)
                    channel.writeAndFlush(Unpooled.copiedBuffer(responseJson.toByteArray()))
                }
                else -> _incomingMessages.emit(packet)
            }
        }
        
        override fun channelInactive(ctx: ChannelHandlerContext) {
            connectedUserId?.let {
                updateConnectionStatus(it, ConnectionStatus.DISCONNECTED)
                clientChannels.remove(it)
            }
        }
        
        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            ctx.close()
        }
    }
    
    inner class P2PClientHandler(private val peerId: String) : SimpleChannelInboundHandler<ByteBuf>() {
        
        override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
            val bytes = ByteArray(msg.readableBytes())
            msg.readBytes(bytes)
            
            scope.launch {
                val packetJson = String(bytes)
                val packet = json.decodeFromString<P2PPacket>(packetJson)
                
                when (packet) {
                    is P2PPacket.Handshake -> {
                        keyExchange.establishSession(peerId, packet.data.publicKey)
                    }
                    else -> _incomingMessages.emit(packet)
                }
            }
        }
        
        override fun channelInactive(ctx: ChannelHandlerContext) {
            updateConnectionStatus(peerId, ConnectionStatus.DISCONNECTED)
        }
        
        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            ctx.close()
        }
    }
}

enum class ConnectionStatus {
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    FAILED
}

@Serializable
sealed class P2PPacket {
    @Serializable
    data class Handshake(val data: HandshakePacket) : P2PPacket()
    
    @Serializable
    data class EncryptedMessage(
        val senderId: String,
        val encryptedData: ByteArray
    ) : P2PPacket()
    
    @Serializable
    data class FileTransfer(
        val attachmentId: String,
        val messageId: String,
        val fileName: String,
        val mimeType: String,
        val fileSize: Long,
        val encryptedData: ByteArray,
        val checksum: String
    ) : P2PPacket()
    
    @Serializable
    data class TypingIndicator(
        val userId: String,
        val conversationId: String,
        val isTyping: Boolean
    ) : P2PPacket()
    
    @Serializable
    data class PresenceUpdate(val presence: PresenceInfo) : P2PPacket()
    
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
        val userId: String,
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

@Serializable
data class HandshakePacket(
    val publicKey: String,
    val userId: String
)

@Serializable
data class TransportMessage(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val receiverId: String,
    val content: String,
    val type: String,
    val replyToId: String?,
    val createdAt: Long
)

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
