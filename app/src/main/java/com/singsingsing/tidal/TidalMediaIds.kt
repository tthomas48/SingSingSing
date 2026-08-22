package com.singsingsing.tidal

import com.singsingsing.party.TrackRef

/**
 * Tidal catalog ids and MediaSession media ids are the same resource with different
 * wire forms (`73416054`, `73416054-US`, `tidal://track/73416054`).
 */
object TidalMediaIds {
    fun normalize(id: String?): String {
        if (id.isNullOrBlank()) return ""
        var value = id.trim()
        val slash = value.lastIndexOf('/')
        if (slash >= 0 && slash < value.lastIndex) {
            value = value.substring(slash + 1)
        }
        val dash = value.lastIndexOf('-')
        if (dash > 0) {
            val suffix = value.substring(dash + 1)
            if (suffix.length == 2 && suffix.all { it.isLetter() }) {
                value = value.substring(0, dash)
            }
        }
        return value
    }

    fun sameId(left: String?, right: String?): Boolean {
        val a = normalize(left)
        val b = normalize(right)
        return a.isNotBlank() && a == b
    }

    fun metadataMatches(track: TrackRef, title: String?, artist: String?): Boolean {
        if (title.isNullOrBlank()) return false
        if (title.equals(track.title, ignoreCase = true)) return true
        val normalizedTitle = normalizeMediaTitle(title)
        val normalizedTrackTitle = normalizeMediaTitle(track.title)
        val titlesMatch = normalizedTitle.isNotBlank() && normalizedTitle == normalizedTrackTitle
        val artistNeedle = track.artist.substringBefore(",").trim()
        val artistOk = when {
            artistNeedle.isBlank() -> true
            artist.isNullOrBlank() -> titlesMatch
            else ->
                artist.contains(artistNeedle, ignoreCase = true) ||
                    normalizeArtistName(artist).contains(normalizeArtistName(artistNeedle))
        }
        if (titlesMatch && artistOk) return true
        if (artistNeedle.isBlank() || artist.isNullOrBlank()) return false
        return artist.contains(artistNeedle, ignoreCase = true) &&
            title.contains(track.title.take(12), ignoreCase = true)
    }

    fun matchesNowPlaying(track: TrackRef, mediaId: String?, title: String?, artist: String?): Boolean =
        sameId(mediaId, track.tidalTrackId) || metadataMatches(track, title, artist)
}
