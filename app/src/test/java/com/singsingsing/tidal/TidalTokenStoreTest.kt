package com.singsingsing.tidal

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TidalTokenStoreTest {
    @Test
    fun tokensAndLibraryRoundTripAcrossStoreInstances() {
        val prefs = FakeSharedPreferences()
        val first = TidalTokenStore(prefs)
        first.saveUserTokens(
            accessToken = "access",
            refreshToken = "refresh",
            expiresInSeconds = 3_600,
            userId = "user-1",
        )
        first.saveLibraryPlaylist("playlist-1", "Sing along")

        val restored = TidalTokenStore(prefs)

        assertThat(restored.hasUserSession()).isTrue()
        assertThat(restored.accessToken()).isEqualTo("access")
        assertThat(restored.refreshToken()).isEqualTo("refresh")
        assertThat(restored.accessExpiresAtEpochMs()).isGreaterThan(System.currentTimeMillis())
        assertThat(restored.userId()).isEqualTo("user-1")
        assertThat(restored.libraryPlaylistId()).isEqualTo("playlist-1")
        assertThat(restored.libraryPlaylistName()).isEqualTo("Sing along")
    }

    private class FakeSharedPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()

        override fun getString(key: String?, defValue: String?): String? =
            values[key] as? String ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as? Set<String>)?.toMutableSet() ?: defValues

        override fun getInt(key: String?, defValue: Int): Int =
            values[key] as? Int ?: defValue

        override fun getLong(key: String?, defValue: Long): Long =
            values[key] as? Long ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float =
            values[key] as? Float ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            values[key] as? Boolean ?: defValue

        override fun contains(key: String?): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        private inner class Editor : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?): SharedPreferences.Editor =
                apply { if (key != null) values[key] = value }

            override fun putStringSet(
                key: String?,
                values: MutableSet<String>?,
            ): SharedPreferences.Editor = apply {
                if (key != null) this@FakeSharedPreferences.values[key] = values?.toSet()
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor =
                apply { if (key != null) values[key] = value }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor =
                apply { if (key != null) values[key] = value }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor =
                apply { if (key != null) values[key] = value }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor =
                apply { if (key != null) values[key] = value }

            override fun remove(key: String?): SharedPreferences.Editor =
                apply { if (key != null) values.remove(key) }

            override fun clear(): SharedPreferences.Editor =
                apply { values.clear() }

            override fun commit(): Boolean = true

            override fun apply() = Unit
        }
    }
}
