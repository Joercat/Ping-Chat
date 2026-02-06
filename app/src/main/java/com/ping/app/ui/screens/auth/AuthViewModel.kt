// ui/screens/auth/AuthViewModel.kt
package com.ping.app.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ping.app.core.encryption.KeyExchange
import com.ping.app.core.encryption.SecureStorage
import com.ping.app.core.network.ConnectionManager
import com.ping.app.data.models.PresenceStatus
import com.ping.app.data.models.User
import com.ping.app.data.repository.UserRepository
import com.ping.app.utils.isValidUsername
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val secureStorage: SecureStorage,
    private val keyExchange: KeyExchange,
    private val connectionManager: ConnectionManager
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()
    
    fun updateUsername(username: String) {
        val trimmed = username.trim().lowercase()
        val error = when {
            trimmed.isEmpty() -> null
            trimmed.length < 3 -> "Username must be at least 3 characters"
            trimmed.length > 20 -> "Username must be at most 20 characters"
            !trimmed.isValidUsername() -> "Only letters, numbers, and underscore allowed"
            else -> null
        }
        
        _uiState.update { state ->
            state.copy(
                username = trimmed,
                usernameError = error,
                isUsernameValid = error == null && trimmed.length >= 3
            )
        }
    }
    
    fun updateDisplayName(name: String) {
        _uiState.update { it.copy(displayName = name) }
    }
    
    fun updateStatus(status: String) {
        _uiState.update { it.copy(status = status) }
    }
    
    fun createProfile() {
        val state = _uiState.value
        
        if (!state.isFormValid) return
        
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            
            try {
                // Initialize encryption keys
                val keyPair = keyExchange.initializeKeys()
                val publicKeyString = keyExchange.getMyPublicKeyString()
                
                // Create user
                val userId = connectionManager.getMyUserId()
                val deviceId = connectionManager.getDeviceId()
                
                val user = User(
                    id = userId,
                    username = state.username,
                    displayName = state.displayName.ifEmpty { state.username },
                    publicKey = publicKeyString,
                    deviceId = deviceId,
                    status = state.status.ifEmpty { null },
                    presence = PresenceStatus.ONLINE
                )
                
                // Save user
                userRepository.insertUser(user)
                secureStorage.saveUserId(userId)
                
                _uiState.update { 
                    it.copy(
                        isLoading = false,
                        isProfileCreated = true
                    )
                }
                
            } catch (e: Exception) {
                _uiState.update { 
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to create profile"
                    )
                }
            }
        }
    }
    
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

data class AuthUiState(
    val username: String = "",
    val displayName: String = "",
    val status: String = "",
    val usernameError: String? = null,
    val isUsernameValid: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val isProfileCreated: Boolean = false
) {
    val isFormValid: Boolean
        get() = isUsernameValid && displayName.length >= 2
}
