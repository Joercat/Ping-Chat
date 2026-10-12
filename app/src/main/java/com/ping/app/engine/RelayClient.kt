package com.ping.app.engine

import com.ping.app.core.crypto.Bytes
import com.ping.app.core.crypto.IdentityKeyPair
import com.ping.app.core.crypto.PreKeyBundle
import com.ping.app.core.protocol.RelayApi
import com.ping.app.core.protocol.Wire
import com.ping.app.data.Settings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.TimeUnit

/** Result of one poll round: what arrived, plus the nonce for the next authenticated call. */
class PollResult(
    val envelopes: List<RelayApi.PolledEnvelope>,
    val nextNonce: ByteArray?
)

/** Failures are classified so the engine knows whether retrying can ever help. */
class RelayException(
    message: String,
    val code: String,
    val status: Int = 0,
    val permanent: Boolean = false
) : Exception(message)

/**
 * HTTP client for the relay.
 *
 * Only HTTPS is accepted, unless the user explicitly points the app at a private/local address and
 * ticks "allow plain HTTP" — useful when self-hosting on a home network.
 */
class RelayClient(private val settings: Settings) {

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(RelayApi.MAX_POLL_WAIT_MS + 15_000, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Same connections, but patient enough for a host that puts an idle instance to sleep.
     *
     * A free Render web service spins down after 15 minutes without traffic and takes up to a
     * minute to come back; the normal timeouts (15 s connect / 45 s read) are far too short for that
     * first request, so waking a sleeping relay goes through this client instead.
     */
    private val wakeClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(WAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** When this client last had a successful answer from the relay, in `System.currentTimeMillis`. */
    @Volatile
    var lastContactAt: Long = 0L
        private set

    companion object {
        /** How long a request may wait for a sleeping relay to boot. Render allows ~1 minute. */
        const val WAKE_TIMEOUT_MS = 90_000L
        /**
         * Traffic is sent at least this often while Ping runs, so an idle free-tier host never
         * reaches its own spin-down timer (Render: 15 minutes without a request).
         */
        const val KEEP_ALIVE_INTERVAL_MS = 10 * 60 * 1000L

        /**
         * Validates a relay address. Returns null when it is acceptable, otherwise a reason.
         *
         * Plain HTTP is only allowed for loopback and private ranges, and even then only when the
         * user opted in: messages stay end-to-end encrypted either way, but plain HTTP leaks
         * metadata (who talks to whom, when) to anybody on the path.
         */
        fun validateRelayUrl(raw: String, allowInsecure: Boolean): String? {
            val value = raw.trim()
            if (value.isEmpty()) return "Enter the address of your relay (for example https://relay.example.com)"
            val uri = try {
                URI(value)
            } catch (e: Exception) {
                return "That is not a valid URL"
            }
            val scheme = uri.scheme?.lowercase() ?: return "The address is missing http:// or https://"
            if (scheme != "https" && scheme != "http") return "Only http and https relays are supported"
            if (uri.host.isNullOrBlank()) return "The address has no host name"
            if (scheme == "http") {
                if (!isPrivateHost(uri.host) && !allowInsecure) {
                    return "Use https:// for a relay on the internet, or enable plain HTTP for local addresses"
                }
            }
            return null
        }

        internal fun isPrivateHost(host: String): Boolean {
            val lower = host.lowercase()
            if (lower == "localhost" || lower.endsWith(".local")) return true
            val address = try {
                InetAddress.getByName(lower)
            } catch (e: Exception) {
                return false
            }
            return address.isLoopbackAddress || address.isSiteLocalAddress ||
                address.isLinkLocalAddress || address.hostAddress?.startsWith("fc") == true ||
                address.hostAddress?.startsWith("fd") == true
        }
    }

    private fun base(): String? {
        val snapshot = settings.current
        val url = snapshot.relayUrl.trim().trimEnd('/')
        if (url.isEmpty()) return null
        if (validateRelayUrl(url, snapshot.allowInsecureRelay) != null) return null
        return url
    }

    fun isConfigured(): Boolean = base() != null

    private fun execute(
        method: String,
        path: String,
        body: String? = null,
        http: OkHttpClient = client
    ): String {
        val url = base() ?: throw RelayException("No relay configured", "no_relay", permanent = false)
        val builder = Request.Builder().url(url + path).header("accept", "application/json")
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post((body ?: "{}").toRequestBody(jsonType))
            else -> throw RelayException("Unsupported method", "bad_method", permanent = true)
        }
        try {
            http.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    lastContactAt = System.currentTimeMillis()
                    return text
                }
                val code = RelayApi.errorCode(text) ?: "http_${response.code}"
                val permanent = response.code == 400 || response.code == 404 ||
                    (response.code == 401 && code == "bad_signature") || response.code == 413
                throw RelayException("Relay error $code (HTTP ${response.code})", code, response.code, permanent)
            }
        } catch (e: RelayException) {
            throw e
        } catch (e: IOException) {
            throw RelayException(e.message ?: "network error", "network", permanent = false)
        }
    }

    /**
     * Asks the relay how it is. `wake = true` waits out a cold start instead of giving up, which is
     * what a free host needs: it doubles as the keep-alive that stops the instance going to sleep.
     */
    fun health(wake: Boolean = false): String = execute("GET", "/v1/health", null,
        if (wake) wakeClient else client)

    /** True when the relay has not answered for longer than the keep-alive interval. */
    fun isStale(now: Long = System.currentTimeMillis()): Boolean =
        now - lastContactAt > KEEP_ALIVE_INTERVAL_MS

    /** Publishes the identity bundle and any un-published one-time pre-keys. */
    fun register(
        signingKey: ByteArray,
        dhIdentityKey: ByteArray,
        dhBinding: ByteArray,
        signedPreKeyId: Int,
        signedPreKey: ByteArray,
        signedPreKeySignature: ByteArray,
        oneTimePreKeys: List<Pair<Int, ByteArray>>
    ): Int {
        val body = RelayApi.registerRequest(
            signingKey, dhIdentityKey, dhBinding, signedPreKeyId, signedPreKey,
            signedPreKeySignature, System.currentTimeMillis(), oneTimePreKeys
        )
        val response = execute("POST", "/v1/register", body)
        return com.ping.app.core.protocol.JsonObject(response).int("preKeys")
    }

    /** Fetches somebody's bundle. Returns null when the relay does not know them (yet). */
    fun fetchBundle(mailbox: ByteArray): PreKeyBundle? {
        val text = try {
            execute("GET", RelayApi.bundlePath(mailbox))
        } catch (e: RelayException) {
            if (e.status == 404) return null
            throw e
        }
        return RelayApi.parseBundleResponse(text)
    }

    fun send(envelope: Wire.Envelope) {
        execute("POST", "/v1/send", RelayApi.sendRequest(envelope))
    }

    fun poll(identity: IdentityKeyPair, mailbox: ByteArray, waitMs: Long, nonce: ByteArray? = null): PollResult {
        val challenge = nonce ?: challenge(mailbox)
        val signature = identity.signing.sign(RelayApi.authMessage("poll", mailbox, challenge))
        val response = execute("POST", "/v1/poll", RelayApi.pollRequest(mailbox, challenge, signature, waitMs))
        return PollResult(RelayApi.parsePollResponse(response), RelayApi.parseNextNonce(response))
    }

    fun ack(mailbox: ByteArray, identity: IdentityKeyPair, ids: List<String>, nonce: ByteArray) {
        if (ids.isEmpty()) return
        val signature = identity.signing.sign(RelayApi.authMessage("ack", mailbox, nonce))
        execute("POST", "/v1/ack", RelayApi.ackRequest(mailbox, nonce, signature, ids))
    }

    fun refreshPreKeys(
        identity: IdentityKeyPair,
        mailbox: ByteArray,
        nonce: ByteArray,
        oneTimePreKeys: List<Pair<Int, ByteArray>>
    ): ByteArray? {
        val signature = identity.signing.sign(RelayApi.authMessage("keys", mailbox, nonce))
        val body = RelayApi.preKeysRequest(mailbox, nonce, signature, oneTimePreKeys)
        val response = execute("POST", "/v1/prekeys", body)
        return RelayApi.parseNextNonce(response)
    }

    fun challenge(mailbox: ByteArray): ByteArray {
        val text = execute("GET", RelayApi.challengePath(mailbox))
        return RelayApi.parseChallenge(text)
    }

    /**
     * The relay answers and speaks our protocol version. `wake = true` gives a sleeping free-tier
     * instance the time to boot instead of reporting it as unreachable.
     */
    fun probe(wake: Boolean = false): String {
        val text = health(wake)
        val json = com.ping.app.core.protocol.JsonObject(text)
        val version = json.int("version")
        if (version != Wire.VERSION) throw RelayException("Relay speaks version $version, this app speaks ${Wire.VERSION}", "version_mismatch", permanent = true)
        return text
    }
}
