package com.ping.app.di

import android.content.Context
import com.ping.app.core.crypto.PingCrypto
import com.ping.app.data.CryptoStore
import com.ping.app.data.Db
import com.ping.app.data.PingStore
import com.ping.app.data.Settings
import com.ping.app.data.Vault
import com.ping.app.engine.AttachmentStore
import com.ping.app.engine.Engine
import com.ping.app.engine.IdentityManager
import com.ping.app.engine.LanTransport
import com.ping.app.engine.RelayClient
import com.ping.app.engine.SessionManager
import com.ping.app.service.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How far the app has got with opening its encrypted storage. */
sealed class StorageState {
    object Loading : StorageState()
    object Ready : StorageState()
    data class Failed(val reason: String) : StorageState()
}

/**
 * Hand-rolled dependency graph.
 *
 * There is no DI framework on purpose: the object graph is tiny, it has to be built exactly once
 * per process, and a compiler plugin is one more thing that can break a release. Everything here
 * is created lazily and torn down in [close].
 */
class AppGraph(context: Context) {

    private val appContext = context.applicationContext

    /** Application context, for the few places that need a ContentResolver or a system service. */
    val context: Context get() = appContext

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * SQLCipher is not thread safe and there is no reason to hammer it: every database call runs
     * on this single thread, which also keeps writes ordered.
     */
    private val dbDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val _storage = MutableStateFlow<StorageState>(StorageState.Loading)
    val storage: StateFlow<StorageState> = _storage.asStateFlow()

    /** Bumped whenever something in the database changed, so screens know to re-read. */
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private val initLock = Mutex()

    val settings: Settings by lazy { Settings(appContext) }
    val vault: Vault by lazy { Vault(appContext) }
    val db: Db by lazy { Db(appContext, vault.databasePassphrase()) }
    val store: PingStore by lazy { PingStore(db, vault) }
    val cryptoStore: CryptoStore by lazy { CryptoStore(db, vault) }
    val attachments: AttachmentStore by lazy { AttachmentStore(appContext) }
    val identity: IdentityManager by lazy { IdentityManager(store, cryptoStore, settings) }
    val relay: RelayClient by lazy { RelayClient(settings) }
    val sessions: SessionManager by lazy { SessionManager(cryptoStore) }
    val notifications: Notifications by lazy { Notifications(appContext, settings) }
    val engine: Engine by lazy { Engine(this) }

    val lan: LanTransport by lazy {
        LanTransport(
            context = appContext,
            identityProvider = { identity.keyPair() },
            onEnvelope = { envelope -> engine.handleEnvelope(envelope) },
            onBundleRequested = { engine.localBundleBytes() }
        )
    }

    // ---------------------------------------------------------------- small shared UI state

    /** Conversation the user is looking at right now; it gets no notification. */
    val activeConversation = MutableStateFlow<String?>(null)

    /** Conversation the user asked to open from a notification. */
    val pendingConversation = MutableStateFlow<String?>(null)

    /** Peer hash -> timestamp of the last typing event. */
    val typing = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** Set when somebody starts talking to us and we learn their name. */
    val toastMessages = MutableStateFlow<List<String>>(emptyList())

    fun note(message: String) {
        toastMessages.value = toastMessages.value + message
    }

    fun consumeNotes(): List<String> {
        val current = toastMessages.value
        toastMessages.value = emptyList()
        return current
    }

    init {
        PingCrypto.install()
        // Opening the database touches the Keystore and the native library, so it happens off the
        // main thread and the UI simply waits for [storage] to leave [StorageState.Loading].
        scope.launch {
            try {
                db
                _storage.value = StorageState.Ready
                // Drop leftovers from previous runs and publish fresh pre-keys when needed.
                engine.onStorageReady()
            } catch (t: Throwable) {
                _storage.value = StorageState.Failed(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    suspend fun awaitStorage(): Boolean {
        storage.filter { it !is StorageState.Loading }.first()
        return storage.value is StorageState.Ready
    }

    /** Runs [block] on the database thread. */
    suspend fun <T> onDb(block: () -> T): T = withContext(dbDispatcher) { block() }

    suspend fun <T> onDbLocked(block: () -> T): T = initLock.withLock { withContext(dbDispatcher) { block() } }

    fun bumpRevision() {
        _revision.value = _revision.value + 1
    }

    /** A read-only view of the database that re-emits after every change. */
    fun <T> live(initial: T? = null, query: suspend () -> T): StateFlow<T?> =
        _revision.map { query() }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), initial)

    fun close() {
        engine.stop()
        try {
            lan.stop()
        } catch (t: Throwable) {
            // never started
        }
        try {
            db.close()
        } catch (t: Throwable) {
            // nothing to close
        }
    }
}
