// core/network/ConnectionManager.kt
package com.ping.app.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import com.ping.app.core.encryption.SecureStorage
import com.ping.app.data.models.User
import com.ping.app.data.repository.UserRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureStorage: SecureStorage
) {
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val _networkAvailable = MutableStateFlow(false)
    val networkAvailable: StateFlow<Boolean> = _networkAvailable.asStateFlow()
    
    private val _currentIpAddress = MutableStateFlow<String?>(null)
    val currentIpAddress: StateFlow<String?> = _currentIpAddress.asStateFlow()
    
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _networkAvailable.value = true
            updateIpAddress()
        }
        
        override fun onLost(network: Network) {
            _networkAvailable.value = false
            _currentIpAddress.value = null
        }
        
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            updateIpAddress()
        }
    }
    
    init {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
        updateIpAddress()
    }
    
    fun getMyUserId(): String {
        var userId = secureStorage.getUserId()
        if (userId == null) {
            userId = UUID.randomUUID().toString()
            secureStorage.saveUserId(userId)
        }
        return userId
    }
    
    fun getDeviceId(): String {
        var deviceId = secureStorage.getDeviceId()
        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString()
            secureStorage.saveDeviceId(deviceId)
        }
        return deviceId
    }
    
    private fun updateIpAddress() {
        scope.launch {
            _currentIpAddress.value = getLocalIpAddress()
        }
    }
    
    fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
    
    fun isNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    
    fun cleanup() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            // Already unregistered
        }
    }
}
