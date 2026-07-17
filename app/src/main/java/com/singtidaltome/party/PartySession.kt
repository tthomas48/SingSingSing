package com.singtidaltome.party

import com.singtidaltome.bridge.TidalBridge
import com.singtidaltome.lyrics.LrcLibClient
import com.singtidaltome.lyrics.LyricsOpener
import com.singtidaltome.tidal.TidalCatalogClient
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class PartySession(
    private val tidalCatalog: TidalCatalogClient,
    private val lrcLibClient: LrcLibClient,
    private val queue: PartyQueue = PartyQueue(),
    private val queuePersistence: PartyQueuePersistence? = null,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val mutex = Mutex()
    private val guests = linkedMapOf<String, Guest>()
    private val messages = ArrayDeque<PartyMessage>()

    private var bridge: TidalBridge? = null
    private var lyricsOpener: LyricsOpener? = null
    private var bridgeReady: Boolean = false
    private var positionMs: Long = 0
    private var isPlaying: Boolean = false
    private var lastObservedTidalTrackId: String? = null
    /** Ignore foreign metadata while we are launching a party track. */
    private var playLaunchUntilEpochMs: Long = 0
    /** Prevent double near-end advances for the same now-playing item. */
    private var nearEndAdvanceArmed: Boolean = true
    /** Restored queues remain inert until a guest explicitly resumes party playback. */
    private var restoredQueueDormant: Boolean = false

    init {
        queuePersistence?.load()?.let(queue::restore)
        lastObservedTidalTrackId = queue.nowPlaying()?.track?.tidalTrackId
        restoredQueueDormant = queue.nowPlaying() != null
    }

    private val _snapshot = MutableStateFlow(buildSnapshotLocked())
    val snapshot: StateFlow<PartySnapshot> = _snapshot.asStateFlow()

    private val _events = MutableSharedFlow<PartySnapshot>(extraBufferCapacity = 16)
    val events: SharedFlow<PartySnapshot> = _events.asSharedFlow()

    fun attachBridge(bridge: TidalBridge) {
        this.bridge = bridge
    }

    fun attachLyricsOpener(opener: LyricsOpener) {
        this.lyricsOpener = opener
    }

    suspend fun setBridgeReady(ready: Boolean) = mutex.withLock {
        bridgeReady = ready
        publishLocked()
    }

    suspend fun refreshLibrarySnapshot() = mutex.withLock {
        publishLocked()
    }

    suspend fun join(name: String): Guest = mutex.withLock {
        val cleaned = name.trim().ifBlank { "Guest" }.take(24)
        val guest = Guest(id = UUID.randomUUID().toString(), name = cleaned)
        guests[guest.id] = guest
        addMessageLocked("${guest.name} joined the party")
        publishLocked()
        guest
    }

    suspend fun search(query: String): List<TrackRef> {
        return tidalCatalog.searchTracks(query)
    }

    suspend fun artistTracks(artistId: String): List<TrackRef> {
        return tidalCatalog.getArtistTracks(artistId)
    }

    suspend fun libraryTracks(query: String = ""): LibraryResponse {
        if (!tidalCatalog.isLibraryConfigured()) {
            return LibraryResponse(tracks = emptyList(), configured = false)
        }
        val tracks = if (query.isBlank()) {
            tidalCatalog.getLibraryTracks()
        } else {
            tidalCatalog.searchLibrary(query)
        }
        return LibraryResponse(
            tracks = tracks,
            playlistName = tidalCatalog.libraryPlaylistName(),
            configured = true,
        )
    }

    suspend fun listHostPlaylists(): List<PlaylistSummary> = tidalCatalog.listUserPlaylists()

    suspend fun addTrack(guestId: String, track: TrackRef): QueueItem = mutex.withLock {
        val guest = guests[guestId] ?: error("Unknown guest")
        if (queue.containsActiveTrackId(track.tidalTrackId)) {
            error("Already in the queue")
        }
        val item = QueueItem(
            id = UUID.randomUUID().toString(),
            track = track,
            addedByGuestId = guest.id,
            addedByName = guest.name,
        )
        queue.add(item)
        addMessageLocked("${guest.name} added ${track.title} by ${track.artist}")
        val shouldStart = queue.nowPlaying() == null
        publishLocked()
        if (shouldStart) {
            startNextLocked()
        }
        item
    }

    suspend fun reorderQueue(guestId: String, itemId: String, toIndex: Int) = mutex.withLock {
        val guest = requireGuestLocked(guestId)
        if (!queue.reorder(itemId, toIndex)) {
            error("Queue item not found")
        }
        addMessageLocked("${guest.name} reordered the queue")
        publishLocked()
    }

    suspend fun jumpTo(guestId: String, itemId: String) = mutex.withLock {
        val guest = requireGuestLocked(guestId)
        val item = queue.jumpTo(itemId) ?: error("Queue item not found")
        addMessageLocked("${guest.name} jumped to ${item.track.title}")
        playItemLocked(item)
        publishLocked()
    }

    suspend fun favoriteTrack(guestId: String, track: TrackRef) {
        mutex.withLock {
            requireGuestLocked(guestId)
            if (!tidalCatalog.isLibraryConfigured()) {
                error("Host hasn't set a karaoke library yet")
            }
        }
        tidalCatalog.addTrackToLibrary(track.tidalTrackId)
        mutex.withLock {
            val guest = requireGuestLocked(guestId)
            val libraryName = tidalCatalog.libraryPlaylistName() ?: "library"
            addMessageLocked("${guest.name} hearted ${track.title} into $libraryName")
            publishLocked()
        }
    }

    suspend fun postMessage(guestId: String, text: String) = mutex.withLock {
        val guest = requireGuestLocked(guestId)
        val cleaned = text.trim().take(120)
        if (cleaned.isBlank()) {
            error("Message can't be empty")
        }
        addMessageLocked("${guest.name} says $cleaned")
        publishLocked()
    }

    suspend fun openLyrics(guestId: String) = mutex.withLock {
        requireGuestLocked(guestId)
        val opener = lyricsOpener
            ?: error("Enable accessibility in Settings to open Tidal lyrics")
        opener.openLyricsBestEffort()
    }

    suspend fun skip(guestId: String) = mutex.withLock {
        val guest = requireGuestLocked(guestId)
        addMessageLocked("${guest.name} skipped ahead")
        startNextLocked(skipCurrent = true)
    }

    suspend fun previous(guestId: String) = mutex.withLock {
        val guest = requireGuestLocked(guestId)
        val previous = queue.replayPrevious() ?: return@withLock
        addMessageLocked("${guest.name} went back to ${previous.track.title}")
        playItemLocked(previous)
        publishLocked()
    }

    suspend fun pause(guestId: String) = mutex.withLock {
        requireGuestLocked(guestId)
        bridge?.pause()
        isPlaying = false
        publishLocked()
    }

    suspend fun play(guestId: String) = mutex.withLock {
        requireGuestLocked(guestId)
        val restoredCurrent = queue.nowPlaying().takeIf { restoredQueueDormant }
        if (restoredCurrent != null) {
            playItemLocked(restoredCurrent)
        } else {
            bridge?.play()
            isPlaying = true
        }
        publishLocked()
    }

    suspend fun nextTransport(guestId: String) = mutex.withLock {
        val guest = requireGuestLocked(guestId)
        addMessageLocked("${guest.name} hit next")
        startNextLocked(skipCurrent = true)
    }

    suspend fun lyricsForNowPlaying(): LyricsResponse {
        val now = snapshot.value.nowPlaying.track ?: return LyricsResponse()
        return lrcLibClient.fetchLyrics(now)
    }

    suspend fun onTidalMetadata(
        trackId: String?,
        title: String?,
        artist: String?,
        positionMs: Long,
        playing: Boolean,
    ) = mutex.withLock {
        if (restoredQueueDormant) {
            this.positionMs = 0
            isPlaying = false
            publishLocked()
            return@withLock
        }
        val our = queue.nowPlaying()
        val launching = nowMs() < playLaunchUntilEpochMs
        val ownsTrack = trackId != null && trackId == our?.track?.tidalTrackId
        val historyTrackIds = queue.snapshotHistory().map { it.track.tidalTrackId }.toSet()

        // Stale MediaSession events from already-sung tracks must not overwrite
        // position or trigger reclaim / near-end against the wrong song.
        if (our != null && trackId != null && !ownsTrack && trackId in historyTrackIds) {
            return@withLock
        }

        if (ownsTrack || our == null || trackId == null) {
            this.positionMs = positionMs
            this.isPlaying = playing
        }

        if (our != null && !launching) {
            if (ownsTrack && shouldAdvanceNearEndLocked(our)) {
                nearEndAdvanceArmed = false
                bridge?.pause()
                startNextLocked(skipCurrent = true)
                return@withLock
            }

            if (trackId != null && !ownsTrack && trackId !in historyTrackIds) {
                // Tidal advanced on its own (play-next / radio / autoplay).
                if (lastObservedTidalTrackId != null && trackId != lastObservedTidalTrackId) {
                    bridge?.pause()
                    isPlaying = false
                    if (queue.snapshotQueue().isNotEmpty()) {
                        startNextLocked(skipCurrent = true)
                        return@withLock
                    }
                    lastObservedTidalTrackId = trackId
                    publishLocked()
                    return@withLock
                }
            }
        }

        if (!launching || trackId == null || ownsTrack) {
            lastObservedTidalTrackId = trackId ?: lastObservedTidalTrackId
        }

        if (our == null && !title.isNullOrBlank()) {
            // Reflect Tidal's current track when our queue has not started yet.
            _snapshot.value = _snapshot.value.copy(
                nowPlaying = NowPlaying(
                    track = TrackRef(
                        tidalTrackId = trackId.orEmpty(),
                        title = title,
                        artist = artist.orEmpty(),
                    ),
                    positionMs = positionMs,
                    isPlaying = playing,
                ),
                bridgeReady = bridgeReady,
            )
            _events.tryEmit(_snapshot.value)
            return@withLock
        }

        publishLocked()
    }

    suspend fun ensurePlayingFromQueue() = mutex.withLock {
        if (queue.nowPlaying() == null && queue.snapshotQueue().isNotEmpty()) {
            startNextLocked()
        }
    }

    private fun requireGuestLocked(guestId: String): Guest =
        guests[guestId] ?: error("Unknown guest")

    private fun shouldAdvanceNearEndLocked(our: QueueItem): Boolean {
        if (!nearEndAdvanceArmed) return false
        if (queue.snapshotQueue().isEmpty()) return false
        val durationMs = our.track.durationSeconds * 1000L
        if (durationMs <= 0L) return false
        val remaining = durationMs - positionMs
        if (remaining in 0..NEAR_END_WINDOW_MS) return true
        // Track ended / paused in the final window.
        if (!isPlaying && remaining in 0..(NEAR_END_WINDOW_MS + 1_000L)) return true
        return false
    }

    private suspend fun startNextLocked(skipCurrent: Boolean = false) {
        val next = if (skipCurrent) queue.skip() else {
            if (queue.nowPlaying() == null) queue.advance() else null
        }
        if (next != null) {
            playItemLocked(next)
        } else if (skipCurrent) {
            // Do not advance Tidal's own queue/radio — pause instead.
            bridge?.pause()
            isPlaying = false
        }
        publishLocked()
    }

    private suspend fun playItemLocked(item: QueueItem) {
        val bridge = bridge
        if (bridge == null) {
            addMessageLocked("Waiting for Tidal bridge before playing ${item.track.title}")
            return
        }
        restoredQueueDormant = false
        playLaunchUntilEpochMs = nowMs() + PLAY_LAUNCH_GRACE_MS
        nearEndAdvanceArmed = true
        val played = bridge.playTrack(item.track)
        if (!played) {
            addMessageLocked("Could not start ${item.track.title} on Tidal")
            playLaunchUntilEpochMs = 0
        } else {
            isPlaying = true
            positionMs = 0
            lastObservedTidalTrackId = item.track.tidalTrackId
            playLaunchUntilEpochMs = nowMs() + PLAY_LAUNCH_GRACE_MS
            lyricsOpener?.openLyricsBestEffort()
        }
    }

    private fun addMessageLocked(text: String) {
        messages.addLast(
            PartyMessage(
                id = UUID.randomUUID().toString(),
                text = text,
                createdAtEpochMs = System.currentTimeMillis(),
            ),
        )
        while (messages.size > 40) {
            messages.removeFirst()
        }
    }

    private fun buildSnapshotLocked(): PartySnapshot {
        val now = queue.nowPlaying()
        return PartySnapshot(
            guests = guests.values.toList(),
            queue = queue.snapshotQueue(),
            history = queue.snapshotHistory(),
            nowPlaying = NowPlaying(
                track = now?.track,
                addedByName = now?.addedByName,
                positionMs = positionMs,
                isPlaying = isPlaying,
            ),
            messages = messages.toList().asReversed(),
            bridgeReady = bridgeReady,
            tidalConfigured = tidalCatalog.isConfigured(),
            libraryConfigured = tidalCatalog.isLibraryConfigured(),
            libraryPlaylistName = tidalCatalog.libraryPlaylistName(),
            libraryTrackIds = tidalCatalog.cachedLibraryTrackIds().toList(),
        )
    }

    private fun publishLocked() {
        val snap = buildSnapshotLocked()
        queuePersistence?.save(queue.snapshotForPersistence())
        _snapshot.value = snap
        _events.tryEmit(snap)
    }

    companion object {
        private const val NEAR_END_WINDOW_MS = 2_500L
        private const val PLAY_LAUNCH_GRACE_MS = 4_000L
    }
}
