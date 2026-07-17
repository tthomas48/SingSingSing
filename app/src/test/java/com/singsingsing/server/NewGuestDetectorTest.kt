package com.singsingsing.server

import com.google.common.truth.Truth.assertThat
import com.singsingsing.party.Guest
import org.junit.Test

class NewGuestDetectorTest {
    @Test
    fun returnsOnlyGuestsAddedSinceLastUpdate() {
        val tim = Guest("1", "Tim")
        val ada = Guest("2", "Ada")
        val detector = NewGuestDetector(listOf(tim))

        assertThat(detector.update(listOf(tim))).isEmpty()
        assertThat(detector.update(listOf(tim, ada))).containsExactly(ada)
        assertThat(detector.update(listOf(tim, ada))).isEmpty()
    }
}
