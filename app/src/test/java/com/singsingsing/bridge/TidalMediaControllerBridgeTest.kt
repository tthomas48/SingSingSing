package com.singsingsing.bridge

import com.google.common.truth.Truth.assertThat
import com.singsingsing.party.MEDIA_TYPE_TRACK
import com.singsingsing.party.MEDIA_TYPE_VIDEO
import com.singsingsing.party.TrackRef
import org.junit.Test

class TidalMediaControllerBridgeTest {
    @Test
    fun playUriStringsUseTrackPathsForAudio() {
        val track = TrackRef(
            tidalTrackId = "123",
            title = "Song",
            artist = "Artist",
            mediaType = MEDIA_TYPE_TRACK,
        )
        assertThat(TidalMediaControllerBridge.playUriStrings(track)).containsExactly(
            "tidal://track/123",
            "https://tidal.com/browse/track/123",
            "https://tidal.com/track/123",
        ).inOrder()
    }

    @Test
    fun playUriStringsUseVideoPathsForVideos() {
        val video = TrackRef(
            tidalTrackId = "456",
            title = "Song",
            artist = "Artist",
            mediaType = MEDIA_TYPE_VIDEO,
        )
        assertThat(TidalMediaControllerBridge.playUriStrings(video)).containsExactly(
            "tidal://video/456",
            "https://tidal.com/browse/video/456",
            "https://tidal.com/video/456",
        ).inOrder()
    }
}
