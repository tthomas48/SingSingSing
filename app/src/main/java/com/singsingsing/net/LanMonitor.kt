package com.singsingsing.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

class LanMonitor(context: Context) {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(LanAddressPicker.snapshot(collectInterfaces()))
    val state: StateFlow<PartyLanState> = _state.asStateFlow()

    private val listenCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()
        override fun onLost(network: Network) = refresh()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = refresh()
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = refresh()
    }

    private val wifiKeepAliveCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()
        override fun onLost(network: Network) = refresh()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = refresh()
    }

    private var started = false

    fun start() {
        if (started) return
        started = true
        val listenRequest = NetworkRequest.Builder().build()
        val wifiRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        connectivity.registerNetworkCallback(listenRequest, listenCallback, handler)
        try {
            connectivity.requestNetwork(wifiRequest, wifiKeepAliveCallback, handler)
        } catch (error: SecurityException) {
            Log.w(TAG, "Could not request WiFi keep-alive", error)
            connectivity.registerNetworkCallback(wifiRequest, wifiKeepAliveCallback, handler)
        }
        refresh()
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { connectivity.unregisterNetworkCallback(listenCallback) }
        runCatching { connectivity.unregisterNetworkCallback(wifiKeepAliveCallback) }
    }

    fun refresh() {
        _state.value = LanAddressPicker.snapshot(collectInterfaces())
    }

    private fun collectInterfaces(): List<LanInterface> {
        val byName = linkedMapOf<String, LanInterface>()
        for (network in connectivityNetworks()) {
            val caps = connectivity.getNetworkCapabilities(network) ?: continue
            val links = connectivity.getLinkProperties(network) ?: continue
            val name = links.interfaceName ?: continue
            val ipv4s = links.linkAddresses.mapNotNull { address ->
                val inet = address.address
                if (inet is Inet4Address) inet.hostAddress else null
            }
            val transport = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> LanTransport.WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> LanTransport.ETHERNET
                else -> LanTransport.OTHER
            }
            val existing = byName[name]
            byName[name] = LanInterface(
                name = name,
                ipv4s = (existing?.ipv4s.orEmpty() + ipv4s).distinct(),
                transport = transport,
            )
        }
        for (network in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) {
            if (!network.isUp || network.isLoopback) continue
            val ipv4s = network.inetAddresses.toList()
                .filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress }
            val existing = byName[network.name]
            if (existing == null) {
                byName[network.name] = LanInterface(network.name, ipv4s)
            } else if (existing.ipv4s.isEmpty() && ipv4s.isNotEmpty()) {
                byName[network.name] = existing.copy(ipv4s = ipv4s)
            }
        }
        return byName.values.toList()
    }

    @Suppress("DEPRECATION")
    private fun connectivityNetworks(): Array<Network> = connectivity.allNetworks

    companion object {
        private const val TAG = "LanMonitor"
    }
}
