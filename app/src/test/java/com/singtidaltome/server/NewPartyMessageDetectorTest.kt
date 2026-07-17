package com.singtidaltome.server

import com.google.common.truth.Truth.assertThat
import com.singtidaltome.party.PartyMessage
import org.junit.Test

class NewPartyMessageDetectorTest {
    private fun message(id: String) = PartyMessage(id = id, text = "msg $id", createdAtEpochMs = 0)

    @Test
    fun returnsOnlyMessagesAddedSinceLastUpdate() {
        val first = message("1")
        val second = message("2")
        val detector = NewPartyMessageDetector(listOf(first))

        assertThat(detector.update(listOf(first))).isEmpty()
        assertThat(detector.update(listOf(first, second))).containsExactly(second)
        assertThat(detector.update(listOf(first, second))).isEmpty()
    }

    @Test
    fun seedsFromInitialMessagesWithoutReporting() {
        val existing = listOf(message("1"), message("2"))
        val detector = NewPartyMessageDetector(existing)

        assertThat(detector.update(existing)).isEmpty()
    }
}
