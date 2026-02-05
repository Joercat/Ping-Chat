// core/network/PeerDiscovery.kt
package com.ping.app.core.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PeerDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connectionManager: ConnectionManager
) {
    
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    
    private val _discoveredPeers = MutableSharedFlow<DiscoveredPeer>()
    val discoveredPeers: SharedFlow<DiscoveredPeer> = _discoveredPeers.asSharedFlow()
    
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    
    companion object {
        private const val SERVICE_TYPE = "_ping._tcp."
        private const val SERVICE_NAME = "PingMessenger"
    }
    
    fun registerService(port: Int, userId: String) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "${SERVICE_NAME}_$userId"
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("userId", userId)
            setAttribute("publicKey", "") // Will be exchanged during connection
        }
        
        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                // Service registered successfully
            }
            
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                // Registration failed
            }
            
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                // Service unregistered
            }
            
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                // Unregistration failed
            }
        }
        
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }
    
    fun startDiscovery() {
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                // Discovery started
            }
            
            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType == SERVICE_TYPE) {
                    resolveService(service)
                }
            }
            
            override fun onServiceLost(service: NsdServiceInfo) {
                // Service lost
            }
            
            override fun onDiscoveryStopped(serviceType: String) {
                // Discovery stopped
            }
            
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                // Start discovery failed
            }
            
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                // Stop discovery failed
            }
        }
        
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }
    
    private fun resolveService(service: NsdServiceInfo) {
        nsdManager.resolveService(service, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // Resolve failed
            }
            
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val userId = serviceInfo.attributes["userId"]?.let { String(it) } ?: return
                val peer = DiscoveredPeer(
                    userId = userId,
                    serviceName = serviceInfo.serviceName,
                    host = serviceInfo.host.hostAddress ?: return,
                    port = serviceInfo.port
                )
                
                kotlinx.coroutines.runBlocking {
                    _discoveredPeers.emit(peer)
                }
            }
        })
    }
    
    fun stopDiscovery() {
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (e: Exception) {
                // Already stopped
            }
        }
    }
    
    fun unregisterService() {
        registrationListener?.let {
            try {
                nsdManager.unregisterService(it)
            } catch (e: Exception) {
                // Already unregistered
            }
        }
    }
}

data class DiscoveredPeer(
    val userId: String,
    val serviceName: String,
    val host: String,
    val port: Int
)
