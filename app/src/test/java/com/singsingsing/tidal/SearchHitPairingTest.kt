package com.singsingsing.tidal

import com.google.common.truth.Truth.assertThat
import com.singsingsing.party.MEDIA_TYPE_TRACK
import com.singsingsing.party.MEDIA_TYPE_VIDEO
import com.singsingsing.party.TrackRef
import org.junit.Test

class SearchHitPairingTest {
    @Test
    fun pairsByNormalizedTitleAndArtistId() {
        val track = TrackRef(
            tidalTrackId = "t1",
            title = "Wolf Like Me",
            artist = "TV On The Radio",
            artistId = "a1",
            mediaType = MEDIA_TYPE_TRACK,
        )
        val video = TrackRef(
            tidalTrackId = "v1",
            title = "Wolf Like Me (Official Video)",
            artist = "TV On The Radio",
            artistId = "a1",
            mediaType = MEDIA_TYPE_VIDEO,
        )

        val hits = pairSearchHits(listOf(track), listOf(video))

        assertThat(hits).hasSize(1)
        assertThat(hits[0].song?.tidalTrackId).isEqualTo("t1")
        assertThat(hits[0].video?.tidalTrackId).isEqualTo("v1")
        assertThat(hits[0].video?.mediaType).isEqualTo(MEDIA_TYPE_VIDEO)
    }

    @Test
    fun pairsByArtistNameWhenArtistIdsMissing() {
        val track = TrackRef("t1", "Hello", "Adele")
        val video = TrackRef("v1", "Hello - Official Music Video", "Adele", mediaType = MEDIA_TYPE_VIDEO)

        val hits = pairSearchHits(listOf(track), listOf(video))

        assertThat(hits).hasSize(1)
        assertThat(hits[0].song?.tidalTrackId).isEqualTo("t1")
        assertThat(hits[0].video?.tidalTrackId).isEqualTo("v1")
    }

    @Test
    fun doesNotPairDifferentArtistIds() {
        val track = TrackRef("t1", "Hello", "Adele", artistId = "1")
        val video = TrackRef("v1", "Hello (Official Video)", "Other", artistId = "2", mediaType = MEDIA_TYPE_VIDEO)

        val hits = pairSearchHits(listOf(track), listOf(video))

        assertThat(hits).hasSize(2)
        assertThat(hits[0].song?.tidalTrackId).isEqualTo("t1")
        assertThat(hits[0].video).isNull()
        assertThat(hits[1].song).isNull()
        assertThat(hits[1].video?.tidalTrackId).isEqualTo("v1")
    }

    @Test
    fun leftoverVideosBecomeVideoOnlyHits() {
        val track = TrackRef("t1", "Song A", "Artist")
        val unmatched = TrackRef("v2", "Other Clip", "Artist", mediaType = MEDIA_TYPE_VIDEO)

        val hits = pairSearchHits(listOf(track), listOf(unmatched))

        assertThat(hits).hasSize(2)
        assertThat(hits[1].song).isNull()
        assertThat(hits[1].video?.tidalTrackId).isEqualTo("v2")
    }

    @Test
    fun normalizeMediaTitleStripsCommonVideoSuffixes() {
        assertThat(normalizeMediaTitle("Song (Official Video)")).isEqualTo("song")
        assertThat(normalizeMediaTitle("Song - Lyric Video")).isEqualTo("song")
        assertThat(normalizeMediaTitle("Song (Official Audio)")).isEqualTo("song")
        assertThat(normalizeMediaTitle("Song [Music Video]")).isEqualTo("song")
    }
}
