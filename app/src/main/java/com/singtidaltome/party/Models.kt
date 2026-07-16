package com.singtidaltome.party

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
    val nowPlaying: NowPlaying,
    val messages: List<PartyMessage>,
    val bridgeReady: Boolean,
    val tidalConfigured: Boolean,
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
data class SearchRequest(
    val query: String,
)

@Serializable
data class SearchResponse(
    val tracks: List<TrackRef>,
)

@Serializable
data class LyricsResponse(
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
    val instrumental: Boolean = false,
)
