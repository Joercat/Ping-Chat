package com.ping.app.engine

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.ping.app.core.crypto.Bytes
import com.ping.app.core.protocol.Wire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Direct device-to-device delivery on the local network.
 *
 * When two phones are on the same Wi-Fi they can talk straight to each other: no relay, no
 * internet connection required at all. The frames carry exactly the same end-to-end encrypted
 * envelopes, so roaming between "direct" and "via relay" changes nothing about the crypto.
 */
class LanTransport(
    private val context: Context,
    private val identityProvider: () -> com.ping.app.core.crypto.IdentityKeyPair?,
    private val onEnvelope: suspend (Wire.Envelope) -> Unit,
    /** Hands out this device's current pre-key bundle to a peer on the same network. */
    private val onBundleRequested: suspend () -> ByteArray?
) {

    companion object {
        private const val TAG = "PingLan"
        private const val SERVICE_TYPE = "_ping2._tcp."
        private const val SERVICE_PREFIX = "ping-"
        private const val FRAME_ENVELOPE = 1
        private const val FRAME_BUNDLE_REQUEST = 2
        private const val FRAME_BUNDLE_RESPONSE = 3
        private const val FRAME_ACK = 4
        private const val MAX_FRAME = Wire.MAX_ENVELOPE_BYTES + 4096
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var serverSocket: ServerSocket? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var resolveInFlight = false

    private val nsdManager: NsdManager? =
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private val _peers = MutableStateFlow<Map<String, DiscoveredPeer>>(emptyMap())
    val peers: StateFlow<Map<String, DiscoveredPeer>> = _peers.asStateFlow()

    data class DiscoveredPeer(
        val mailboxId: String,
        val dhKey: String,
        val host: String,
        val port: Int,
        val serviceName: String
    )

    fun start(localMailbox: ByteArray) {
        startServer(localMailbox)
        startDiscovery()
    }

    private fun startServer(localMailbox: ByteArray) {
        try {
            val server = ServerSocket(0)
            serverSocket = server
            registerService(server.localPort, localMailbox)
            scope.launch {
                while (serverSocket?.isClosed == false) {
                    val socket = try {
                        server.accept()
                    } catch (e: Exception) {
                        break
                    }
                    scope.launch { handleConnection(socket) }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not start the local socket: ${e.message}")
        }
    }

    private fun registerService(port: Int, localMailbox: ByteArray) {
        val manager = nsdManager ?: return
        val identity = identityProvider() ?: return
        val info = NsdServiceInfo().apply {
            serviceName = SERVICE_PREFIX + Bytes.toBase64Url(identity.dh.publicKey).take(12)
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("v", "1")
            setAttribute("m", Bytes.toBase64Url(localMailbox))
            setAttribute("k", Bytes.toBase64Url(identity.dh.publicKey))
            setAttribute("n", "Ping")
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Announced ${serviceInfo.serviceName}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Announcement failed: $errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        registrationListener = listener
        try {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "Discovery announcement not possible: ${e.message}")
        }
    }

    private fun startDiscovery() {
        val manager = nsdManager ?: return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = stopDiscovery()
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceLost(service: NsdServiceInfo) {
                _peers.value = _peers.value.filterValues { it.serviceName != service.serviceName }
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType.contains("_ping2")) resolve(service)
            }
        }
        discoveryListener = listener
        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "Discovery not possible: ${e.message}")
        }
    }

    /** Android only allows one resolve at a time; failures are ignored on purpose. */
    private fun resolve(service: NsdServiceInfo) {
        val manager = nsdManager ?: return
        if (resolveInFlight) return
        resolveInFlight = true
        try {
            manager.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    resolveInFlight = false
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    resolveInFlight = false
                    val attributes = serviceInfo.attributes ?: return
                    val mailbox = attributes["m"]?.toString(Charsets.UTF_8) ?: return
                    val key = attributes["k"]?.toString(Charsets.UTF_8) ?: return
                    val host = serviceInfo.host?.hostAddress ?: return
                    if (serviceInfo.port <= 0) return
                    _peers.value = _peers.value + (mailbox to DiscoveredPeer(
                        mailboxId = mailbox,
                        dhKey = key,
                        host = host,
                        port = serviceInfo.port,
                        serviceName = serviceInfo.serviceName
                    ))
                }
            })
        } catch (e: Exception) {
            resolveInFlight = false
            Log.d(TAG, "Resolve failed: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ receiving

    private suspend fun handleConnection(socket: Socket) {
        try {
            socket.soTimeout = 20_000
            val input = DataInputStream(socket.getInputStream().buffered())
            val output = DataOutputStream(socket.getOutputStream().buffered())
            while (true) {
                val type = try {
                    input.readUnsignedByte()
                } catch (e: Exception) {
                    break
                }
                val length = input.readInt()
                if (length < 0 || length > MAX_FRAME) break
                val payload = ByteArray(length)
                input.readFully(payload)
                when (type) {
                    FRAME_ENVELOPE -> {
                        val envelope = try {
                            Wire.Envelope.decode(payload)
                        } catch (e: Exception) {
                            writeFrame(output, FRAME_ACK, byteArrayOf(0))
                            continue
                        }
                        onEnvelope(envelope)
                        writeFrame(output, FRAME_ACK, byteArrayOf(1))
                    }
                    FRAME_BUNDLE_REQUEST -> {
                        val bundle = onBundleRequested()
                        if (bundle != null) writeFrame(output, FRAME_BUNDLE_RESPONSE, bundle)
                        else writeFrame(output, FRAME_ACK, byteArrayOf(0))
                    }
                    else -> break
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Connection ended: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
                // already closed
            }
        }
    }

    private fun writeFrame(output: DataOutputStream, type: Int, payload: ByteArray) {
        output.writeByte(type)
        output.writeInt(payload.size)
        output.write(payload)
        output.flush()
    }

    // ------------------------------------------------------------------ sending

    /** Delivers an envelope straight to a peer on the local network. */
    fun deliver(peer: DiscoveredPeer, envelope: Wire.Envelope): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(peer.host, peer.port), 4000)
                socket.soTimeout = 8000
                val output = DataOutputStream(socket.getOutputStream().buffered())
                val input = DataInputStream(socket.getInputStream().buffered())
                writeFrame(output, FRAME_ENVELOPE, envelope.encode())
                val type = input.readUnsignedByte()
                val length = input.readInt()
                if (length < 0 || length > MAX_FRAME) return false
                val payload = ByteArray(length)
                input.readFully(payload)
                type == FRAME_ACK && payload.isNotEmpty() && payload[0].toInt() == 1
            }
        } catch (e: Exception) {
            Log.d(TAG, "Direct delivery failed: ${e.message}")
            false
        }
    }

    /** Fetches a peer's pre-key bundle over the local network (no relay involved). */
    fun fetchBundle(peer: DiscoveredPeer): ByteArray? {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(peer.host, peer.port), 4000)
                socket.soTimeout = 8000
                val output = DataOutputStream(socket.getOutputStream().buffered())
                val input = DataInputStream(socket.getInputStream().buffered())
                writeFrame(output, FRAME_BUNDLE_REQUEST, ByteArray(0))
                val type = input.readUnsignedByte()
                val length = input.readInt()
                if (length < 0 || length > MAX_FRAME) return null
                val payload = ByteArray(length)
                input.readFully(payload)
                if (type == FRAME_BUNDLE_RESPONSE) payload else null
            }
        } catch (e: Exception) {
            Log.d(TAG, "Local bundle fetch failed: ${e.message}")
            null
        }
    }

    fun peerFor(mailbox: ByteArray): DiscoveredPeer? =
        _peers.value[Bytes.toBase64Url(mailbox)]

    fun peerForDhKey(dhKey: String): DiscoveredPeer? =
        _peers.value.values.firstOrNull { it.dhKey == dhKey }

    // ------------------------------------------------------------------ lifecycle

    private fun stopDiscovery() {
        discoveryListener?.let {
            try {
                nsdManager?.stopServiceDiscovery(it)
            } catch (e: Exception) {
                // already stopped
            }
        }
        discoveryListener = null
    }

    fun stop() {
        stopDiscovery()
        registrationListener?.let {
            try {
                nsdManager?.unregisterService(it)
            } catch (e: Exception) {
                // already unregistered
            }
        }
        registrationListener = null
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // already closed
        }
        serverSocket = null
        scope.cancel()
        _peers.value = emptyMap()
    }
}
