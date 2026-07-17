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
        var pauseCount = 0
        var skipToNextCount = 0
        var ready = true
        override fun isReady() = ready
        override fun play() = Unit
        override fun pause() {
            pauseCount += 1
        }
        override fun skipToNext() {
            skipToNextCount += 1
        }
        override fun skipToPrevious() = Unit
        override fun skipToQueueItem(queueItemId: Long) = true
        override suspend fun playTrack(track: TrackRef): Boolean {
            played += track
            return true
        }
        override fun readQueue(): List<BridgeQueueItem> = emptyList()
    }

    private class FakeQueuePersistence(
        private var persisted: PersistedPartyQueue? = null,
    ) : PartyQueuePersistence {
        val saves = mutableListOf<PersistedPartyQueue>()

        override fun load(): PersistedPartyQueue? = persisted

        override fun save(queue: PersistedPartyQueue) {
            persisted = queue
            saves += queue
        }

        override fun clear() {
            persisted = null
        }
    }

    private fun session(
        bridge: FakeBridge = FakeBridge(),
        queuePersistence: PartyQueuePersistence? = null,
        nowMs: () -> Long = { System.currentTimeMillis() },
    ): Pair<PartySession, FakeBridge> {
        val auth = TidalAuthClient(clientId = "", clientSecret = "")
        val catalog = TidalCatalogClient(authClient = auth, countryCode = "US")
        val party = PartySession(
            tidalCatalog = catalog,
            lrcLibClient = LrcLibClient(),
            queuePersistence = queuePersistence,
            nowMs = nowMs,
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

    @Test
    fun reorderAndJumpToWorkWithAttribution() = runTest {
        val (party, bridge) = session()
        val guest = party.join("Bee")
        party.addTrack(guest.id, TrackRef("1", "One", "A"))
        party.addTrack(guest.id, TrackRef("2", "Two", "B"))
        party.addTrack(guest.id, TrackRef("3", "Three", "C"))

        val secondId = party.snapshot.value.queue[0].id
        val thirdId = party.snapshot.value.queue[1].id
        party.reorderQueue(guest.id, thirdId, 0)
        assertThat(party.snapshot.value.queue.map { it.track.tidalTrackId })
            .containsExactly("3", "2").inOrder()

        party.jumpTo(guest.id, secondId)
        assertThat(party.snapshot.value.nowPlaying.track?.tidalTrackId).isEqualTo("2")
        assertThat(party.snapshot.value.history.map { it.track.tidalTrackId })
            .containsExactly("1", "3").inOrder()
        assertThat(party.snapshot.value.queue).isEmpty()
        assertThat(bridge.played.map { it.tidalTrackId }).containsExactly("1", "2").inOrder()
        assertThat(party.snapshot.value.messages.any { it.text.contains("jumped to Two") }).isTrue()
    }

    @Test
    fun postMessageAttributesWithSaysAndTrimsText() = runTest {
        val (party, _) = session()
        val guest = party.join("Tim")

        party.postMessage(guest.id, "  hello everyone  ")

        assertThat(party.snapshot.value.messages.any { it.text == "Tim says hello everyone" }).isTrue()
    }

    @Test
    fun postMessageRejectsBlankText() = runTest {
        val (party, _) = session()
        val guest = party.join("Tim")

        val result = runCatching { party.postMessage(guest.id, "   ") }

        assertThat(result.isFailure).isTrue()
        assertThat(party.snapshot.value.messages.none { it.text.startsWith("Tim says") }).isTrue()
    }

    @Test
    fun postMessageTruncatesLongText() = runTest {
        val (party, _) = session()
        val guest = party.join("Tim")

        party.postMessage(guest.id, "x".repeat(200))

        assertThat(party.snapshot.value.messages.any { it.text == "Tim says ${"x".repeat(120)}" }).isTrue()
    }

    @Test
    fun skipKeepsSungTracksInHistory() = runTest {
        val (party, _) = session()
        val guest = party.join("Ada")
        party.addTrack(guest.id, TrackRef("1", "One", "A"))
        party.addTrack(guest.id, TrackRef("2", "Two", "B"))
        party.skip(guest.id)
        assertThat(party.snapshot.value.history.map { it.track.tidalTrackId }).containsExactly("1")
        assertThat(party.snapshot.value.nowPlaying.track?.tidalTrackId).isEqualTo("2")
    }

    @Test
    fun nearEndAdvancesToNextPartyTrackWithoutForeignMetadata() = runTest {
        var clock = 1_000_000L
        val (party, bridge) = session(nowMs = { clock })
        val guest = party.join("Ada")
        party.addTrack(
            guest.id,
            TrackRef(tidalTrackId = "1", title = "One", artist = "A", durationSeconds = 10),
        )
        party.addTrack(
            guest.id,
            TrackRef(tidalTrackId = "2", title = "Two", artist = "B", durationSeconds = 20),
        )
        assertThat(bridge.played.map { it.tidalTrackId }).containsExactly("1")

        // Expire play-launch grace so near-end logic is armed.
        clock += 5_000
        party.onTidalMetadata(
            trackId = "1",
            title = "One",
            artist = "A",
            positionMs = 8_000,
            playing = true,
        )

        assertThat(bridge.played.map { it.tidalTrackId }).containsExactly("1", "2").inOrder()
        assertThat(bridge.pauseCount).isAtLeast(1)
        assertThat(bridge.skipToNextCount).isEqualTo(0)
        assertThat(party.snapshot.value.nowPlaying.track?.tidalTrackId).isEqualTo("2")
        assertThat(party.snapshot.value.history.map { it.track.tidalTrackId }).containsExactly("1")
    }

    @Test
    fun stalePreviousTrackMetadataDoesNotDoubleSkipAfterNearEndAdvance() = runTest {
        var clock = 1_000_000L
        val (party, bridge) = session(nowMs = { clock })
        val guest = party.join("Ada")
        party.addTrack(
            guest.id,
            TrackRef(tidalTrackId = "1", title = "One", artist = "A", durationSeconds = 10),
        )
        party.addTrack(
            guest.id,
            TrackRef(tidalTrackId = "2", title = "Two", artist = "B", durationSeconds = 20),
        )
        party.addTrack(
            guest.id,
            TrackRef(tidalTrackId = "3", title = "Three", artist = "C", durationSeconds = 30),
        )
        assertThat(bridge.played.map { it.tidalTrackId }).containsExactly("1")

        clock += 5_000
        party.onTidalMetadata(
            trackId = "1",
            title = "One",
            artist = "A",
            positionMs = 8_000,
            playing = true,
        )
        assertThat(bridge.played.map { it.tidalTrackId }).containsExactly("1", "2").inOrder()
        assertThat(party.snapshot.value.nowPlaying.track?.tidalTrackId).isEqualTo("2")

        // Expire launch grace, then deliver a delayed MediaSession snapshot from song 1.
        clock += 5_000
        party.onTidalMetadata(
            trackId = "1",
            title = "One",
            artist = "A",
            positionMs = 9_500,
            playing = false,
        )

        assertThat(bridge.played.map { it.tidalTrackId }).containsExactly("1", "2").inOrder()
        assertThat(party.snapshot.value.nowPlaying.track?.tidalTrackId).isEqualTo("2")
        assertThat(party.snapshot.value.queue.map { it.track.tidalTrackId }).containsExactly("3")
    }

    @Test
    fun foreignTrackReclaimPausesThenPlaysNext() = runTest {
        var clock = 1_000_000L
        val (party, bridge) = session(nowMs = { clock })
        val guest = party.join("Ada")
        party.addTrack(guest.id, TrackRef("1", "One", "A", durationSeconds = 200))
        party.addTrack(guest.id, TrackRef("2", "Two", "B", durationSeconds = 200))

        party.onTidalMetadata("1", "One", "A", positionMs = 1_000, playing = true)
        clock += 5_000

        party.onTidalMetadata(
            trackId = "999",
            title = "Radio filler",
            artist = "Tidal",
            positionMs = 0,
            playing = true,
        )

        assertThat(bridge.played.map { it.tidalTrackId }).containsExactly("1", "2").inOrder()
        assertThat(bridge.pauseCount).isAtLeast(1)
        assertThat(bridge.skipToNextCount).isEqualTo(0)
        assertThat(party.snapshot.value.nowPlaying.track?.tidalTrackId).isEqualTo("2")
    }

    @Test
    fun foreignTrackWithEmptyUpNextPausesWithoutAdvancing() = runTest {
        var clock = 1_000_000L
        val (party, bridge) = session(nowMs = { clock })
        val guest = party.join("Ada")
        party.addTrack(guest.id, TrackRef("1", "One", "A", durationSeconds = 200))

        party.onTidalMetadata("1", "One", "A", positionMs = 1_000, playing = true)
        clock += 5_000

        party.onTidalMetadata(
            trackId = "cutthroat",
            title = "Cutthroat",
            artist = "Shame",
            positionMs = 0,
            playing = true,
        )

        assertThat(bridge.played.map { it.tidalTrackId }).containsExactly("1")
        assertThat(bridge.pauseCount).isAtLeast(1)
        assertThat(bridge.skipToNextCount).isEqualTo(0)
        assertThat(party.snapshot.value.nowPlaying.track?.tidalTrackId).isEqualTo("1")
        assertThat(party.snapshot.value.nowPlaying.isPlaying).isFalse()
        assertThat(party.snapshot.value.queue).isEmpty()
    }

    @Test
    fun rejectsDuplicateActiveTrackButAllowsAfterHistory() = runTest {
        val (party, _) = session()
        val guest = party.join("Ada")
        party.addTrack(guest.id, TrackRef("1", "One", "A"))
        party.addTrack(guest.id, TrackRef("2", "Two", "B"))

        try {
            party.addTrack(guest.id, TrackRef("1", "One again", "A"))
            throw AssertionError("expected duplicate add to fail")
        } catch (error: IllegalStateException) {
            assertThat(error).hasMessageThat().contains("Already in the queue")
        }

        party.skip(guest.id)
        assertThat(party.snapshot.value.history.map { it.track.tidalTrackId }).containsExactly("1")
        party.addTrack(guest.id, TrackRef("1", "One again", "A"))
        assertThat(party.snapshot.value.queue.map { it.track.tidalTrackId }).containsExactly("1")
    }

    @Test
    fun restoresAndPersistsQueueWithoutAutoPlaying() = runTest {
        fun item(id: String) = QueueItem(
            id = id,
            track = TrackRef(id, "Song $id", "Artist"),
            addedByGuestId = "old-guest",
            addedByName = "Earlier guest",
        )
        val persistence = FakeQueuePersistence(
            PersistedPartyQueue(
                items = listOf(item("1"), item("2"), item("3")),
                currentIndex = 1,
            ),
        )

        val (party, bridge) = session(queuePersistence = persistence)

        assertThat(bridge.played).isEmpty()
        assertThat(party.snapshot.value.nowPlaying.isPlaying).isFalse()
        assertThat(party.snapshot.value.history.map { it.id }).containsExactly("1")
        assertThat(party.snapshot.value.nowPlaying.track?.tidalTrackId).isEqualTo("2")
        assertThat(party.snapshot.value.queue.map { it.id }).containsExactly("3")

        val guest = party.join("New guest")
        assertThat(persistence.saves.last().currentIndex).isEqualTo(1)
        assertThat(persistence.saves.last().items.map { it.id })
            .containsExactly("1", "2", "3").inOrder()

        party.onTidalMetadata(
            trackId = "foreign-track",
            title = "Something Tidal was already playing",
            artist = "Another artist",
            positionMs = 42_000,
            playing = true,
        )
        assertThat(bridge.played).isEmpty()
        assertThat(party.snapshot.value.nowPlaying.isPlaying).isFalse()
        assertThat(party.snapshot.value.nowPlaying.track?.tidalTrackId).isEqualTo("2")

        party.play(guest.id)
        assertThat(bridge.played.map { it.tidalTrackId }).containsExactly("2")
        assertThat(party.snapshot.value.nowPlaying.isPlaying).isTrue()
    }
}
