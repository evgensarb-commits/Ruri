package com.ruri

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import java.util.concurrent.CopyOnWriteArrayList

/** Process-wide view of the active default network and its VPN transport. */
object NetState {
    @Volatile var online: Boolean = false
        private set
    @Volatile var vpn: Boolean = false
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    @Volatile private var connectivity: ConnectivityManager? = null
    @Volatile private var callback: ConnectivityManager.NetworkCallback? = null

    fun addListener(listener: () -> Unit) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun fire() {
        listeners.forEach { listener -> runCatching { listener.invoke() } }
    }

    fun refresh(context: Context) {
        val manager = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = manager.activeNetwork
        val capabilities = if (active != null) manager.getNetworkCapabilities(active) else null
        update(
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        )
    }

    private fun update(isOnline: Boolean, isVpn: Boolean) {
        val changed = online != isOnline || vpn != isVpn
        online = isOnline
        vpn = isVpn
        if (changed) fire()
    }

    /**
     * How many owners currently need the callback.
     *
     * The callback is process-wide, so a plain unregister from the activity tore it down while
     * the foreground service still relied on it. After swiping the task away no network change
     * fired any more, NetState.vpn stayed stale, and a transient network failure was then
     * misread as terminal "no VPN" — which silently stopped the ten-second retry.
     */
    private var owners = 0

    @Synchronized
    fun register(context: Context) {
        owners++
        if (callback != null) {
            refresh(context)
            return
        }
        val manager = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivity = manager
        refresh(context)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                refresh(context.applicationContext)
            }

            override fun onLost(network: Network) {
                refresh(context.applicationContext)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                refresh(context.applicationContext)
            }
        }
        try {
            manager.registerDefaultNetworkCallback(networkCallback)
            callback = networkCallback
        } catch (e: Exception) {
            android.util.Log.w("Ruri", "Network callback registration failed", e)
        }
    }

    @Synchronized
    fun unregister() {
        // Only the last owner may tear the callback down; others still depend on it.
        owners = (owners - 1).coerceAtLeast(0)
        if (owners > 0) return
        val manager = connectivity
        val registered = callback
        if (manager != null && registered != null) {
            runCatching { manager.unregisterNetworkCallback(registered) }
        }
        callback = null
        connectivity = null
    }
}
