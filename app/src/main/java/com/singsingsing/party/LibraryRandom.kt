package com.singsingsing.party

import kotlin.random.Random

object LibraryRandom {
    const val MIN_COUNT = 1
    const val MAX_COUNT = 20
    const val DEFAULT_COUNT = 5

    fun clampCount(count: Int): Int = count.coerceIn(MIN_COUNT, MAX_COUNT)

    /**
     * Picks up to [count] library tracks, excluding [excludeIds].
     *
     * First pass takes at most one track per artist (case-insensitive trimmed name).
     * If there are not enough distinct artists, leftover eligible tracks fill the rest.
     */
    fun selectRandomLibraryTracks(
        tracks: List<TrackRef>,
        excludeIds: Set<String>,
        count: Int,
        random: Random,
    ): List<TrackRef> {
        val wanted = clampCount(count)
        val eligible = tracks.filter { track ->
            track.tidalTrackId.isNotBlank() && track.tidalTrackId !in excludeIds
        }
        if (eligible.isEmpty()) return emptyList()

        val shuffled = eligible.shuffled(random)
        val picked = mutableListOf<TrackRef>()
        val pickedIds = mutableSetOf<String>()
        val usedArtists = mutableSetOf<String>()
        val leftover = mutableListOf<TrackRef>()

        for (track in shuffled) {
            if (picked.size >= wanted) break
            if (track.tidalTrackId in pickedIds) continue
            val key = artistKey(track.artist)
            if (key.isNotEmpty() && key in usedArtists) {
                leftover += track
                continue
            }
            picked += track
            pickedIds += track.tidalTrackId
            if (key.isNotEmpty()) usedArtists += key
        }

        if (picked.size < wanted) {
            for (track in leftover) {
                if (picked.size >= wanted) break
                if (track.tidalTrackId in pickedIds) continue
                picked += track
                pickedIds += track.tidalTrackId
            }
        }
        return picked
    }

    fun artistKey(artist: String): String = artist.trim().lowercase()
}
