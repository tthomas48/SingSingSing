package com.singsingsing.tidal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TidalApiLogTest {
    @Test
    fun userFacingErrorUsesJsonApiDetailWithoutUrls() {
        val body = """
            {"errors":[{"status":"403","detail":"Playlist is not writable"}]}
        """.trimIndent()
        assertThat(TidalApiLog.userFacingError(403, body)).isEqualTo("Playlist is not writable")
        assertThat(TidalApiLog.userFacingError(403, body).lowercase()).doesNotContain("http")
    }

    @Test
    fun userFacingErrorFallsBackToStatusWhenDetailHasUrl() {
        val body = """
            {"errors":[{"detail":"See https://openapi.tidal.com/docs"}]}
        """.trimIndent()
        assertThat(TidalApiLog.userFacingError(500, body))
            .isEqualTo("Couldn't update the karaoke library (status 500)")
        assertThat(TidalApiLog.userFacingError(500, body).lowercase()).doesNotContain("http")
    }

    @Test
    fun conflictStatusCountsAsAlreadyInPlaylist() {
        assertThat(TidalApiLog.isAlreadyInPlaylist(409, "")).isTrue()
        assertThat(
            TidalApiLog.isAlreadyInPlaylist(
                422,
                """{"errors":[{"detail":"Item already exists in playlist"}]}""",
            ),
        ).isTrue()
        assertThat(TidalApiLog.isAlreadyInPlaylist(403, """{"errors":[{"detail":"Forbidden"}]}"""))
            .isFalse()
    }
}
