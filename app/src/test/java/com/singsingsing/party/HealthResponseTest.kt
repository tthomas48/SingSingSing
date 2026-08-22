package com.singsingsing.party

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

class HealthResponseTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun encodesLanFieldsForHttpHealthCheck() {
        val encoded = json.encodeToString(
            HealthResponse(
                ok = true,
                port = 8787,
                advertisedHost = "192.168.86.21",
                wifiHost = "192.168.86.21",
                ethernetHost = "10.0.0.5",
                wifiAvailable = true,
            ),
        )
        assertThat(encoded).contains("\"advertisedHost\":\"192.168.86.21\"")
        assertThat(encoded).contains("\"wifiHost\":\"192.168.86.21\"")
        assertThat(encoded).contains("\"ethernetHost\":\"10.0.0.5\"")
        assertThat(encoded).contains("\"wifiAvailable\":true")
        assertThat(encoded).contains("\"port\":8787")
    }

    @Test
    fun encodesMissingEthernetAsNull() {
        val encoded = json.encodeToString(
            HealthResponse(
                ok = true,
                port = 8787,
                advertisedHost = "192.168.86.21",
                wifiHost = "192.168.86.21",
                ethernetHost = null,
                wifiAvailable = true,
            ),
        )
        assertThat(encoded).contains("\"ethernetHost\":null")
        assertThat(encoded).contains("\"wifiAvailable\":true")
    }
}
