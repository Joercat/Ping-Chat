// ui/screens/newchat/NewChatViewModel.kt
package com.ping.app.ui.screens.newchat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ping.app.core.network.ConnectionManager
import com.ping.app.data.models.User
import com.ping.app.data.repository.ConversationRepository
import com.ping.app.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NewChatViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val conversationRepository: ConversationRepository,
    private val connectionManager: ConnectionManager
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(NewChatUiState())
    val uiState: StateFlow<NewChatUiState> = _uiState.asStateFlow()
    
    val users: StateFlow<List<User>> = userRepository
        .getUsers()
        .map { users -> 
            users.filter { it.id != connectionManager.getMyUserId() }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    
    private val _searchResults = MutableStateFlow<List<User>>(emptyList())
    val searchResults: StateFlow<List<User>> = _searchResults.asStateFlow()
    
    fun searchUsers(query: String) {
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            return
        }
        
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val user = userRepository.getUserByUsername(query.lowercase())
                _searchResults.value = listOfNotNull(user)
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
    
    fun startChatWithUser(userId: String, onCreated: (String) -> Unit) {
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
    
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

data class NewChatUiState(
    val isLoading: Boolean = false,
    val error: String? = null
)
