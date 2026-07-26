package com.singsingsing.tidal

import com.singsingsing.party.MEDIA_TYPE_TRACK
import com.singsingsing.party.MEDIA_TYPE_VIDEO
import com.singsingsing.party.SearchHit
import com.singsingsing.party.TrackRef

/**
 * Pairs catalog tracks with music videos for merged search rows.
 *
 * Matching uses normalized title + artistId (preferred) or normalized artist name.
 * At most one video is attached per track; leftover videos become video-only hits.
 */
fun pairSearchHits(tracks: List<TrackRef>, videos: List<TrackRef>): List<SearchHit> {
    val usedVideoIds = mutableSetOf<String>()
    val hits = ArrayList<SearchHit>(tracks.size + videos.size)

    for (track in tracks) {
        val song = track.copy(mediaType = MEDIA_TYPE_TRACK)
        val video = videos.firstOrNull { candidate ->
            candidate.tidalTrackId !in usedVideoIds && mediaTitlesMatch(song, candidate)
        }?.copy(mediaType = MEDIA_TYPE_VIDEO)
        if (video != null) {
            usedVideoIds += video.tidalTrackId
        }
        hits += SearchHit(
            title = song.title,
            artist = song.artist,
            album = song.album,
            artworkUrl = song.artworkUrl ?: video?.artworkUrl,
            artistId = song.artistId ?: video?.artistId,
            song = song,
            video = video,
        )
    }

    for (video in videos) {
        if (video.tidalTrackId in usedVideoIds) continue
        val videoRef = video.copy(mediaType = MEDIA_TYPE_VIDEO)
        hits += SearchHit(
            title = videoRef.title,
            artist = videoRef.artist,
            album = videoRef.album,
            artworkUrl = videoRef.artworkUrl,
            artistId = videoRef.artistId,
            song = null,
            video = videoRef,
        )
    }

    return hits
}

internal fun mediaTitlesMatch(track: TrackRef, video: TrackRef): Boolean {
    if (normalizeMediaTitle(track.title) != normalizeMediaTitle(video.title)) {
        return false
    }
    val trackArtistId = track.artistId
    val videoArtistId = video.artistId
    if (!trackArtistId.isNullOrBlank() && !videoArtistId.isNullOrBlank()) {
        return trackArtistId == videoArtistId
    }
    return normalizeArtistName(track.artist) == normalizeArtistName(video.artist)
}

internal fun normalizeMediaTitle(title: String): String {
    var text = title.lowercase().trim()
    text = PARENTHETICAL_VIDEO_SUFFIX.replace(text, "")
    text = TRAILING_VIDEO_SUFFIX.replace(text, "")
    return text
        .replace(NON_ALNUM, "")
        .replace(MULTI_SPACE, " ")
        .trim()
}

internal fun normalizeArtistName(artist: String): String =
    artist.lowercase()
        .replace(NON_ALNUM, "")
        .replace(MULTI_SPACE, " ")
        .trim()

private val PARENTHETICAL_VIDEO_SUFFIX = Regex(
    """\s*[\(\[\{][^\)\]\}]*?(?:official\s+)?(?:music\s+)?video[^\)\]\}]*[\)\]\}]""" +
        """|\s*[\(\[\{][^\)\]\}]*?lyric\s*video[^\)\]\}]*[\)\]\}]""" +
        """|\s*[\(\[\{][^\)\]\}]*?official\s+audio[^\)\]\}]*[\)\]\}]""",
    RegexOption.IGNORE_CASE,
)

private val TRAILING_VIDEO_SUFFIX = Regex(
    """\s*[-–—:]\s*(?:official\s+)?(?:music\s+)?video\s*$""" +
        """|\s*[-–—:]\s*lyric\s*video\s*$""" +
        """|\s*[-–—:]\s*official\s+audio\s*$""",
    RegexOption.IGNORE_CASE,
)

private val NON_ALNUM = Regex("""[^\p{L}\p{N}\s]""")
private val MULTI_SPACE = Regex("""\s+""")
