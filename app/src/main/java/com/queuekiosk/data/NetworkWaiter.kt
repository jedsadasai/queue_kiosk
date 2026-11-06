package com.queuekiosk.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Waits until the device has a network. Right after power-on the app can start before Wi-Fi
 * has connected; a login attempt then fails at once and the next one would only come after a
 * retry delay. Waiting for the network instead lets the first attempt go out the moment Wi-Fi is up.
 *
 * Any connected network counts: internet validation is not required, so a LAN-only server works.
 */
class NetworkWaiter(context: Context) {

    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    fun isConnected(): Boolean = connectivity.activeNetwork != null

    /** True as soon as a network is available, false if none appeared within [timeoutMs]. */
    suspend fun awaitNetwork(timeoutMs: Long): Boolean {
        if (isConnected()) return true
        val available = CompletableDeferred<Unit>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                available.complete(Unit)
            }
        }
        connectivity.registerDefaultNetworkCallback(callback)
        return try {
            withTimeoutOrNull(timeoutMs) { available.await() } != null
        } finally {
            runCatching { connectivity.unregisterNetworkCallback(callback) }
        }
    }
}
