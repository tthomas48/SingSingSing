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

    suspend fun addTrack(guestId: String, track: TrackRef): QueueItem = mutex.withLock {
        val guest = guests[guestId] ?: error("Unknown guest")
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
        bridge?.play()
        isPlaying = true
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
        this.positionMs = positionMs
        this.isPlaying = playing

        val our = queue.nowPlaying()
        if (our != null && trackId != null && trackId != our.track.tidalTrackId) {
            // Tidal advanced on its own; reclaim control with our next track if we have one.
            if (lastObservedTidalTrackId != null && trackId != lastObservedTidalTrackId) {
                if (queue.snapshotQueue().isNotEmpty()) {
                    startNextLocked(skipCurrent = true)
                }
            }
        }
        lastObservedTidalTrackId = trackId ?: lastObservedTidalTrackId

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

    private suspend fun startNextLocked(skipCurrent: Boolean = false) {
        val next = if (skipCurrent) queue.skip() else {
            if (queue.nowPlaying() == null) queue.advance() else null
        }
        if (next != null) {
            playItemLocked(next)
        } else if (skipCurrent) {
            bridge?.skipToNext()
        }
        publishLocked()
    }

    private suspend fun playItemLocked(item: QueueItem) {
        val bridge = bridge
        if (bridge == null) {
            addMessageLocked("Waiting for Tidal bridge before playing ${item.track.title}")
            return
        }
        val played = bridge.playTrack(item.track)
        if (!played) {
            addMessageLocked("Could not start ${item.track.title} on Tidal")
        } else {
            isPlaying = true
            positionMs = 0
            lastObservedTidalTrackId = item.track.tidalTrackId
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
            nowPlaying = NowPlaying(
                track = now?.track,
                addedByName = now?.addedByName,
                positionMs = positionMs,
                isPlaying = isPlaying,
            ),
            messages = messages.toList().asReversed(),
            bridgeReady = bridgeReady,
            tidalConfigured = tidalCatalog.isConfigured(),
        )
    }

    private fun publishLocked() {
        val snap = buildSnapshotLocked()
        _snapshot.value = snap
        _events.tryEmit(snap)
    }
}
