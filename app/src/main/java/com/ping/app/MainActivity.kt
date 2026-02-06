// MainActivity.kt
package com.ping.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ping.app.core.encryption.SecureStorage
import com.ping.app.services.PingMessagingService
import com.ping.app.services.PresenceService
import com.ping.app.ui.screens.auth.AuthScreen
import com.ping.app.ui.screens.chat.ChatScreen
import com.ping.app.ui.screens.conversations.ConversationsScreen
import com.ping.app.ui.screens.newchat.NewChatScreen
import com.ping.app.ui.screens.profile.ProfileScreen
import com.ping.app.ui.screens.settings.NotificationSettingsScreen
import com.ping.app.ui.screens.settings.PrivacySettingsScreen
import com.ping.app.ui.screens.settings.SettingsScreen
import com.ping.app.ui.screens.splash.SplashScreen
import com.ping.app.ui.theme.PingTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    
    @Inject
    lateinit var secureStorage: SecureStorage
    
    private var keepSplashScreen = true
    
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
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
        
        setContent {
            PingTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var showCustomSplash by remember { mutableStateOf(true) }
                    var isUserLoggedIn by remember { mutableStateOf<Boolean?>(null) }
                    
                    val navController = rememberNavController()
                    
                    // Check login status
                    LaunchedEffect(Unit) {
                        isUserLoggedIn = secureStorage.getUserId() != null
                        keepSplashScreen = false
                    }
                    
                    if (showCustomSplash && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                        SplashScreen(
                            onSplashComplete = { loggedIn ->
                                showCustomSplash = false
                                isUserLoggedIn = loggedIn
                            }
                        )
                    } else {
                        isUserLoggedIn?.let { loggedIn ->
                            PingNavHost(
                                navController = navController,
                                startDestination = if (loggedIn) Screen.Conversations.route else Screen.Auth.route,
                                onAuthComplete = {
                                    checkAndRequestPermissions()
                                },
                                onLogout = {
                                    navController.navigate(Screen.Auth.route) {
                                        popUpTo(0) { inclusive = true }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
        
        // Handle notification intent
        handleIntent(intent)
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }
    
    private fun handleIntent(intent: Intent?) {
        val conversationId = intent?.getStringExtra(EXTRA_CONVERSATION_ID)
        if (conversationId != null) {
            // Navigate to conversation - this would require a more complex setup
            // with a shared ViewModel or saved state
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
        
        // Media permissions
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
        
        // Camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.CAMERA)
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
    
    override fun onDestroy() {
        super.onDestroy()
        // Services will continue running in background
    }
    
    companion object {
        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
        const val EXTRA_USER_ID = "extra_user_id"
    }
}

// Navigation routes
sealed class Screen(val route: String) {
    object Splash : Screen("splash")
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
    navController: androidx.navigation.NavHostController,
    startDestination: String,
    onAuthComplete: () -> Unit,
    onLogout: () -> Unit
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
        
        // Chat Screen
        composable(
            route = Screen.Chat.route,
            arguments = listOf(
                navArgument("conversationId") { type = NavType.StringType }
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
                onCreateGroup = {
                    // Navigate to create group screen
                },
                onScanQr = {
                    // Navigate to QR scanner
                }
            )
        }
        
        // Profile Screen (other users)
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
                onNavigateToProfile = {
                    navController.navigate(Screen.MyProfile.route)
                },
                onNavigateToPrivacy = {
                    navController.navigate(Screen.PrivacySettings.route)
                },
                onNavigateToNotifications = {
                    navController.navigate(Screen.NotificationSettings.route)
                },
                onNavigateToStorage = {
                    navController.navigate(Screen.StorageSettings.route)
                },
                onNavigateToHelp = {
                    navController.navigate(Screen.Help.route)
                },
                onNavigateToAbout = {
                    navController.navigate(Screen.About.route)
                }
            )
        }
        
        // Privacy Settings Screen
        composable(Screen.PrivacySettings.route) {
            PrivacySettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToBlockedContacts = {
                    navController.navigate(Screen.BlockedContacts.route)
                }
            )
        }
        
        // Notification Settings Screen
        composable(Screen.NotificationSettings.route) {
            NotificationSettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        
        // Storage Settings Screen
        composable(Screen.StorageSettings.route) {
            StorageSettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        
        // Blocked Contacts Screen
        composable(Screen.BlockedContacts.route) {
            BlockedContactsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        
        // Help Screen
        composable(Screen.Help.route) {
            HelpScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        
        // About Screen
        composable(Screen.About.route) {
            AboutScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}

// Placeholder screens for missing ones
@Composable
fun StorageSettingsScreen(onNavigateBack: () -> Unit) {
    PlaceholderScreen(title = "Storage & Data", onNavigateBack = onNavigateBack)
}

@Composable
fun BlockedContactsScreen(onNavigateBack: () -> Unit) {
    PlaceholderScreen(title = "Blocked Contacts", onNavigateBack = onNavigateBack)
}

@Composable
fun HelpScreen(onNavigateBack: () -> Unit) {
    PlaceholderScreen(title = "Help", onNavigateBack = onNavigateBack)
}

@Composable
fun AboutScreen(onNavigateBack: () -> Unit) {
    PlaceholderScreen(title = "About Ping", onNavigateBack = onNavigateBack)
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PlaceholderScreen(
    title: String,
    onNavigateBack: () -> Unit
) {
    androidx.compose.material3.Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = {
                    androidx.compose.material3.Text(
                        text = title,
                        color = com.ping.app.ui.theme.TextPrimary
                    )
                },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onNavigateBack) {
                        androidx.compose.material3.Icon(
                            imageVector = androidx.compose.material.icons.Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = com.ping.app.ui.theme.TextPrimary
                        )
                    }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = com.ping.app.ui.theme.DarkBackground
                )
            )
        },
        containerColor = com.ping.app.ui.theme.DarkBackground
    ) { paddingValues ->
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            androidx.compose.material3.Text(
                text = "Coming Soon",
                style = MaterialTheme.typography.headlineSmall,
                color = com.ping.app.ui.theme.TextSecondary
            )
        }
    }
}
