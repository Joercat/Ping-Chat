// ui/screens/chat/ChatViewModel.kt
package com.ping.app.ui.screens.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ping.app.core.network.ConnectionManager
import com.ping.app.core.network.MessageRouter
import com.ping.app.core.network.P2PManager
import com.ping.app.data.models.*
import com.ping.app.data.repository.ConversationRepository
import com.ping.app.data.repository.MessageRepository
import com.ping.app.data.repository.UserRepository
import com.ping.app.utils.FileUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
    private val userRepository: UserRepository,
    private val messageRouter: MessageRouter,
    private val p2pManager: P2PManager,
    private val connectionManager: ConnectionManager,
    private val fileUtils: FileUtils,
    @ApplicationContext private val context: Context
) : ViewModel() {
    
    private val conversationId: String = savedStateHandle.get<String>("conversationId") ?: ""
    
    val currentUserId: String = connectionManager.getMyUserId()
    
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()
    
    val messages: StateFlow<List<Message>> = messageRepository
        .getMessages(conversationId)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    
    val typingState: StateFlow<TypingState?> = messageRouter.typingUsers
        .map { it[conversationId] }
        .stateIn(viewModelScope, SharingStarted.Lazily, null)
    
    val peerPresence: StateFlow<PresenceInfo?> = messageRouter.userPresence
        .map { presenceMap ->
            _uiState.value.peerUser?.id?.let { peerId ->
                presenceMap[peerId]
            }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, null)
    
    init {
        loadConversation()
        observeConnectionStatus()
    }
    
    private fun loadConversation() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            
            try {
                val conversation = conversationRepository.getConversationById(conversationId)
                conversation?.let { conv ->
                    val peerId = conv.participantIds.firstOrNull { it != currentUserId }
                    val peerUser = peerId?.let { userRepository.getUserById(it) }
                    
                    _uiState.update { state ->
                        state.copy(
                            conversation = conv,
                            peerUser = peerUser,
                            isLoading = false
                        )
                    }
                    
                    // Connect to peer if not already connected
                    peerUser?.let { user ->
                        if (user.ipAddress != null && user.port != null) {
                            p2pManager.connectToPeer(user.id, user.ipAddress!!, user.port!!)
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }
    
    private fun observeConnectionStatus() {
        viewModelScope.launch {
            p2pManager.connectionStatus.collect { statusMap ->
                _uiState.value.peerUser?.id?.let { peerId ->
                    val status = statusMap[peerId]
                    _uiState.update { it.copy(connectionStatus = status) }
                }
            }
        }
    }
    
    fun sendMessage(content: String, replyToId: String? = null) {
        if (content.isBlank()) return
        
        val peerId = _uiState.value.peerUser?.id ?: return
        
        viewModelScope.launch {
            val message = Message(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                senderId = currentUserId,
                receiverId = peerId,
                content = content,
                type = MessageType.TEXT,
                status = MessageStatus.SENDING,
                replyToId = replyToId,
                createdAt = System.currentTimeMillis()
            )
            
            messageRouter.sendMessage(message)
            
            // Update conversation's last message
            conversationRepository.updateConversation(
                _uiState.value.conversation!!.copy(
                    lastMessage = message,
                    lastMessageTimestamp = message.createdAt,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }
    
    fun sendAttachment(uri: Uri, replyToId: String? = null) {
        val peerId = _uiState.value.peerUser?.id ?: return
        
        viewModelScope.launch {
            try {
                val fileInfo = fileUtils.getFileInfo(uri)
                
                // Check file size (10MB limit)
                if (fileInfo.size > Attachment.MAX_FILE_SIZE) {
                    _uiState.update { 
                        it.copy(error = "File size exceeds 10MB limit")
                    }
                    return@launch
                }
                
                val messageId = UUID.randomUUID().toString()
                val attachmentId = UUID.randomUUID().toString()
                
                val attachment = Attachment(
                    id = attachmentId,
                    messageId = messageId,
                    fileName = fileInfo.name,
                    fileSize = fileInfo.size,
                    mimeType = fileInfo.mimeType,
                    localPath = uri.toString(),
                    downloadStatus = DownloadStatus.COMPLETED
                )
                
                val messageType = when {
                    fileInfo.mimeType.startsWith("image/") -> MessageType.IMAGE
                    fileInfo.mimeType.startsWith("video/") -> MessageType.VIDEO
                    fileInfo.mimeType.startsWith("audio/") -> MessageType.AUDIO
                    else -> MessageType.FILE
                }
                
                val message = Message(
                    id = messageId,
                    conversationId = conversationId,
                    senderId = currentUserId,
                    receiverId = peerId,
                    content = fileInfo.name,
                    type = messageType,
                    status = MessageStatus.SENDING,
                    replyToId = replyToId,
                    attachments = listOf(attachment),
                    createdAt = System.currentTimeMillis()
                )
                
                // Read file data
                val fileData = fileUtils.readFileBytes(uri)
                
                // Send message with attachment
                messageRouter.sendMessageWithAttachment(message, attachment, fileData)
                
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to send file: ${e.message}") }
            }
        }
    }
    
    fun updateTypingState(isTyping: Boolean) {
        val peerId = _uiState.value.peerUser?.id ?: return
        messageRouter.sendTypingIndicator(conversationId, peerId, isTyping)
    }
    
    fun markConversationAsRead() {
        val peerId = _uiState.value.peerUser?.id ?: return
        
        viewModelScope.launch {
            messageRouter.markAsRead(conversationId, peerId)
            conversationRepository.clearUnreadCount(conversationId)
        }
    }
    
    fun addReaction(messageId: String, emoji: String) {
        viewModelScope.launch {
            messageRepository.addReaction(messageId, currentUserId, emoji)
            
            // Send reaction to peer
            _uiState.value.peerUser?.id?.let { peerId ->
                // messageRouter.sendReaction(messageId, peerId, emoji)
            }
        }
    }
    
    fun togglePinMessage(message: Message) {
        viewModelScope.launch {
            messageRepository.pinMessage(message.id, !message.isPinned)
        }
    }
    
    fun deleteMessage(messageId: String, forEveryone: Boolean) {
        viewModelScope.launch {
            if (forEveryone) {
                messageRepository.deleteMessageForEveryone(messageId)
                // Notify peer
                _uiState.value.peerUser?.id?.let { peerId ->
                    // Send delete notification
                }
            } else {
                messageRepository.deleteMessage(messageId)
            }
        }
    }
    
    fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("message", text)
        clipboard.setPrimaryClip(clip)
    }
    
    fun loadMoreMessages() {
        // Implement pagination
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            // Load more messages
            _uiState.update { it.copy(isLoadingMore = false) }
        }
    }
    
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

data class ChatUiState(
    val conversation: Conversation? = null,
    val peerUser: User? = null,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    val connectionStatus: com.ping.app.core.network.ConnectionStatus? = null
)
