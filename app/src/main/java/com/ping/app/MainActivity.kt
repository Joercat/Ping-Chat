// MainActivity.kt (updated with complete navigation)
package com.ping.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ping.app.services.PingMessagingService
import com.ping.app.services.PresenceService
import com.ping.app.ui.screens.auth.AuthScreen
import com.ping.app.ui.screens.chat.ChatScreen
import com.ping.app.ui.screens.conversations.ConversationsScreen
import com.ping.app.ui.screens.profile.ProfileScreen
import com.ping.app.ui.screens.settings.NotificationSettingsScreen
import com.ping.app.ui.screens.settings.PrivacySettingsScreen
import com.ping.app.ui.screens.settings.SettingsScreen
import com.ping.app.ui.screens.splash.SplashScreen
import com.ping.app.ui.theme.PingTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.ping.app.core.encryption.SecureStorage

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    
    @Inject
    lateinit var secureStorage: SecureStorage
    
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            startServices()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            PingTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    var showSplash by remember { mutableStateOf(true) }
                    
                    if (showSplash) {
                        SplashScreen(
                            onSplashComplete = { isLoggedIn ->
                                showSplash = false
                                if (!isLoggedIn) {
                                    navController.navigate("auth") {
                                        popUpTo("splash") { inclusive = true }
                                    }
                                }
                            }
                        )
                    } else {
                        NavHost(
                            navController = navController,
                            startDestination = if (isUserLoggedIn()) "conversations" else "auth"
                        ) {
                            // Auth
                            composable("auth") {
                                AuthScreen(
                                    onAuthComplete = {
                                        checkAndRequestPermissions()
                                        navController.navigate("conversations") {
                                            popUpTo("auth") { inclusive = true }
                                        }
                                    }
                                )
                            }
                            
                            // Conversations
                            composable("conversations") {
                                ConversationsScreen(
                                    onNavigateToChat = { conversationId ->
                                        navController.navigate("chat/$conversationId")
                                    },
                                    onNavigateToNewChat = {
                                        navController.navigate("new_chat")
                                    },
                                    onNavigateToSettings = {
                                        navController.navigate("settings")
                                    }
                                )
                            }
                            
                            // Chat
                            composable(
                                route = "chat/{conversationId}",
                                arguments = listOf(
                                    navArgument("conversationId") { type = NavType.StringType }
                                )
                            ) { backStackEntry ->
                                val conversationId = backStackEntry.arguments?.getString("conversationId") ?: ""
                                ChatScreen(
                                    conversationId = conversationId,
                                    onNavigateBack = { navController.popBackStack() },
                                    onNavigateToProfile = { userId ->
                                        navController.navigate("profile/$userId")
                                    }
                                )
                            }
                            
                            // New Chat
                            composable("new_chat") {
                                // NewChatScreen - list of contacts to start chat
                            }
                            
                            // Profile (own or other user)
                            composable(
                                route = "profile/{userId}",
                                arguments = listOf(
                                    navArgument("userId") { 
                                        type = NavType.StringType
                                        nullable = true
                                        defaultValue = null
                                    }
                                )
                            ) { backStackEntry ->
                                val userId = backStackEntry.arguments?.getString("userId")
                                ProfileScreen(
                                    userId = userId,
                                    onNavigateBack = { navController.popBackStack() },
                                    onStartChat = { recipientId ->
                                        // Find or create conversation and navigate
                                        navController.navigate("chat/$recipientId")
                                    }
                                )
                            }
                            
                            // My Profile
                            composable("my_profile") {
                                ProfileScreen(
                                    userId = null,
                                    onNavigateBack = { navController.popBackStack() },
                                    onStartChat = null
                                )
                            }
                            
                            // Settings
                            composable("settings") {
                                SettingsScreen(
                                    onNavigateBack = { navController.popBackStack() },
                                    onNavigateToProfile = { navController.navigate("my_profile") },
                                    onNavigateToPrivacy = { navController.navigate("settings/privacy") },
                                    onNavigateToNotifications = { navController.navigate("settings/notifications") },
                                    onNavigateToStorage = { navController.navigate("settings/storage") },
                                    onNavigateToHelp = { navController.navigate("settings/help") },
                                    onNavigateToAbout = { navController.navigate("settings/about") }
                                )
                            }
                            
                            // Privacy Settings
                            composable("settings/privacy") {
                                PrivacySettingsScreen(
                                    onNavigateBack = { navController.popBackStack() },
                                    onNavigateToBlockedContacts = { 
                                        navController.navigate("settings/blocked") 
                                    }
                                )
                            }
                            
                            // Notification Settings
                            composable("settings/notifications") {
                                NotificationSettingsScreen(
                                    onNavigateBack = { navController.popBackStack() }
                                )
                            }
                            
                            // Storage Settings
                            composable("settings/storage") {
                                // StorageSettingsScreen
                            }
                            
                            // Help
                            composable("settings/help") {
                                // HelpScreen
                            }
                            
                            // About
                            composable("settings/about") {
                                // AboutScreen
                            }
                            
                            // Blocked Contacts
                            composable("settings/blocked") {
                                // BlockedContactsScreen
                            }
                        }
                    }
                }
            }
        }
        
        // Handle notification intent
        handleIntent(intent)
    }
    
    private fun isUserLoggedIn(): Boolean {
        return secureStorage.getUserId() != null
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }
    
    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra("extra_conversation_id")?.let { conversationId ->
            // Navigate to conversation
        }
    }
    
    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) 
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) 
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) 
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        
        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            startServices()
        }
    }
    
    private fun startServices() {
        // Start messaging service
        val messagingServiceIntent = Intent(this, PingMessagingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(messagingServiceIntent)
        } else {
            startService(messagingServiceIntent)
        }
        
        // Start presence service
        val presenceServiceIntent = Intent(this, PresenceService::class.java)
        startService(presenceServiceIntent)
    }
}
