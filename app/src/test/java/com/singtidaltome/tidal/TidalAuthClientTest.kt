package com.singtidaltome.tidal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TidalAuthClientTest {
    @Test
    fun isConfiguredRequiresBothCredentials() {
        assertThat(TidalAuthClient("", "").isConfigured()).isFalse()
        assertThat(TidalAuthClient("id", "").isConfigured()).isFalse()
        assertThat(TidalAuthClient("id", "secret").isConfigured()).isTrue()
    }
}
