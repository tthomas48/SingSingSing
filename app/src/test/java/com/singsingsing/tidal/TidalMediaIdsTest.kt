package com.singsingsing.tidal

import com.google.common.truth.Truth.assertThat
import com.singsingsing.party.TrackRef
import org.junit.Test

class TidalMediaIdsTest {
    @Test
    fun normalizeStripsUriAndCountrySuffix() {
        assertThat(TidalMediaIds.normalize("73416054")).isEqualTo("73416054")
        assertThat(TidalMediaIds.normalize("73416054-US")).isEqualTo("73416054")
        assertThat(TidalMediaIds.normalize("tidal://track/73416054")).isEqualTo("73416054")
        assertThat(TidalMediaIds.normalize("https://tidal.com/browse/track/73416054-GB"))
            .isEqualTo("73416054")
        assertThat(TidalMediaIds.normalize(null)).isEmpty()
        assertThat(TidalMediaIds.normalize("")).isEmpty()
    }

    @Test
    fun sameIdTreatsCatalogAndSessionFormsAsEqual() {
        assertThat(TidalMediaIds.sameId("73416054", "73416054-US")).isTrue()
        assertThat(TidalMediaIds.sameId("tidal://track/35778985", "35778985")).isTrue()
        assertThat(TidalMediaIds.sameId("73416054", "35778985")).isFalse()
        assertThat(TidalMediaIds.sameId(null, "73416054")).isFalse()
    }

    @Test
    fun matchesNowPlayingUsesNormalizedIdOrTitle() {
        val track = TrackRef(
            tidalTrackId = "35778985",
            title = "Dishes",
            artist = "Pulp",
        )
        assertThat(TidalMediaIds.matchesNowPlaying(track, "35778985-US", "Other", "X")).isTrue()
        assertThat(
            TidalMediaIds.matchesNowPlaying(track, "session-other", "Dishes", "Pulp"),
        ).isTrue()
        assertThat(
            TidalMediaIds.matchesNowPlaying(track, "session-other", "Radio filler", "Tidal"),
        ).isFalse()
    }

    @Test
    fun officialVideoTitleMatchesQueuedSongTitle() {
        val video = TrackRef(
            tidalTrackId = "v1",
            title = "Wolf Like Me",
            artist = "TV On The Radio",
        )
        assertThat(
            TidalMediaIds.matchesNowPlaying(
                video,
                "other-id",
                "Wolf Like Me (Official Video)",
                "TV On The Radio",
            ),
        ).isTrue()
        assertThat(
            TidalMediaIds.metadataMatches(
                video,
                "Wolf Like Me - Official Music Video",
                "TV On The Radio",
            ),
        ).isTrue()
    }
}
