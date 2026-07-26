package com.singsingsing.party

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class TrackRefSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun mediaTypeDefaultsToTrackWhenMissing() {
        val decoded = json.decodeFromString(
            TrackRef.serializer(),
            """{"tidalTrackId":"1","title":"A","artist":"B"}""",
        )
        assertThat(decoded.mediaType).isEqualTo(MEDIA_TYPE_TRACK)
        assertThat(decoded.isVideo).isFalse()
    }

    @Test
    fun searchHitRoundTripsSongAndVideo() {
        val hit = SearchHit(
            title = "Song",
            artist = "Artist",
            song = TrackRef("t1", "Song", "Artist"),
            video = TrackRef("v1", "Song", "Artist", mediaType = MEDIA_TYPE_VIDEO),
        )
        val encoded = json.encodeToString(SearchHit.serializer(), hit)
        val decoded = json.decodeFromString(SearchHit.serializer(), encoded)
        assertThat(decoded.song?.tidalTrackId).isEqualTo("t1")
        assertThat(decoded.video?.mediaType).isEqualTo(MEDIA_TYPE_VIDEO)
    }
}
