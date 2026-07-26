package com.singsingsing.party

import kotlinx.serialization.Serializable

@Serializable
data class Guest(
    val id: String,
    val name: String,
)

@Serializable
data class TrackRef(
    val tidalTrackId: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationSeconds: Int = 0,
    val artworkUrl: String? = null,
    val artistId: String? = null,
    /** `"track"` (audio) or `"video"` (music video). */
    val mediaType: String = MEDIA_TYPE_TRACK,
) {
    val isVideo: Boolean get() = mediaType == MEDIA_TYPE_VIDEO
}

const val MEDIA_TYPE_TRACK = "track"
const val MEDIA_TYPE_VIDEO = "video"

@Serializable
data class SearchHit(
    val title: String,
    val artist: String,
    val album: String = "",
    val artworkUrl: String? = null,
    val artistId: String? = null,
    val song: TrackRef? = null,
    val video: TrackRef? = null,
)

@Serializable
data class QueueItem(
    val id: String,
    val track: TrackRef,
    val addedByGuestId: String,
    val addedByName: String,
)

@Serializable
data class PartyMessage(
    val id: String,
    val text: String,
    val createdAtEpochMs: Long,
)

@Serializable
data class NowPlaying(
    val track: TrackRef? = null,
    val addedByName: String? = null,
    val positionMs: Long = 0,
    val isPlaying: Boolean = false,
)

@Serializable
data class PartySnapshot(
    val guests: List<Guest>,
    val queue: List<QueueItem>,
    val history: List<QueueItem> = emptyList(),
    val nowPlaying: NowPlaying,
    val messages: List<PartyMessage>,
    val bridgeReady: Boolean,
    val tidalConfigured: Boolean,
    val libraryConfigured: Boolean = false,
    val libraryPlaylistName: String? = null,
    val libraryTrackIds: List<String> = emptyList(),
)

@Serializable
data class JoinRequest(
    val name: String,
)

@Serializable
data class JoinResponse(
    val guest: Guest,
    val snapshot: PartySnapshot,
)

@Serializable
data class AddTrackRequest(
    val guestId: String,
    val track: TrackRef,
)

@Serializable
data class GuestActionRequest(
    val guestId: String,
)

@Serializable
data class PostMessageRequest(
    val guestId: String,
    val text: String,
)

@Serializable
data class SearchRequest(
    val query: String,
)

@Serializable
data class SearchResponse(
    val results: List<SearchHit>,
)

@Serializable
data class ReorderQueueRequest(
    val guestId: String,
    val itemId: String,
    val toIndex: Int,
)

@Serializable
data class PlayQueueItemRequest(
    val guestId: String,
    val itemId: String,
)

@Serializable
data class FavoriteTrackRequest(
    val guestId: String,
    val track: TrackRef,
)

@Serializable
data class LibraryResponse(
    val tracks: List<TrackRef>,
    val playlistName: String? = null,
    val configured: Boolean = false,
)

@Serializable
data class PlaylistSummary(
    val id: String,
    val name: String,
    val numberOfItems: Int = 0,
)

@Serializable
data class LyricsLine(
    val timeMs: Long,
    val text: String,
)

@Serializable
data class LyricsResponse(
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
    val instrumental: Boolean = false,
    val lines: List<LyricsLine> = emptyList(),
)
