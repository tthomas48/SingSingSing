package com.singtidaltome.party

import com.google.common.truth.Truth.assertThat
import com.singtidaltome.bridge.BridgeQueueItem
import com.singtidaltome.bridge.TidalBridge
import com.singtidaltome.lyrics.LrcLibClient
import com.singtidaltome.tidal.TidalAuthClient
import com.singtidaltome.tidal.TidalCatalogClient
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PartySessionTest {
    private class FakeBridge : TidalBridge {
        val played = mutableListOf<TrackRef>()
        var ready = true
        override fun isReady() = ready
        override fun play() = Unit
        override fun pause() = Unit
        override fun skipToNext() = Unit
        override fun skipToPrevious() = Unit
        override fun skipToQueueItem(queueItemId: Long) = true
        override suspend fun playTrack(track: TrackRef): Boolean {
            played += track
            return true
        }
        override fun readQueue(): List<BridgeQueueItem> = emptyList()
    }

    private fun session(bridge: FakeBridge = FakeBridge()): Pair<PartySession, FakeBridge> {
        val auth = TidalAuthClient(clientId = "", clientSecret = "")
        val catalog = TidalCatalogClient(authClient = auth, countryCode = "US")
        val party = PartySession(
            tidalCatalog = catalog,
            lrcLibClient = LrcLibClient(),
        )
        party.attachBridge(bridge)
        return party to bridge
    }

    @Test
    fun joinAndAddTrackStartsPlaybackWithAttribution() = runTest {
        val (party, bridge) = session()
        val guest = party.join("Tim")
        val track = TrackRef(
            tidalTrackId = "95574931",
            title = "Wolf Like Me",
            artist = "TV On The Radio",
        )

        party.addTrack(guest.id, track)

        assertThat(bridge.played.map { it.tidalTrackId }).containsExactly("95574931")
        val snap = party.snapshot.value
        assertThat(snap.nowPlaying.track?.title).isEqualTo("Wolf Like Me")
        assertThat(snap.nowPlaying.addedByName).isEqualTo("Tim")
        assertThat(snap.messages.any { it.text.contains("Tim added Wolf Like Me") }).isTrue()
    }

    @Test
    fun secondTrackWaitsInQueueUntilSkip() = runTest {
        val (party, bridge) = session()
        val guest = party.join("Ada")
        party.addTrack(guest.id, TrackRef("1", "One", "A"))
        party.addTrack(guest.id, TrackRef("2", "Two", "B"))

        assertThat(bridge.played).hasSize(1)
        assertThat(party.snapshot.value.queue).hasSize(1)

        party.skip(guest.id)
        assertThat(bridge.played.map { it.tidalTrackId }).containsExactly("1", "2").inOrder()
        assertThat(party.snapshot.value.nowPlaying.track?.title).isEqualTo("Two")
    }
}
