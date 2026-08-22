package com.singsingsing.net

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LanAddressPickerTest {
    @Test
    fun prefersWifiOverEthernet() {
        val picked = LanAddressPicker.pick(
            listOf(
                LanInterface("eth0", listOf("192.168.86.10"), LanTransport.ETHERNET),
                LanInterface("wlan0", listOf("192.168.86.21"), LanTransport.WIFI),
            ),
        )
        assertThat(picked).isEqualTo("192.168.86.21")
    }

    @Test
    fun prefersWifiWhenTransportIsInferredFromName() {
        val picked = LanAddressPicker.pick(
            listOf(
                LanInterface("eth0", listOf("10.0.0.5")),
                LanInterface("wlan0", listOf("192.168.86.21")),
            ),
        )
        assertThat(picked).isEqualTo("192.168.86.21")
    }

    @Test
    fun skipsLinkLocalWifiAndFallsBackToEthernet() {
        val state = LanAddressPicker.snapshot(
            listOf(
                LanInterface("wlan0", listOf("169.254.12.4"), LanTransport.WIFI),
                LanInterface("eth0", listOf("192.168.86.10"), LanTransport.ETHERNET),
            ),
        )
        assertThat(state.wifiHost).isNull()
        assertThat(state.wifiAvailable).isFalse()
        assertThat(state.advertisedHost).isEqualTo("192.168.86.10")
        assertThat(state.ethernetHost).isEqualTo("192.168.86.10")
    }

    @Test
    fun usesEthernetWhenWifiIsAbsent() {
        val state = LanAddressPicker.snapshot(
            listOf(
                LanInterface("eth0", listOf("192.168.86.10"), LanTransport.ETHERNET),
                LanInterface("dummy0", listOf("192.0.0.2"), LanTransport.OTHER),
            ),
        )
        assertThat(state.advertisedHost).isEqualTo("192.168.86.10")
        assertThat(state.wifiAvailable).isFalse()
        assertThat(state.ethernetHost).isEqualTo("192.168.86.10")
    }

    @Test
    fun emptyInterfacesFallBackToLoopback() {
        val state = LanAddressPicker.snapshot(emptyList())
        assertThat(state.advertisedHost).isEqualTo("127.0.0.1")
        assertThat(state.wifiHost).isNull()
        assertThat(state.ethernetHost).isNull()
        assertThat(state.wifiAvailable).isFalse()
    }

    @Test
    fun skipsLoopbackAndLinkLocalEverywhere() {
        val picked = LanAddressPicker.pick(
            listOf(
                LanInterface("lo", listOf("127.0.0.1"), LanTransport.OTHER),
                LanInterface("wlan0", listOf("169.254.1.1", "192.168.86.21"), LanTransport.WIFI),
            ),
        )
        assertThat(picked).isEqualTo("192.168.86.21")
    }

    @Test
    fun infersEthernetFromEnPrefix() {
        assertThat(LanAddressPicker.inferTransport("en0")).isEqualTo(LanTransport.ETHERNET)
        assertThat(LanAddressPicker.inferTransport("eth0")).isEqualTo(LanTransport.ETHERNET)
        assertThat(LanAddressPicker.inferTransport("wlan0")).isEqualTo(LanTransport.WIFI)
        assertThat(LanAddressPicker.inferTransport("dummy0")).isEqualTo(LanTransport.OTHER)
    }

    @Test
    fun snapshotJoinUrlUsesAdvertisedHost() {
        val state = LanAddressPicker.snapshot(
            listOf(LanInterface("wlan0", listOf("192.168.86.21"), LanTransport.WIFI)),
        )
        assertThat(state.joinUrl(8787)).isEqualTo("http://192.168.86.21:8787/")
        assertThat(state.oauthCallbackUrl(8787)).isEqualTo("http://192.168.86.21:8787/oauth/callback")
    }

    @Test
    fun snapshotFromNetworkInterfacesDoesNotThrow() {
        val state = LanAddressPicker.snapshotFromNetworkInterfaces()
        assertThat(state.advertisedHost).isNotEmpty()
    }
}
