package com.singtidaltome.party

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PartyQueueStoreTest {
    @Test
    fun codecRoundTripsFullQueue() {
        val persisted = PersistedPartyQueue(
            items = listOf(
                QueueItem(
                    id = "queue-1",
                    track = TrackRef(
                        tidalTrackId = "track-1",
                        title = "Song",
                        artist = "Singer",
                        album = "Album",
                        durationSeconds = 123,
                        artistId = "artist-1",
                    ),
                    addedByGuestId = "guest-1",
                    addedByName = "Tim",
                ),
            ),
            currentIndex = 0,
        )

        val restored = PartyQueueStore.decode(PartyQueueStore.encode(persisted))

        assertThat(restored).isEqualTo(persisted)
    }

    @Test
    fun corruptQueueDataIsIgnored() {
        assertThat(PartyQueueStore.decode("not-json")).isNull()
    }
}
