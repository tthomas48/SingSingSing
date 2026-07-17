package com.singsingsing.tidal

import android.content.Context
import android.content.SharedPreferences
import com.singsingsing.party.TrackRef
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PersistedLibraryTracks(
    val playlistId: String,
    val tracks: List<TrackRef>,
)

interface LibraryTrackCachePersistence {
    fun load(playlistId: String): List<TrackRef>?
    fun save(playlistId: String, tracks: List<TrackRef>)
    fun clear()
}

/** Persists karaoke library tracks across process death. */
class LibraryTrackCacheStore(context: Context) : LibraryTrackCachePersistence {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(playlistId: String): List<TrackRef>? {
        val encoded = prefs.getString(KEY_LIBRARY, null) ?: return null
        val persisted = decode(encoded) ?: return null
        if (persisted.playlistId != playlistId) return null
        return persisted.tracks
    }

    override fun save(playlistId: String, tracks: List<TrackRef>) {
        prefs.edit()
            .putString(KEY_LIBRARY, encode(PersistedLibraryTracks(playlistId, tracks)))
            .apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_LIBRARY).apply()
    }

    companion object {
        private const val PREFS_NAME = "library_track_cache"
        private const val KEY_LIBRARY = "library_tracks"
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        internal fun encode(value: PersistedLibraryTracks): String =
            json.encodeToString(PersistedLibraryTracks.serializer(), value)

        internal fun decode(encoded: String): PersistedLibraryTracks? =
            runCatching {
                json.decodeFromString(PersistedLibraryTracks.serializer(), encoded)
            }.getOrNull()
    }
}
