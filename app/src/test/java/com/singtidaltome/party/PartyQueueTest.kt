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
        assertThat(queue.snapshotHistory()).isEmpty()
    }

    @Test
    fun skipKeepsHistoryAndPreviousReturns() {
        val queue = PartyQueue()
        queue.add(item("a"))
        queue.add(item("b"))
        queue.advance()

        val next = queue.skip()
        assertThat(next?.id).isEqualTo("b")
        assertThat(queue.snapshotHistory().map { it.id }).containsExactly("a")
        assertThat(queue.snapshotQueue()).isEmpty()

        val previous = queue.replayPrevious()
        assertThat(previous?.id).isEqualTo("a")
        assertThat(queue.snapshotQueue().map { it.id }).containsExactly("b")
        assertThat(queue.snapshotHistory()).isEmpty()
    }

    @Test
    fun removeDeletesUpcomingItemOnly() {
        val queue = PartyQueue()
        queue.add(item("a"))
        queue.add(item("b"))
        queue.advance()
        assertThat(queue.remove("b")).isTrue()
        assertThat(queue.snapshotQueue()).isEmpty()
        assertThat(queue.remove("a")).isFalse()
        assertThat(queue.remove("missing")).isFalse()
    }

    @Test
    fun reorderMovesItemWithinUpcomingQueue() {
        val queue = PartyQueue()
        queue.add(item("a"))
        queue.add(item("b"))
        queue.add(item("c"))
        queue.advance()
        assertThat(queue.reorder("c", 0)).isTrue()
        assertThat(queue.snapshotQueue().map { it.id }).containsExactly("c", "b").inOrder()
        assertThat(queue.nowPlaying()?.id).isEqualTo("a")
        assertThat(queue.reorder("a", 0)).isFalse()
        assertThat(queue.reorder("missing", 0)).isFalse()
    }

    @Test
    fun jumpToAnyItemKeepsFullSession() {
        val queue = PartyQueue()
        queue.add(item("a"))
        queue.add(item("b"))
        queue.add(item("c"))
        queue.advance()

        val jumped = queue.jumpTo("c")
        assertThat(jumped?.id).isEqualTo("c")
        assertThat(queue.nowPlaying()?.id).isEqualTo("c")
        assertThat(queue.snapshotHistory().map { it.id }).containsExactly("a", "b").inOrder()
        assertThat(queue.snapshotQueue()).isEmpty()

        queue.jumpTo("a")
        assertThat(queue.nowPlaying()?.id).isEqualTo("a")
        assertThat(queue.snapshotHistory()).isEmpty()
        assertThat(queue.snapshotQueue().map { it.id }).containsExactly("b", "c").inOrder()
    }

    @Test
    fun containsActiveTrackIdCoversNowAndUpcomingNotHistory() {
        val queue = PartyQueue()
        queue.add(item("a", "t1"))
        queue.add(item("b", "t2"))
        queue.advance()
        assertThat(queue.containsActiveTrackId("t1")).isTrue()
        assertThat(queue.containsActiveTrackId("t2")).isTrue()
        queue.skip()
        assertThat(queue.containsActiveTrackId("t1")).isFalse()
        assertThat(queue.containsActiveTrackId("t2")).isTrue()
    }
}
