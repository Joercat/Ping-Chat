// ui/screens/profile/ProfileViewModel.kt
package com.ping.app.ui.screens.profile

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ping.app.core.network.ConnectionManager
import com.ping.app.data.models.User
import com.ping.app.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val connectionManager: ConnectionManager
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()
    
    fun loadProfile(userId: String?) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            
            try {
                val id = userId ?: connectionManager.getMyUserId()
                val user = userRepository.getUserById(id)
                
                _uiState.update { state ->
                    state.copy(
                        user = user,
                        isLoading = false,
                        isOwnProfile = userId == null,
                        editedDisplayName = user?.displayName ?: "",
                        editedStatus = user?.status ?: ""
                    )
                }
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
    
    fun toggleEditMode() {
        if (_uiState.value.isEditing) {
            saveProfile()
        } else {
            _uiState.update { it.copy(isEditing = true) }
        }
    }
    
    private fun saveProfile() {
        viewModelScope.launch {
            val state = _uiState.value
            val user = state.user ?: return@launch
            
            try {
                val updatedUser = user.copy(
                    displayName = state.editedDisplayName,
                    status = state.editedStatus.ifEmpty { null },
                    updatedAt = System.currentTimeMillis()
                )
                
                userRepository.updateUser(updatedUser)
                
                _uiState.update { 
                    it.copy(
                        user = updatedUser,
                        isEditing = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }
    
    fun updateDisplayName(name: String) {
        _uiState.update { it.copy(editedDisplayName = name) }
    }
    
    fun updateStatus(status: String) {
        _uiState.update { it.copy(editedStatus = status) }
    }
    
    fun updateAvatar(uri: Uri) {
        viewModelScope.launch {
            // Save avatar and update user
            val user = _uiState.value.user ?: return@launch
            
            try {
                val updatedUser = user.copy(
                    avatarUrl = uri.toString(),
                    updatedAt = System.currentTimeMillis()
                )
                
                userRepository.updateUser(updatedUser)
                
                _uiState.update { it.copy(user = updatedUser) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }
    
    fun toggleFavorite() {
        viewModelScope.launch {
            val user = _uiState.value.user ?: return@launch
            
            try {
                val updatedUser = user.copy(
                    isFavorite = !user.isFavorite,
                    updatedAt = System.currentTimeMillis()
                )
                
                userRepository.updateUser(updatedUser)
                
                _uiState.update { it.copy(user = updatedUser) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }
    
    fun toggleBlock() {
        viewModelScope.launch {
            val user = _uiState.value.user ?: return@launch
            
            try {
                val updatedUser = user.copy(
                    isBlocked = !user.isBlocked,
                    updatedAt = System.currentTimeMillis()
                )
                
                userRepository.updateUser(updatedUser)
                
                _uiState.update { it.copy(user = updatedUser) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }
    
    fun showQrCode() {
        _uiState.update { it.copy(showQrCode = true) }
    }
    
    fun hideQrCode() {
        _uiState.update { it.copy(showQrCode = false) }
    }
    
    fun shareProfile() {
        // Generate share intent
    }
    
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

data class ProfileUiState(
    val user: User? = null,
    val isLoading: Boolean = false,
    val isOwnProfile: Boolean = false,
    val isEditing: Boolean = false,
    val editedDisplayName: String = "",
    val editedStatus: String = "",
    val showQrCode: Boolean = false,
    val error: String? = null
)
