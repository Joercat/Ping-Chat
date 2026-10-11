package com.ping.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.ping.app.di.AppGraph
import com.ping.app.di.StorageState
import com.ping.app.service.PingService
import com.ping.app.ui.PingController
import com.ping.app.ui.screens.AddContactScreen
import com.ping.app.ui.screens.ChatScreen
import com.ping.app.ui.screens.ContactScreen
import com.ping.app.ui.screens.HomeScreen
import com.ping.app.ui.screens.OnboardingScreen
import com.ping.app.ui.screens.PickedAttachment
import com.ping.app.ui.screens.SettingsScreen
import com.ping.app.ui.theme.PingTheme
import com.ping.app.ui.theme.PrimaryPurple
import com.ping.app.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Where the app was asked to go from the outside. */
private sealed interface DeepLink {
    /** `ping://chat/<peer hash>` or the conversation carried by a notification. */
    data class Chat(val conversationId: String) : DeepLink

    /** `ping://chat/peer:<peer hash>`: we know the contact but not the conversation yet. */
    data class ChatWithPeer(val peerHash: String) : DeepLink

    /** `ping://add/<code>` */
    data class Add(val code: String) : DeepLink
}

/** Screens are a small enum, so there is no need to pull in a navigation library. */
private sealed interface Screen {
    data object Home : Screen
    data class Chat(val conversationId: String) : Screen
    data object AddContact : Screen
    data class Contact(val peerHash: String) : Screen
    data object Settings : Screen
}

class MainActivity : ComponentActivity() {

    private lateinit var graph: AppGraph
    private val deepLink = MutableStateFlow<DeepLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        graph = (application as PingApplication).graph
        applyScreenshotGuard()
        // The flag has to follow the setting while the app is running, not only at launch.
        lifecycleScope.launch {
            graph.settings.state.map { it.screenshotGuard }.distinctUntilChanged().collect { applyScreenshotGuard() }
        }
        deepLink.value = parseDeepLink(intent)
        setContent {
            PingTheme {
                PingRoot(graph = graph, deepLink = deepLink)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLink.value = parseDeepLink(intent)
    }

    private fun applyScreenshotGuard() {
        val secure = runCatching { graph.settings.current.screenshotGuard }.getOrDefault(true)
        if (secure) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun parseDeepLink(intent: Intent?): DeepLink? {
        if (intent == null) return null
        intent.getStringExtra("conversation_id")?.let { if (it.isNotBlank()) return DeepLink.Chat(it) }
        val data: Uri = intent.data ?: return null
        if (data.scheme != "ping") return null
        val value = data.lastPathSegment?.trim().orEmpty()
        if (value.isEmpty()) return null
        return when (data.host) {
            "chat" ->
                if (value.startsWith("peer:")) DeepLink.ChatWithPeer(value.removePrefix("peer:"))
                else DeepLink.Chat(value)
            "add" -> DeepLink.Add(value)
            else -> null
        }
    }
}

@Composable
private fun PingRoot(graph: AppGraph, deepLink: MutableStateFlow<DeepLink?>) {
    val context = LocalContext.current
    val controller = remember { PingController(graph) }
    val storage by graph.storage.collectAsStateWithLifecycle()
    val settings by controller.settings.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Ask for the notification permission once we are far enough along to have something to say.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(storage, settings.onboardingComplete) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            storage is StorageState.Ready &&
            settings.onboardingComplete &&
            settings.notificationsEnabled &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    when (val state = storage) {
        is StorageState.Loading -> LoadingScreen()
        is StorageState.Failed -> FailureScreen(state.reason)
        is StorageState.Ready -> {
            if (!settings.onboardingComplete) {
                OnboardingScreen(controller = controller) {
                    graph.engine.start()
                    PingService.start(context)
                }
            } else {
                var unlocked by remember { mutableStateOf(!settings.appLock) }
                DisposableEffect(lifecycleOwner, settings.appLock) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_STOP) unlocked = false
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }
                if (settings.appLock && !unlocked) {
                    UnlockScreen(onUnlock = { unlocked = true })
                } else {
                    MainNavigation(controller = controller, deepLink = deepLink)
                }
            }
        }
    }
}

@Composable
private fun MainNavigation(controller: PingController, deepLink: MutableStateFlow<DeepLink?>) {
    val context = LocalContext.current
    val graph = controller.graph
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var picked by remember { mutableStateOf<PickedAttachment?>(null) }
    val notes by controller.notes.collectAsStateWithLifecycle()
    val link by deepLink.collectAsStateWithLifecycle()

    // Tell the engine which conversation is on screen, so it stays quiet about that one.
    val activeConversation = (screen as? Screen.Chat)?.conversationId
    LaunchedEffect(activeConversation) { graph.activeConversation.value = activeConversation }

    LaunchedEffect(notes) {
        if (notes.isNotEmpty()) {
            delay(2_800)
            graph.consumeNotes()
        }
    }

    LaunchedEffect(link) {
        when (val target = link) {
            null -> Unit
            is DeepLink.Chat -> {
                screen = Screen.Chat(target.conversationId)
                deepLink.value = null
            }
            is DeepLink.ChatWithPeer -> {
                controller.resolveConversation(target.peerHash) { conversation ->
                    if (conversation != null) {
                        deepLink.value = null
                        screen = Screen.Chat(conversation.id)
                    }
                }
            }
            is DeepLink.Add -> {
                controller.addContact(target.code)
                deepLink.value = null
                screen = Screen.Home
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            picked = PickedAttachment(
                uri = uri,
                name = displayNameOf(uri) ?: "attachment",
                mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (val current = screen) {
            is Screen.Home -> HomeScreen(
                controller = controller,
                onOpenChat = { screen = Screen.Chat(it.conversation.id) },
                onAddContact = { screen = Screen.AddContact },
                onSettings = { screen = Screen.Settings },
                onShowMyCode = { screen = Screen.AddContact }
            )

            is Screen.Chat -> ChatScreen(
                controller = controller,
                conversationId = current.conversationId,
                onBack = { screen = Screen.Home },
                onOpenContact = { peerHash -> screen = Screen.Contact(peerHash) },
                onPickAttachment = { picker.launch(arrayOf("*/*")) },
                pickedAttachment = picked,
                onPickedAttachmentHandled = { picked = null }
            )

            is Screen.AddContact -> AddContactScreen(
                controller = controller,
                onBack = { screen = Screen.Home },
                onAdded = { screen = Screen.Home }
            )

            is Screen.Contact -> ContactScreen(
                controller = controller,
                peerHash = current.peerHash,
                onBack = { screen = Screen.Home }
            )

            is Screen.Settings -> SettingsScreen(
                controller = controller,
                onBack = { screen = Screen.Home }
            )
        }

        if (notes.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp)
            ) {
                Text(
                    notes.last(),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
                )
            }
        }
    }

    BackHandler(enabled = screen != Screen.Home && !(screen is Screen.Chat)) {
        screen = Screen.Home
    }
}

@Composable
private fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = PrimaryPurple)
            Spacer(Modifier.height(16.dp))
            Text("Opening encrypted storage…", color = TextSecondary)
        }
    }
}

@Composable
private fun FailureScreen(reason: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(16.dp))
            Text("Encrypted storage did not open", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                reason,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Restart the phone and try again. If this keeps happening the database may have been " +
                    "restored from a backup, which Ping refuses to read on purpose.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Optional app lock, on top of the phone's own lock screen. */
@Composable
private fun UnlockScreen(onUnlock: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? androidx.fragment.app.FragmentActivity
    var problem by remember { mutableStateOf<String?>(null) }
    val authenticators = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        }
    }
    val available = remember {
        runCatching {
            BiometricManager.from(context).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
        }.getOrDefault(false)
    }

    fun prompt() {
        if (activity == null) {
            problem = "This device has no usable screen lock."
            return
        }
        val executor = ContextCompat.getMainExecutor(context)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                problem = null
                onUnlock()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                problem = errString.toString()
            }
        }
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Ping")
            .setSubtitle("Your messages stay encrypted until you unlock")
            .setAllowedAuthenticators(authenticators)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            builder.setNegativeButtonText("Cancel")
        }
        runCatching { BiometricPrompt(activity, executor, callback).authenticate(builder.build()) }
            .onFailure { problem = it.message }
    }

    LaunchedEffect(Unit) { if (available) prompt() }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = PrimaryPurple, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(20.dp))
            Text("Ping is locked", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                if (available) "Unlock to read your messages." else "Set up a screen lock on this phone first.",
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    if (available) {
                        prompt()
                    } else {
                        runCatching {
                            context.startActivity(Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (available) "Unlock" else "Open security settings") }
            problem?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun android.content.Context.displayNameOf(uri: Uri): String? = runCatching {
    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }
}.getOrNull()
