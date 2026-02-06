// MainActivity.kt
package com.ping.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.ping.app.core.encryption.SecureStorage
import com.ping.app.services.PingMessagingService
import com.ping.app.services.PresenceService
import com.ping.app.ui.screens.auth.AuthScreen
import com.ping.app.ui.screens.chat.ChatScreen
import com.ping.app.ui.screens.conversations.ConversationsScreen
import com.ping.app.ui.screens.newchat.NewChatScreen
import com.ping.app.ui.screens.profile.ProfileScreen
import com.ping.app.ui.screens.settings.*
import com.ping.app.ui.screens.splash.SplashScreen
import com.ping.app.ui.theme.DarkBackground
import com.ping.app.ui.theme.PingTheme
import com.ping.app.ui.theme.PrimaryPurple
import com.ping.app.ui.theme.TextPrimary
import com.ping.app.ui.theme.TextSecondary
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    
    @Inject
    lateinit var secureStorage: SecureStorage
    
    private var keepSplashScreen = true
    private var pendingConversationId: String? = null
    
    // Navigation controller reference for deep linking
    private var navController: NavHostController? = null
    
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            startServices()
        } else {
            // Show which permissions were denied
            val deniedPermissions = permissions.filter { !it.value }.keys
            if (deniedPermissions.isNotEmpty()) {
                Toast.makeText(
                    this,
                    "Some features may not work without permissions",
                    Toast.LENGTH_LONG
                ).show()
            }
            // Still start services for basic functionality
            startServices()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        // Install splash screen (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val splashScreen = installSplashScreen()
            splashScreen.setKeepOnScreenCondition { keepSplashScreen }
        }
        
        super.onCreate(savedInstanceState)
        
        // Handle initial intent
        pendingConversationId = extractConversationIdFromIntent(intent)
        
        setContent {
            PingTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var showCustomSplash by remember { mutableStateOf(Build.VERSION.SDK_INT < Build.VERSION_CODES.S) }
                    var isUserLoggedIn by remember { mutableStateOf<Boolean?>(null) }
                    
                    val navControllerState = rememberNavController()
                    
                    // Store nav controller reference
                    LaunchedEffect(navControllerState) {
                        navController = navControllerState
                    }
                    
                    // Check login status
                    LaunchedEffect(Unit) {
                        isUserLoggedIn = checkUserLoggedIn()
                        keepSplashScreen = false
                    }
                    
                    // Handle pending navigation after login check
                    LaunchedEffect(isUserLoggedIn, pendingConversationId) {
                        if (isUserLoggedIn == true && pendingConversationId != null) {
                            navControllerState.navigate(Screen.Chat.createRoute(pendingConversationId!!)) {
                                popUpTo(Screen.Conversations.route)
                            }
                            pendingConversationId = null
                        }
                    }
                    
                    if (showCustomSplash) {
                        SplashScreen(
                            onSplashComplete = { loggedIn ->
                                showCustomSplash = false
                                isUserLoggedIn = loggedIn
                            }
                        )
                    } else {
                        isUserLoggedIn?.let { loggedIn ->
                            PingNavHost(
                                navController = navControllerState,
                                startDestination = if (loggedIn) Screen.Conversations.route else Screen.Auth.route,
                                onAuthComplete = {
                                    checkAndRequestPermissions()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        
        val conversationId = extractConversationIdFromIntent(intent)
        if (conversationId != null) {
            navController?.let { nav ->
                nav.navigate(Screen.Chat.createRoute(conversationId)) {
                    popUpTo(Screen.Conversations.route) {
                        saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                }
            } ?: run {
                pendingConversationId = conversationId
            }
        }
    }
    
    private fun extractConversationIdFromIntent(intent: Intent?): String? {
        if (intent == null) return null
        
        // From notification extras
        intent.getStringExtra(EXTRA_CONVERSATION_ID)?.let { return it }
        
        // From deep link
        intent.data?.let { uri ->
            if (uri.scheme == "ping" && uri.host == "chat") {
                return uri.getQueryParameter("id") ?: uri.lastPathSegment
            }
        }
        
        return null
    }
    
    private fun checkUserLoggedIn(): Boolean {
        return try {
            secureStorage.getUserId() != null
        } catch (e: Exception) {
            false
        }
    }
    
    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        
        // Notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        
        // Media permissions (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        
        // Camera permission - FIXED: Now requested at runtime
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.CAMERA)
        }
        
        // Audio recording permission - FIXED: Now requested at runtime
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        
        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            startServices()
        }
    }
    
    private fun startServices() {
        try {
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
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    companion object {
        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
        const val EXTRA_USER_ID = "extra_user_id"
        const val ACTION_OPEN_CHAT = "com.ping.app.OPEN_CHAT"
    }
}

// Navigation routes
sealed class Screen(val route: String) {
    object Auth : Screen("auth")
    object Conversations : Screen("conversations")
    object Chat : Screen("chat/{conversationId}") {
        fun createRoute(conversationId: String) = "chat/$conversationId"
    }
    object NewChat : Screen("new_chat")
    object Profile : Screen("profile/{userId}") {
        fun createRoute(userId: String?) = "profile/${userId ?: "me"}"
    }
    object MyProfile : Screen("profile/me")
    object Settings : Screen("settings")
    object PrivacySettings : Screen("settings/privacy")
    object NotificationSettings : Screen("settings/notifications")
    object StorageSettings : Screen("settings/storage")
    object BlockedContacts : Screen("settings/blocked")
    object Help : Screen("settings/help")
    object About : Screen("settings/about")
}

@Composable
fun PingNavHost(
    navController: NavHostController,
    startDestination: String,
    onAuthComplete: () -> Unit
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = {
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Left,
                animationSpec = tween(300)
            )
        },
        exitTransition = {
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Left,
                animationSpec = tween(300)
            )
        },
        popEnterTransition = {
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Right,
                animationSpec = tween(300)
            )
        },
        popExitTransition = {
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Right,
                animationSpec = tween(300)
            )
        }
    ) {
        // Auth Screen
        composable(Screen.Auth.route) {
            AuthScreen(
                onAuthComplete = {
                    onAuthComplete()
                    navController.navigate(Screen.Conversations.route) {
                        popUpTo(Screen.Auth.route) { inclusive = true }
                    }
                }
            )
        }
        
        // Conversations Screen
        composable(Screen.Conversations.route) {
            ConversationsScreen(
                onNavigateToChat = { conversationId ->
                    navController.navigate(Screen.Chat.createRoute(conversationId))
                },
                onNavigateToNewChat = {
                    navController.navigate(Screen.NewChat.route)
                },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }
        
        // Chat Screen with deep link support
        composable(
            route = Screen.Chat.route,
            arguments = listOf(
                navArgument("conversationId") { type = NavType.StringType }
            ),
            deepLinks = listOf(
                navDeepLink { uriPattern = "ping://chat/{conversationId}" }
            )
        ) { backStackEntry ->
            val conversationId = backStackEntry.arguments?.getString("conversationId") ?: ""
            ChatScreen(
                conversationId = conversationId,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToProfile = { userId ->
                    navController.navigate(Screen.Profile.createRoute(userId))
                }
            )
        }
        
        // New Chat Screen
        composable(Screen.NewChat.route) {
            NewChatScreen(
                onNavigateBack = { navController.popBackStack() },
                onStartChat = { conversationId ->
                    navController.navigate(Screen.Chat.createRoute(conversationId)) {
                        popUpTo(Screen.NewChat.route) { inclusive = true }
                    }
                },
                onCreateGroup = { },
                onScanQr = { }
            )
        }
        
        // Profile Screen
        composable(
            route = Screen.Profile.route,
            arguments = listOf(
                navArgument("userId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val userId = backStackEntry.arguments?.getString("userId")
            val isOwnProfile = userId == null || userId == "me"
            
            ProfileScreen(
                userId = if (isOwnProfile) null else userId,
                onNavigateBack = { navController.popBackStack() },
                onStartChat = if (isOwnProfile) null else { recipientId ->
                    navController.navigate(Screen.Chat.createRoute(recipientId)) {
                        popUpTo(Screen.Conversations.route)
                    }
                }
            )
        }
        
        // My Profile Screen
        composable(Screen.MyProfile.route) {
            ProfileScreen(
                userId = null,
                onNavigateBack = { navController.popBackStack() },
                onStartChat = null
            )
        }
        
        // Settings Screen
        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToProfile = { navController.navigate(Screen.MyProfile.route) },
                onNavigateToPrivacy = { navController.navigate(Screen.PrivacySettings.route) },
                onNavigateToNotifications = { navController.navigate(Screen.NotificationSettings.route) },
                onNavigateToStorage = { navController.navigate(Screen.StorageSettings.route) },
                onNavigateToHelp = { navController.navigate(Screen.Help.route) },
                onNavigateToAbout = { navController.navigate(Screen.About.route) }
            )
        }
        
        // Privacy Settings
        composable(Screen.PrivacySettings.route) {
            PrivacySettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToBlockedContacts = { navController.navigate(Screen.BlockedContacts.route) }
            )
        }
        
        // Notification Settings
        composable(Screen.NotificationSettings.route) {
            NotificationSettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        
        // Storage Settings - IMPLEMENTED
        composable(Screen.StorageSettings.route) {
            StorageSettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        
        // Blocked Contacts - IMPLEMENTED
        composable(Screen.BlockedContacts.route) {
            BlockedContactsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        
        // Help - IMPLEMENTED
        composable(Screen.Help.route) {
            HelpScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        
        // About - IMPLEMENTED
        composable(Screen.About.route) {
            AboutScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
