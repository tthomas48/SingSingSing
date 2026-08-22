package com.singsingsing.party

import com.google.common.truth.Truth.assertThat
import com.singsingsing.bridge.BridgeQueueItem
import com.singsingsing.bridge.TidalBridge
import com.singsingsing.lyrics.LrcLibClient
import com.singsingsing.tidal.TidalAuthClient
import com.singsingsing.tidal.TidalCatalogClient
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PartySessionTest {
    private class FakeBridge : TidalBridge {
        val played = mutableListOf<TrackRef>()
        var pauseCount = 0
        var skipToNextCount = 0
        var ready = true
        var playResult = true
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
            return playResult
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
    fun addingVideoUsesVideoAttribution() = runTest {
        val (party, bridge) = session()
        val guest = party.join("Tim")
        val video = TrackRef(
            tidalTrackId = "vid-1",
            title = "Wolf Like Me",
            artist = "TV On The Radio",
            mediaType = MEDIA_TYPE_VIDEO,
        )

        party.addTrack(guest.id, video)

        assertThat(bridge.played.map { it.tidalTrackId }).containsExactly("vid-1")
        assertThat(bridge.played[0].mediaType).isEqualTo(MEDIA_TYPE_VIDEO)
        assertThat(
            party.snapshot.value.messages.any {
                it.text == "Tim added video Wolf Like Me by TV On The Radio"
            },
        ).isTrue()
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
    fun videoWithMismatchedMediaIdAndSameTitleDoesNotPause() = runTest {
        var clock = 1_000_000L
        val (party, bridge) = session(nowMs = { clock })
        val guest = party.join("Ada")
        party.addTrack(
            guest.id,
            TrackRef(
                tidalTrackId = "v1",
                title = "Wolf Like Me",
                artist = "TV On The Radio",
                durationSeconds = 200,
                mediaType = MEDIA_TYPE_VIDEO,
            ),
        )

        party.onTidalMetadata("v1", "Wolf Like Me", "TV On The Radio", positionMs = 500, playing = true)
        clock += 5_000

        party.onTidalMetadata(
            trackId = "audio-95574931",
            title = "Wolf Like Me",
            artist = "TV On The Radio",
            positionMs = 2_000,
            playing = true,
        )

        assertThat(bridge.pauseCount).isEqualTo(0)
        assertThat(party.snapshot.value.nowPlaying.isPlaying).isTrue()
        assertThat(party.snapshot.value.nowPlaying.track?.tidalTrackId).isEqualTo("v1")
        assertThat(party.snapshot.value.nowPlaying.positionMs).isEqualTo(2_000)
    }

    @Test
    fun audioWithMismatchedMediaIdAndSameTitleDoesNotPause() = runTest {
        var clock = 1_000_000L
        val (party, bridge) = session(nowMs = { clock })
        val guest = party.join("Ada")
        party.addTrack(
            guest.id,
            TrackRef(
                tidalTrackId = "35778985",
                title = "Dishes",
                artist = "Pulp",
                durationSeconds = 200,
            ),
        )

        party.onTidalMetadata("35778985", "Dishes", "Pulp", positionMs = 500, playing = true)
        clock += 5_000

        party.onTidalMetadata(
            trackId = "session-other",
            title = "Dishes",
            artist = "Pulp",
            positionMs = 2_000,
            playing = true,
        )

        assertThat(bridge.pauseCount).isEqualTo(0)
        assertThat(bridge.played.map { it.tidalTrackId }).containsExactly("35778985")
        assertThat(party.snapshot.value.nowPlaying.isPlaying).isTrue()
        assertThat(party.snapshot.value.nowPlaying.track?.tidalTrackId).isEqualTo("35778985")
        assertThat(party.snapshot.value.nowPlaying.positionMs).isEqualTo(2_000)
    }

    @Test
    fun audioWithNormalizedCountrySuffixIdCountsAsOwned() = runTest {
        var clock = 1_000_000L
        val (party, bridge) = session(nowMs = { clock })
        val guest = party.join("Ada")
        party.addTrack(
            guest.id,
            TrackRef(
                tidalTrackId = "73416054",
                title = "Hanging On The Telephone",
                artist = "Blondie",
                durationSeconds = 200,
            ),
        )

        party.onTidalMetadata("73416054", "Hanging On The Telephone", "Blondie", 500, true)
        clock += 5_000

        party.onTidalMetadata(
            trackId = "73416054-US",
            title = "Hanging On The Telephone",
            artist = "Blondie",
            positionMs = 3_000,
            playing = true,
        )

        assertThat(bridge.pauseCount).isEqualTo(0)
        assertThat(party.snapshot.value.nowPlaying.isPlaying).isTrue()
        assertThat(party.snapshot.value.nowPlaying.track?.tidalTrackId).isEqualTo("73416054")
        assertThat(party.snapshot.value.nowPlaying.positionMs).isEqualTo(3_000)
    }

    @Test
    fun audioWithMismatchedIdAndDifferentTitleStillReclaims() = runTest {
        var clock = 1_000_000L
        val (party, bridge) = session(nowMs = { clock })
        val guest = party.join("Ada")
        party.addTrack(guest.id, TrackRef("1", "One", "A", durationSeconds = 200))
        party.addTrack(guest.id, TrackRef("2", "Two", "B", durationSeconds = 200))

        party.onTidalMetadata("1", "One", "A", positionMs = 1_000, playing = true)
        clock += 5_000

        party.onTidalMetadata(
            trackId = "session-other",
            title = "Radio filler",
            artist = "Tidal",
            positionMs = 0,
            playing = true,
        )

        assertThat(bridge.played.map { it.tidalTrackId }).containsExactly("1", "2").inOrder()
        assertThat(bridge.pauseCount).isAtLeast(1)
        assertThat(party.snapshot.value.nowPlaying.track?.tidalTrackId).isEqualTo("2")
    }

    @Test
    fun videoWithDifferentTitleStillReclaims() = runTest {
        var clock = 1_000_000L
        val (party, bridge) = session(nowMs = { clock })
        val guest = party.join("Ada")
        party.addTrack(
            guest.id,
            TrackRef(
                tidalTrackId = "v1",
                title = "Wolf Like Me",
                artist = "TV On The Radio",
                durationSeconds = 200,
                mediaType = MEDIA_TYPE_VIDEO,
            ),
        )
        party.addTrack(guest.id, TrackRef("2", "Two", "B", durationSeconds = 200))

        party.onTidalMetadata("v1", "Wolf Like Me", "TV On The Radio", positionMs = 500, playing = true)
        clock += 25_000

        party.onTidalMetadata(
            trackId = "radio-1",
            title = "Completely Different Song",
            artist = "Someone Else",
            positionMs = 0,
            playing = true,
        )

        assertThat(bridge.pauseCount).isAtLeast(1)
        assertThat(party.snapshot.value.nowPlaying.track?.tidalTrackId).isEqualTo("2")
    }

    @Test
    fun leftoverHistoryAudioDoesNotSkipQueuedVideoAfterGrace() = runTest {
        var clock = 1_000_000L
        val (party, bridge) = session(nowMs = { clock })
        val guest = party.join("Ada")
        party.addTrack(guest.id, TrackRef("1", "One", "A", durationSeconds = 200))
        party.addTrack(
            guest.id,
            TrackRef(
                tidalTrackId = "v1",
                title = "Wolf Like Me",
                artist = "TV On The Radio",
                durationSeconds = 200,
                mediaType = MEDIA_TYPE_VIDEO,
            ),
        )
        party.addTrack(guest.id, TrackRef("3", "Three", "C", durationSeconds = 200))

        party.onTidalMetadata("1", "One", "A", positionMs = 1_000, playing = true)
        party.skip(guest.id)
        assertThat(party.snapshot.value.nowPlaying.track?.tidalTrackId).isEqualTo("v1")

        clock += 25_000
        party.onTidalMetadata(
            trackId = "1",
            title = "One",
            artist = "A",
            positionMs = 12_000,
            playing = false,
        )

        assertThat(bridge.played.map { it.tidalTrackId }).containsExactly("1", "v1").inOrder()
        assertThat(party.snapshot.value.nowPlaying.track?.tidalTrackId).isEqualTo("v1")
        assertThat(party.snapshot.value.queue.map { it.track.tidalTrackId }).containsExactly("3")
    }

    @Test
    fun officialVideoTitleStillOwnsQueuedVideo() = runTest {
        var clock = 1_000_000L
        val (party, bridge) = session(nowMs = { clock })
        val guest = party.join("Ada")
        party.addTrack(
            guest.id,
            TrackRef(
                tidalTrackId = "v1",
                title = "Wolf Like Me",
                artist = "TV On The Radio",
                durationSeconds = 200,
                mediaType = MEDIA_TYPE_VIDEO,
            ),
        )
        party.addTrack(guest.id, TrackRef("2", "Two", "B", durationSeconds = 200))

        clock += 25_000
        party.onTidalMetadata(
            trackId = "session-video",
            title = "Wolf Like Me (Official Video)",
            artist = "TV On The Radio",
            positionMs = 2_000,
            playing = true,
        )

        assertThat(bridge.pauseCount).isEqualTo(0)
        assertThat(bridge.played.map { it.tidalTrackId }).containsExactly("v1")
        assertThat(party.snapshot.value.nowPlaying.track?.tidalTrackId).isEqualTo("v1")
        assertThat(party.snapshot.value.nowPlaying.positionMs).isEqualTo(2_000)
    }

    @Test
    fun failedVideoStartDoesNotAdvanceQueue() = runTest {
        val (party, bridge) = session()
        bridge.playResult = false
        val guest = party.join("Ada")
        party.addTrack(
            guest.id,
            TrackRef(
                tidalTrackId = "v1",
                title = "Wolf Like Me",
                artist = "TV On The Radio",
                mediaType = MEDIA_TYPE_VIDEO,
            ),
        )
        party.addTrack(guest.id, TrackRef("2", "Two", "B"))

        assertThat(party.snapshot.value.nowPlaying.track?.tidalTrackId).isEqualTo("v1")
        assertThat(party.snapshot.value.queue.map { it.track.tidalTrackId }).containsExactly("2")
        assertThat(party.snapshot.value.nowPlaying.isPlaying).isFalse()
        assertThat(party.snapshot.value.messages.map { it.text })
            .contains("Could not start Wolf Like Me on Tidal")
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

    @Test
    fun addRandomTracksStartsPlaybackWhenIdleWithSingleMessage() = runTest {
        val (party, bridge) = session()
        val guest = party.join("Tim")
        val library = listOf(
            TrackRef("1", "One", "Alpha"),
            TrackRef("2", "Two", "Beta"),
            TrackRef("3", "Three", "Gamma"),
            TrackRef("4", "Four", "Delta"),
            TrackRef("5", "Five", "Epsilon"),
        )

        val added = party.addRandomTracks(guest.id, library, count = 5, random = kotlin.random.Random(1))

        assertThat(added).hasSize(5)
        assertThat(bridge.played).hasSize(1)
        assertThat(party.snapshot.value.nowPlaying.track?.tidalTrackId).isEqualTo(added[0].track.tidalTrackId)
        assertThat(party.snapshot.value.queue).hasSize(4)
        val randomMessages = party.snapshot.value.messages.filter {
            it.text.contains("random")
        }
        assertThat(randomMessages).hasSize(1)
        assertThat(randomMessages[0].text).isEqualTo("Tim added 5 random songs from the library")
    }

    @Test
    fun addRandomTracksSkipsAlreadyQueuedAndWaitsWhenPlaying() = runTest {
        val (party, bridge) = session()
        val guest = party.join("Ada")
        party.addTrack(guest.id, TrackRef("1", "One", "Alpha"))
        val library = listOf(
            TrackRef("1", "One", "Alpha"),
            TrackRef("2", "Two", "Beta"),
            TrackRef("3", "Three", "Gamma"),
        )

        val added = party.addRandomTracks(guest.id, library, count = 5, random = kotlin.random.Random(2))

        assertThat(added.map { it.track.tidalTrackId }).containsExactly("2", "3")
        assertThat(bridge.played.map { it.tidalTrackId }).containsExactly("1")
        assertThat(party.snapshot.value.queue.map { it.track.tidalTrackId }).containsExactly("2", "3")
    }

    @Test
    fun addRandomTracksErrorsWhenNothingLeft() = runTest {
        val (party, _) = session()
        val guest = party.join("Ada")
        party.addTrack(guest.id, TrackRef("1", "One", "A"))
        try {
            party.addRandomTracks(guest.id, listOf(TrackRef("1", "One", "A")), count = 5)
            throw AssertionError("expected empty random add to fail")
        } catch (error: IllegalStateException) {
            assertThat(error).hasMessageThat().contains("No library songs left to add")
        }
    }

    @Test
    fun addRandomFromLibraryErrorsWhenLibraryNotConfigured() = runTest {
        val (party, _) = session()
        val guest = party.join("Ada")
        try {
            party.addRandomFromLibrary(guest.id, count = 5)
            throw AssertionError("expected missing library to fail")
        } catch (error: IllegalStateException) {
            assertThat(error).hasMessageThat().contains("Host hasn't set a karaoke library yet")
        }
    }

    @Test
    fun addRandomTracksRejectsUnknownGuest() = runTest {
        val (party, _) = session()
        try {
            party.addRandomTracks("missing", listOf(TrackRef("1", "One", "A")), count = 1)
            throw AssertionError("expected unknown guest to fail")
        } catch (error: IllegalStateException) {
            assertThat(error).hasMessageThat().contains("Unknown guest")
        }
    }

    @Test
    fun addRandomTracksSingularMessageForOneSong() = runTest {
        val (party, _) = session()
        val guest = party.join("Bo")
        party.addRandomTracks(guest.id, listOf(TrackRef("9", "Solo", "One Artist")), count = 5)
        assertThat(
            party.snapshot.value.messages.any { it.text == "Bo added 1 random song from the library" },
        ).isTrue()
    }
}
