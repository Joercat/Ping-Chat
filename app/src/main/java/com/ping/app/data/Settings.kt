package com.ping.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User-visible configuration. Deliberately stored in plain preferences: nothing in here is secret
 * (the identities, messages and session keys live in the encrypted database).
 */
data class SettingsSnapshot(
    val onboardingComplete: Boolean = false,
    val displayName: String = "",
    val relayUrl: String = "",
    val allowInsecureRelay: Boolean = false,
    val keepRelayAwake: Boolean = true,
    val relayLastSeen: Long = 0L,
    val localNetworkEnabled: Boolean = true,
    val screenshotGuard: Boolean = false,
    val appLock: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val notificationPreview: Boolean = true,
    val readReceipts: Boolean = true,
    val typingIndicators: Boolean = true,
    val defaultDisappearingSeconds: Long = 0L,
    val darkTheme: Boolean = true,
    val lastPreKeyPublish: Long = 0L,
    val signedPreKeyId: Int = 0,
    val lastBundleFetchFailure: String = ""
)

class Settings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("ping_settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<SettingsSnapshot> = _state.asStateFlow()

    val current: SettingsSnapshot get() = _state.value

    private fun read(): SettingsSnapshot = SettingsSnapshot(
        onboardingComplete = prefs.getBoolean("onboarding", false),
        displayName = prefs.getString("display_name", "") ?: "",
        // One-time adoption of DEFAULT_RELAY for installs that predate it: an empty stored address
        // means "no relay was configured yet", not "the user refused the built-in one", because
        // there was nothing to refuse. Once the address has been set (or deliberately cleared) from
        // the UI, the flag makes the stored value win for good.
        relayUrl = when {
            !prefs.getBoolean("relay_url_explicit", false) -> DEFAULT_RELAY
            else -> prefs.getString("relay_url", DEFAULT_RELAY) ?: DEFAULT_RELAY
        },
        allowInsecureRelay = prefs.getBoolean("allow_insecure_relay", false),
        keepRelayAwake = prefs.getBoolean("keep_relay_awake", true),
        relayLastSeen = prefs.getLong("relay_last_seen", 0L),
        localNetworkEnabled = prefs.getBoolean("lan", true),
        screenshotGuard = prefs.getBoolean("screenshot_guard", false),
        appLock = prefs.getBoolean("app_lock", false),
        notificationsEnabled = prefs.getBoolean("notifications", true),
        notificationPreview = prefs.getBoolean("notification_preview", true),
        readReceipts = prefs.getBoolean("read_receipts", true),
        typingIndicators = prefs.getBoolean("typing", true),
        defaultDisappearingSeconds = prefs.getLong("disappearing", 0L),
        darkTheme = prefs.getBoolean("dark_theme", true),
        lastPreKeyPublish = prefs.getLong("last_prekey_publish", 0L),
        signedPreKeyId = prefs.getInt("signed_prekey_id", 0),
        lastBundleFetchFailure = prefs.getString("bundle_failure", "") ?: ""
    )

    private fun update(transform: (SettingsSnapshot) -> SettingsSnapshot) {
        val next = transform(_state.value)
        persist(next)
        _state.value = next
    }

    private fun persist(snapshot: SettingsSnapshot) {
        prefs.edit()
            .putBoolean("onboarding", snapshot.onboardingComplete)
            .putString("display_name", snapshot.displayName)
            .putString("relay_url", snapshot.relayUrl)
            .putBoolean("allow_insecure_relay", snapshot.allowInsecureRelay)
            .putBoolean("keep_relay_awake", snapshot.keepRelayAwake)
            .putLong("relay_last_seen", snapshot.relayLastSeen)
            .putBoolean("lan", snapshot.localNetworkEnabled)
            .putBoolean("screenshot_guard", snapshot.screenshotGuard)
            .putBoolean("app_lock", snapshot.appLock)
            .putBoolean("notifications", snapshot.notificationsEnabled)
            .putBoolean("notification_preview", snapshot.notificationPreview)
            .putBoolean("read_receipts", snapshot.readReceipts)
            .putBoolean("typing", snapshot.typingIndicators)
            .putLong("disappearing", snapshot.defaultDisappearingSeconds)
            .putBoolean("dark_theme", snapshot.darkTheme)
            .putLong("last_prekey_publish", snapshot.lastPreKeyPublish)
            .putInt("signed_prekey_id", snapshot.signedPreKeyId)
            .putString("bundle_failure", snapshot.lastBundleFetchFailure)
            .apply()
    }

    fun setOnboardingComplete(value: Boolean) = update { it.copy(onboardingComplete = value) }
    fun setDisplayName(value: String) = update { it.copy(displayName = value.take(64)) }
    fun setRelayUrl(value: String) = update {
        // From here on the stored address is the user's decision, including an empty one.
        prefs.edit().putBoolean("relay_url_explicit", true).apply()
        it.copy(relayUrl = value.trim().trimEnd('/'))
    }
    fun setAllowInsecureRelay(value: Boolean) = update { it.copy(allowInsecureRelay = value) }
    fun setKeepRelayAwake(value: Boolean) = update { it.copy(keepRelayAwake = value) }
    fun setRelayLastSeen(time: Long) = update { it.copy(relayLastSeen = time) }
    fun setLocalNetworkEnabled(value: Boolean) = update { it.copy(localNetworkEnabled = value) }
    fun setScreenshotGuard(value: Boolean) = update { it.copy(screenshotGuard = value) }
    fun setAppLock(value: Boolean) = update { it.copy(appLock = value) }
    fun setNotificationsEnabled(value: Boolean) = update { it.copy(notificationsEnabled = value) }
    fun setNotificationPreview(value: Boolean) = update { it.copy(notificationPreview = value) }
    fun setReadReceipts(value: Boolean) = update { it.copy(readReceipts = value) }
    fun setTypingIndicators(value: Boolean) = update { it.copy(typingIndicators = value) }
    fun setDefaultDisappearing(value: Long) = update { it.copy(defaultDisappearingSeconds = value) }
    fun setDarkTheme(value: Boolean) = update { it.copy(darkTheme = value) }
    fun setPreKeyPublication(time: Long, signedPreKeyId: Int) =
        update { it.copy(lastPreKeyPublish = time, signedPreKeyId = signedPreKeyId) }
    fun setBundleFailure(message: String) = update { it.copy(lastBundleFetchFailure = message.take(200)) }

    /** Used by the panic wipe. */
    fun reset() {
        prefs.edit().clear().apply()
        _state.value = SettingsSnapshot()
    }

    companion object {
        /**
         * The relay the app points at out of the box.
         *
         * It is the project's own free Render instance (`render.yaml` deploys the same relay to your
         * own account in one click, and the address can be changed at any time in Settings — the
         * mailbox only ever holds sealed envelopes, so the relay is a convenience, not a trust
         * anchor). A relay you run yourself is never worse: the message encryption does not depend
         * on which relay carried it, which is why switching is safe.
         */
        const val DEFAULT_RELAY = "https://ping-relay-tvj1.onrender.com"

        val DISAPPEARING_OPTIONS = listOf(
            0L to "Off",
            3600L to "1 hour",
            8 * 3600L to "8 hours",
            24 * 3600L to "24 hours",
            7 * 24 * 3600L to "7 days",
            28 * 24 * 3600L to "4 weeks"
        )
    }
}
