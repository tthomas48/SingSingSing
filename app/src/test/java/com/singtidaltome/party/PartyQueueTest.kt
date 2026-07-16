package com.singtidaltome.party

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PartyQueueTest {
    private fun track(id: String, title: String = "Song $id") = TrackRef(
        tidalTrackId = id,
        title = title,
        artist = "Artist",
    )

    private fun item(id: String, trackId: String = id) = QueueItem(
        id = id,
        track = track(trackId),
        addedByGuestId = "g1",
        addedByName = "Tim",
    )

    @Test
    fun addAndAdvanceStartsFirstTrack() {
        val queue = PartyQueue()
        queue.add(item("a"))
        queue.add(item("b"))

        val now = queue.advance()
        assertThat(now?.id).isEqualTo("a")
        assertThat(queue.snapshotQueue().map { it.id }).containsExactly("b")
    }

    @Test
    fun skipMovesToNextAndRemembersPrevious() {
        val queue = PartyQueue()
        queue.add(item("a"))
        queue.add(item("b"))
        queue.advance()

        val next = queue.skip()
        assertThat(next?.id).isEqualTo("b")

        val previous = queue.replayPrevious()
        assertThat(previous?.id).isEqualTo("a")
        assertThat(queue.snapshotQueue().map { it.id }).containsExactly("b")
    }

    @Test
    fun removeDeletesQueuedItemOnly() {
        val queue = PartyQueue()
        queue.add(item("a"))
        queue.add(item("b"))
        assertThat(queue.remove("b")).isTrue()
        assertThat(queue.snapshotQueue().map { it.id }).containsExactly("a")
        assertThat(queue.remove("missing")).isFalse()
    }
}
