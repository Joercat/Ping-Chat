// ui/screens/settings/StorageSettingsViewModel.kt
package com.ping.app.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class StorageSettingsViewModel @Inject constructor() : ViewModel() {
    
    private val _uiState = MutableStateFlow(StorageSettingsUiState())
    val uiState: StateFlow<StorageSettingsUiState> = _uiState.asStateFlow()
    
    fun calculateStorageUsage(context: Context) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val pingDir = File(context.filesDir, "ping_files")
                    val cacheDir = context.cacheDir
                    
                    val photosSize = calculateDirSize(File(pingDir, "images"))
                    val videosSize = calculateDirSize(File(pingDir, "videos"))
                    val audioSize = calculateDirSize(File(pingDir, "audio"))
                    val documentsSize = calculateDirSize(File(pingDir, "files"))
                    val cacheSize = calculateDirSize(cacheDir)
                    
                    val totalUsed = photosSize + videosSize + audioSize + documentsSize + cacheSize
                    val totalStorage = context.filesDir.totalSpace
                    
                    _uiState.update { state ->
                        state.copy(
                            photosSize = formatSize(photosSize),
                            videosSize = formatSize(videosSize),
                            audioSize = formatSize(audioSize),
                            documentsSize = formatSize(documentsSize),
                            cacheSize = formatSize(cacheSize),
                            usedStorage = formatSize(totalUsed),
                            totalStorage = formatSize(totalStorage),
                            usagePercentage = if (totalStorage > 0) totalUsed.toFloat() / totalStorage.toFloat() else 0f
                        )
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
    
    private fun calculateDirSize(dir: File): Long {
        if (!dir.exists()) return 0
        return dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
    }
    
    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
    
    fun clearCache(context: Context) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    context.cacheDir.deleteRecursively()
                    calculateStorageUsage(context)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
    
    fun clearMedia(context: Context) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val pingDir = File(context.filesDir, "ping_files")
                    pingDir.deleteRecursively()
                    hideClearMediaDialog()
                    calculateStorageUsage(context)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
    
    fun showMobileDataDialog() {
        // Implement dialog
    }
    
    fun showWifiDialog() {
        // Implement dialog
    }
    
    fun showRoamingDialog() {
        // Implement dialog
    }
    
    fun showClearMediaDialog() {
        _uiState.update { it.copy(showClearMediaDialog = true) }
    }
    
    fun hideClearMediaDialog() {
        _uiState.update { it.copy(showClearMediaDialog = false) }
    }
    
    fun resetNetworkStats() {
        _uiState.update { 
            it.copy(
                dataSent = "0 B",
                dataReceived = "0 B"
            )
        }
    }
}

data class StorageSettingsUiState(
    val photosSize: String = "0 B",
    val videosSize: String = "0 B",
    val audioSize: String = "0 B",
    val documentsSize: String = "0 B",
    val cacheSize: String = "0 B",
    val usedStorage: String = "0 B",
    val totalStorage: String = "0 B",
    val usagePercentage: Float = 0f,
    val mobileDataSetting: String = "Photos, Videos, Audio, Documents",
    val wifiSetting: String = "All media",
    val roamingSetting: String = "No media",
    val dataSent: String = "0 B",
    val dataReceived: String = "0 B",
    val showClearMediaDialog: Boolean = false
)
