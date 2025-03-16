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

class AndroidConnectivityCheck(
    private val context: Context
): ConnectivityCheck {

    private val connectivityManager = context.getSystemService<ConnectivityManager>()

    private fun checkInitialConnection(): Boolean {
        val network = connectivityManager?.activeNetwork
        val capabilities = connectivityManager?.getNetworkCapabilities(network)
        return capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
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
                    trySend(connected)
                }
            }

            connectivityManager?.registerDefaultNetworkCallback(callBack)

            awaitClose {
                connectivityManager?.unregisterNetworkCallback(callBack)
            }
        }
}