package com.singsingsing.tidal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TidalAuthClientTest {
    @Test
    fun isConfiguredRequiresBothCredentials() {
        assertThat(TidalAuthClient("", "").isConfigured()).isFalse()
        assertThat(TidalAuthClient("id", "").isConfigured()).isFalse()
        assertThat(TidalAuthClient("id", "secret").isConfigured()).isTrue()
    }

    @Test
    fun pkceChallengeIsUrlSafeBase64WithoutPadding() {
        val verifier = TidalAuthClient.generateCodeVerifier()
        val challenge = TidalAuthClient.codeChallengeS256(verifier)
        assertThat(verifier).doesNotContain("=")
        assertThat(challenge).doesNotContain("=")
        assertThat(challenge).doesNotContain("+")
        assertThat(challenge).doesNotContain("/")
        assertThat(challenge).isNotEmpty()
    }

    @Test
    fun beginPkceLoginBuildsAuthorizeUrl() {
        val auth = TidalAuthClient("client-id", "secret")
        val session = auth.beginPkceLogin("http://192.168.1.10:8787/oauth/callback")
        assertThat(session.authorizeUrl).startsWith("https://login.tidal.com/authorize?")
        assertThat(session.authorizeUrl).contains("client_id=client-id")
        assertThat(session.authorizeUrl).contains("code_challenge_method=S256")
        assertThat(session.redirectUri).isEqualTo("http://192.168.1.10:8787/oauth/callback")
    }
}
