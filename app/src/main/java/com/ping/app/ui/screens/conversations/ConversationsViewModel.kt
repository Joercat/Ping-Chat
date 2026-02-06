// ui/screens/conversations/ConversationsViewModel.kt
package com.ping.app.ui.screens.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ping.app.core.network.ConnectionManager
import com.ping.app.core.network.MessageRouter
import com.ping.app.core.network.TypingState
import com.ping.app.data.models.Conversation
import com.ping.app.data.models.User
import com.ping.app.data.repository.ConversationRepository
import com.ping.app.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConversationsViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val userRepository: UserRepository,
    private val messageRouter: MessageRouter,
    private val connectionManager: ConnectionManager
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(ConversationsUiState())
    val uiState: StateFlow<ConversationsUiState> = _uiState.asStateFlow()
    
    val conversations: StateFlow<List<Conversation>> = conversationRepository
        .getConversations()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    
    val onlineUsers: StateFlow<List<User>> = userRepository
        .getOnlineUsers()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    
    private val typingUsers: StateFlow<Map<String, TypingState>> = messageRouter
        .typingUsers
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyMap())
    
    val totalUnreadCount: StateFlow<Int> = conversationRepository
        .getTotalUnreadCount()
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)
    
    init {
        loadData()
    }
    
    private fun loadData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                // Initial data loading if needed
                _uiState.update { it.copy(isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { 
                    it.copy(
                        isLoading = false,
                        error = e.message
                    )
                }
            }
        }
    }
    
    fun getTypingUser(conversationId: String): String? {
        return typingUsers.value[conversationId]?.userId
    }
    
    fun openOrCreateConversation(userId: String, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val conversation = conversationRepository.findOrCreateDirectConversation(
                    userId = userId,
                    currentUserId = connectionManager.getMyUserId()
                )
                onCreated(conversation.id)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }
    
    fun pinConversation(conversationId: String, isPinned: Boolean) {
        viewModelScope.launch {
            conversationRepository.setPinned(conversationId, isPinned)
        }
    }
    
    fun muteConversation(conversationId: String, isMuted: Boolean, duration: Long? = null) {
        viewModelScope.launch {
            val mutedUntil = duration?.let { System.currentTimeMillis() + it }
            conversationRepository.setMuted(conversationId, isMuted, mutedUntil)
        }
    }
    
    fun archiveConversation(conversationId: String) {
        viewModelScope.launch {
            conversationRepository.setArchived(conversationId, true)
        }
    }
    
    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            val conversation = conversationRepository.getConversationById(conversationId)
            conversation?.let {
                // Delete conversation and messages
            }
        }
    }
    
    fun searchConversations(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        // Implement search filtering
    }
    
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

data class ConversationsUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val searchQuery: String = ""
)
