package com.example.pixelpayout.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class AndroidConnectivityCheck(
    private val context: Context
): ConnectivityCheck {

    private val connectivityManager = context.getSystemService<ConnectivityManager>()
    
    private suspend fun isInternetReachable(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            withTimeoutOrNull(3000) { // 3 second timeout
                val connection = URL("https://8.8.8.8").openConnection() as HttpURLConnection
                connection.setRequestProperty("User-Agent", "Android")
                connection.setRequestProperty("Connection", "close")
                connection.connectTimeout = 1500
                connection.connect()
                connection.responseCode == 200
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun checkInitialConnection(): Boolean {
        val network = connectivityManager?.activeNetwork
        val capabilities = connectivityManager?.getNetworkCapabilities(network)
        val hasValidatedCapabilities = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        
        // Check for slow connection
        val isHighPerformance = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true ||
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        
        // If on mobile data or slow connection, do additional check
        return if (!isHighPerformance && hasValidatedCapabilities) {
            isInternetReachable()
        } else {
            hasValidatedCapabilities
        }
    }

    override val isConnected: Flow<Boolean>
        get() = callbackFlow {
            // Send initial connection status immediately
            trySend(checkInitialConnection())

            val callBack = object: NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    // Don't immediately send true here, wait for capabilities check
                }

                override fun onLost(network: Network) {
                    super.onLost(network)
                    trySend(false)
                }

                override fun onUnavailable() {
                    super.onUnavailable()
                    trySend(false)
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    super.onCapabilitiesChanged(network, networkCapabilities)
                    val connected = networkCapabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_VALIDATED
                    )
                    
                    // For slow connections, do additional check
                    val isHighPerformance = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
                            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                            
                    if (!isHighPerformance && connected) {
                        // Launch a coroutine to check internet reachability
                        GlobalScope.launch(Dispatchers.IO) {
                            val isReachable = isInternetReachable()
                            trySend(isReachable)
                        }
                    } else {
                        trySend(connected)
                    }
                }
            }

            connectivityManager?.registerDefaultNetworkCallback(callBack)

            awaitClose {
                connectivityManager?.unregisterNetworkCallback(callBack)
            }
        }
}