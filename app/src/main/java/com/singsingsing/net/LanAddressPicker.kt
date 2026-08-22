package com.singsingsing.net

import java.net.Inet4Address
import java.net.NetworkInterface

data class LanInterface(
    val name: String,
    val ipv4s: List<String>,
    val transport: LanTransport? = null,
) {
    val resolvedTransport: LanTransport
        get() = transport ?: LanAddressPicker.inferTransport(name)
}

enum class LanTransport {
    WIFI,
    ETHERNET,
    OTHER,
}

data class PartyLanState(
    val advertisedHost: String = FALLBACK_HOST,
    val wifiHost: String? = null,
    val ethernetHost: String? = null,
    val wifiAvailable: Boolean = false,
) {
    fun joinUrl(port: Int): String = "http://$advertisedHost:$port/"

    fun oauthCallbackUrl(port: Int): String = "http://$advertisedHost:$port/oauth/callback"
}

object LanAddressPicker {
    fun pick(interfaces: List<LanInterface>): String =
        snapshot(interfaces).advertisedHost

    fun snapshotFromNetworkInterfaces(): PartyLanState {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .map { network ->
                LanInterface(
                    name = network.name,
                    ipv4s = network.inetAddresses.toList()
                        .filterIsInstance<Inet4Address>()
                        .mapNotNull { it.hostAddress },
                )
            }
        return snapshot(interfaces)
    }

    fun snapshot(interfaces: List<LanInterface>): PartyLanState {
        val wifiHost = firstUsable(interfaces, LanTransport.WIFI)
        val ethernetHost = firstUsable(interfaces, LanTransport.ETHERNET)
        val otherHost = firstUsable(interfaces, LanTransport.OTHER)
        val advertisedHost = wifiHost ?: ethernetHost ?: otherHost ?: FALLBACK_HOST
        return PartyLanState(
            advertisedHost = advertisedHost,
            wifiHost = wifiHost,
            ethernetHost = ethernetHost,
            wifiAvailable = wifiHost != null,
        )
    }

    fun inferTransport(name: String): LanTransport {
        val n = name.lowercase()
        return when {
            n.startsWith("wlan") || n.startsWith("wifi") -> LanTransport.WIFI
            n.startsWith("eth") || n.startsWith("lan") || n.startsWith("en") -> LanTransport.ETHERNET
            else -> LanTransport.OTHER
        }
    }

    fun isUsableIpv4(host: String): Boolean {
        if (host.isBlank() || host.contains(':')) return false
        val parts = host.split('.')
        if (parts.size != 4) return false
        if (parts.any { it.toIntOrNull() == null }) return false
        if (parts[0] == "127") return false
        if (parts[0] == "169" && parts[1] == "254") return false
        return true
    }

    private fun firstUsable(interfaces: List<LanInterface>, transport: LanTransport): String? =
        interfaces
            .filter { it.resolvedTransport == transport }
            .flatMap { it.ipv4s }
            .firstOrNull(::isUsableIpv4)
}

const val FALLBACK_HOST = "127.0.0.1"
