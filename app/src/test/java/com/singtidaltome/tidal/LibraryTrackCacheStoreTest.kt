package com.singtidaltome.tidal

import com.google.common.truth.Truth.assertThat
import com.singtidaltome.party.TrackRef
import org.junit.Test

class LibraryTrackCacheStoreTest {
    @Test
    fun codecRoundTripsTracksForPlaylist() {
        val persisted = PersistedLibraryTracks(
            playlistId = "playlist-1",
            tracks = listOf(
                TrackRef(
                    tidalTrackId = "track-1",
                    title = "Song",
                    artist = "Singer",
                    album = "Album",
                    durationSeconds = 123,
                    artistId = "artist-1",
                ),
            ),
        )

        val restored = LibraryTrackCacheStore.decode(LibraryTrackCacheStore.encode(persisted))

        assertThat(restored).isEqualTo(persisted)
    }

    @Test
    fun corruptLibraryDataIsIgnored() {
        assertThat(LibraryTrackCacheStore.decode("not-json")).isNull()
    }

    @Test
    fun inMemoryStoreLoadsMatchingPlaylistAndMissesOthers() {
        val store = InMemoryLibraryTrackCache()
        val tracks = listOf(
            TrackRef(tidalTrackId = "1", title = "A", artist = "B"),
        )

        store.save("playlist-a", tracks)

        assertThat(store.load("playlist-a")).isEqualTo(tracks)
        assertThat(store.load("playlist-b")).isNull()
    }

    @Test
    fun clearRemovesPersistedTracks() {
        val store = InMemoryLibraryTrackCache()
        store.save("playlist-a", listOf(TrackRef(tidalTrackId = "1", title = "A", artist = "B")))

        store.clear()

        assertThat(store.load("playlist-a")).isNull()
    }

    private class InMemoryLibraryTrackCache : LibraryTrackCachePersistence {
        private var payload: PersistedLibraryTracks? = null

        override fun load(playlistId: String): List<TrackRef>? {
            val current = payload ?: return null
            if (current.playlistId != playlistId) return null
            return current.tracks
        }

        override fun save(playlistId: String, tracks: List<TrackRef>) {
            payload = PersistedLibraryTracks(playlistId, tracks)
        }

        override fun clear() {
            payload = null
        }
    }
}
