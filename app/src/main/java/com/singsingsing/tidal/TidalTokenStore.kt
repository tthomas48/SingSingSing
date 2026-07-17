package com.singsingsing.tidal

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists Tidal user OAuth tokens and the selected karaoke library playlist.
 */
class TidalTokenStore internal constructor(
    private val prefs: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    fun hasUserSession(): Boolean = !refreshToken().isNullOrBlank() || !accessToken().isNullOrBlank()

    fun accessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)

    fun refreshToken(): String? = prefs.getString(KEY_REFRESH_TOKEN, null)

    fun accessExpiresAtEpochMs(): Long = prefs.getLong(KEY_ACCESS_EXPIRES_AT, 0L)

    fun userId(): String? = prefs.getString(KEY_USER_ID, null)

    fun libraryPlaylistId(): String? = prefs.getString(KEY_LIBRARY_PLAYLIST_ID, null)

    fun libraryPlaylistName(): String? = prefs.getString(KEY_LIBRARY_PLAYLIST_NAME, null)

    fun isLibraryConfigured(): Boolean = !libraryPlaylistId().isNullOrBlank()

    fun saveUserTokens(
        accessToken: String,
        refreshToken: String?,
        expiresInSeconds: Long,
        userId: String? = null,
    ) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putLong(KEY_ACCESS_EXPIRES_AT, System.currentTimeMillis() + expiresInSeconds * 1000L)
            .apply {
                if (!refreshToken.isNullOrBlank()) {
                    putString(KEY_REFRESH_TOKEN, refreshToken)
                }
                if (!userId.isNullOrBlank()) {
                    putString(KEY_USER_ID, userId)
                }
            }
            .apply()
    }

    fun saveUserId(userId: String) {
        prefs.edit().putString(KEY_USER_ID, userId).apply()
    }

    fun saveLibraryPlaylist(id: String, name: String) {
        prefs.edit()
            .putString(KEY_LIBRARY_PLAYLIST_ID, id)
            .putString(KEY_LIBRARY_PLAYLIST_NAME, name)
            .apply()
    }

    fun clearUserSession() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_ACCESS_EXPIRES_AT)
            .remove(KEY_USER_ID)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "tidal_user_auth"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_ACCESS_EXPIRES_AT = "access_expires_at"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_LIBRARY_PLAYLIST_ID = "library_playlist_id"
        private const val KEY_LIBRARY_PLAYLIST_NAME = "library_playlist_name"
    }
}
