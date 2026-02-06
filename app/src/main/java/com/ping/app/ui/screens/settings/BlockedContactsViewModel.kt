// ui/screens/settings/BlockedContactsViewModel.kt
package com.ping.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ping.app.data.models.User
import com.ping.app.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BlockedContactsViewModel @Inject constructor(
    private val userRepository: UserRepository
) : ViewModel() {
    
    private val _blockedUsers = MutableStateFlow<List<User>>(emptyList())
    val blockedUsers: StateFlow<List<User>> = _blockedUsers.asStateFlow()
    
    init {
        loadBlockedUsers()
    }
    
    private fun loadBlockedUsers() {
        viewModelScope.launch {
            userRepository.getBlockedUsers().collect { users ->
                _blockedUsers.value = users
            }
        }
    }
    
    fun unblockUser(userId: String) {
        viewModelScope.launch {
            try {
                val user = userRepository.getUserById(userId)
                user?.let {
                    userRepository.updateUser(it.copy(isBlocked = false))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
