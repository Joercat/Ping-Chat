// ui/screens/settings/SettingsViewModel.kt
package com.ping.app.ui.screens.settings

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ping.app.core.encryption.SecureStorage
import com.ping.app.core.network.ConnectionManager
import com.ping.app.core.network.P2PManager
import com.ping.app.data.models.User
import com.ping.app.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userRepository: UserRepository,
    private val connectionManager: ConnectionManager,
    private val p2pManager: P2PManager,
    private val secureStorage: SecureStorage
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()
    
    init {
        loadSettings()
        observeConnectionStatus()
    }
    
    private fun loadSettings() {
        viewModelScope.launch {
            try {
                val userId = connectionManager.getMyUserId()
                val user = userRepository.getUserById(userId)
                
                _uiState.update { state ->
                    state.copy(
                        currentUser = user,
                        appVersion = getAppVersion()
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }
    
    private fun observeConnectionStatus() {
        viewModelScope.launch {
            connectionManager.networkAvailable.collect { isConnected ->
                _uiState.update { it.copy(isConnected = isConnected) }
            }
        }
    }
    
    fun toggleDarkMode() {
        _uiState.update { it.copy(isDarkMode = !it.isDarkMode) }
        // Save preference
    }
    
    fun setFontSize(size: String) {
        _uiState.update { it.copy(fontSize = size) }
        // Save preference
    }
    
    fun showLogoutDialog() {
        _uiState.update { it.copy(showLogoutDialog = true) }
    }
    
    fun hideLogoutDialog() {
        _uiState.update { it.copy(showLogoutDialog = false) }
    }
    
    fun logout() {
        viewModelScope.launch {
            try {
                // Disconnect from all peers
                p2pManager.shutdown()
                
                // Clear secure storage
                secureStorage.clear()
                
                // Navigate to auth screen
                _uiState.update { 
                    it.copy(
                        showLogoutDialog = false,
                        isLoggedOut = true
                    )
                }
            } catch (e: Exception) {
                _uiState.update { 
                    it.copy(
                        showLogoutDialog = false,
                        error = e.message
                    )
                }
            }
        }
    }
    
    fun shareApp() {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Ping Messenger")
            putExtra(
                Intent.EXTRA_TEXT,
                "Check out Ping - a secure peer-to-peer messenger! Download it here: https://ping.app"
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share Ping").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
    
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
    
    private fun getAppVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }
}

data class SettingsUiState(
    val currentUser: User? = null,
    val isDarkMode: Boolean = true,
    val fontSize: String = "Medium",
    val isConnected: Boolean = false,
    val appVersion: String = "1.0.0",
    val showLogoutDialog: Boolean = false,
    val isLoggedOut: Boolean = false,
    val error: String? = null
)
