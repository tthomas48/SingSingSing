package com.singsingsing.tidal

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.singsingsing.party.TrackRef
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

class TidalCatalogClientTest {
    @Test
    fun parseSearchMapsOrderedTracksAndIgnoresJunkIncludedTracks() {
        val client = TidalCatalogClient(
            authClient = TidalAuthClient("", ""),
            countryCode = "US",
        )
        val response = SearchApiResponse(
            data = listOf(
                SearchData(
                    id = "wolf",
                    type = "searchResults",
                    relationships = ResourceRelationships(
                        tracks = RelationshipList(
                            listOf(
                                RelationshipRef("95574931", "tracks"),
                                RelationshipRef("2", "tracks"),
                            ),
                        ),
                    ),
                ),
            ),
            included = listOf(
                IncludedResource(
                    id = "95574931",
                    type = "tracks",
                    attributes = ResourceAttributes(
                        title = "Wolf Like Me",
                        duration = JsonPrimitive(201.0),
                    ),
                    relationships = ResourceRelationships(
                        artists = RelationshipList(listOf(RelationshipRef("1", "artists"))),
                        albums = RelationshipList(listOf(RelationshipRef("9", "albums"))),
                    ),
                ),
                IncludedResource(
                    id = "2",
                    type = "tracks",
                    attributes = ResourceAttributes(title = "Other Song"),
                    relationships = ResourceRelationships(
                        artists = RelationshipList(listOf(RelationshipRef("1", "artists"))),
                    ),
                ),
                // Album-related junk that must not appear unless listed in relationships.tracks
                IncludedResource(
                    id = "999",
                    type = "tracks",
                    attributes = ResourceAttributes(title = "Album Filler"),
                    relationships = ResourceRelationships(
                        artists = RelationshipList(listOf(RelationshipRef("1", "artists"))),
                    ),
                ),
                IncludedResource(
                    id = "1",
                    type = "artists",
                    attributes = ResourceAttributes(name = "TV On The Radio"),
                ),
                IncludedResource(
                    id = "9",
                    type = "albums",
                    attributes = ResourceAttributes(
                        title = "Return To Cookie Mountain",
                        imageLinks = listOf(ImageLink(href = "https://example.com/art.jpg")),
                    ),
                ),
            ),
        )

        val tracks = client.parseSearch(response)
        assertThat(tracks.map { it.tidalTrackId }).containsExactly("95574931", "2").inOrder()
        assertThat(tracks[0].title).isEqualTo("Wolf Like Me")
        assertThat(tracks[0].artist).isEqualTo("TV On The Radio")
        assertThat(tracks[0].artistId).isEqualTo("1")
        assertThat(tracks[0].album).isEqualTo("Return To Cookie Mountain")
        assertThat(tracks[0].artworkUrl).isEqualTo("https://example.com/art.jpg")
        assertThat(tracks[0].durationSeconds).isEqualTo(201)
        assertThat(tracks.map { it.title }).doesNotContain("Album Filler")
    }

    @Test
    fun parsePlaylistItemsPreservesRelationshipOrder() {
        val client = TidalCatalogClient(
            authClient = TidalAuthClient("", ""),
            countryCode = "US",
        )
        val response = RelationshipItemsResponse(
            data = listOf(
                RelationshipRef("2", "tracks"),
                RelationshipRef("1", "tracks"),
            ),
            included = listOf(
                IncludedResource(
                    id = "1",
                    type = "tracks",
                    attributes = ResourceAttributes(title = "First"),
                    relationships = ResourceRelationships(
                        artists = RelationshipList(listOf(RelationshipRef("a", "artists"))),
                    ),
                ),
                IncludedResource(
                    id = "2",
                    type = "tracks",
                    attributes = ResourceAttributes(title = "Second"),
                    relationships = ResourceRelationships(
                        artists = RelationshipList(listOf(RelationshipRef("a", "artists"))),
                    ),
                ),
                IncludedResource(
                    id = "a",
                    type = "artists",
                    attributes = ResourceAttributes(name = "Band"),
                ),
            ),
        )

        val tracks = client.parsePlaylistItems(response)
        assertThat(tracks.map { it.tidalTrackId }).containsExactly("2", "1").inOrder()
        assertThat(tracks.map { it.title }).containsExactly("Second", "First").inOrder()
    }

    @Test
    fun parseSearchHitsPairsVideosAndLeavesUnmatchedVideos() {
        val client = TidalCatalogClient(
            authClient = TidalAuthClient("", ""),
            countryCode = "US",
        )
        val response = SearchApiResponse(
            data = listOf(
                SearchData(
                    id = "wolf",
                    type = "searchResults",
                    relationships = ResourceRelationships(
                        tracks = RelationshipList(
                            listOf(RelationshipRef("95574931", "tracks")),
                        ),
                        videos = RelationshipList(
                            listOf(
                                RelationshipRef("v1", "videos"),
                                RelationshipRef("v2", "videos"),
                            ),
                        ),
                    ),
                ),
            ),
            included = listOf(
                IncludedResource(
                    id = "95574931",
                    type = "tracks",
                    attributes = ResourceAttributes(title = "Wolf Like Me"),
                    relationships = ResourceRelationships(
                        artists = RelationshipList(listOf(RelationshipRef("1", "artists"))),
                    ),
                ),
                IncludedResource(
                    id = "v1",
                    type = "videos",
                    attributes = ResourceAttributes(title = "Wolf Like Me (Official Video)"),
                    relationships = ResourceRelationships(
                        artists = RelationshipList(listOf(RelationshipRef("1", "artists"))),
                        thumbnailArt = RelationshipList(listOf(RelationshipRef("art1", "artworks"))),
                    ),
                ),
                IncludedResource(
                    id = "v2",
                    type = "videos",
                    attributes = ResourceAttributes(title = "Other Clip"),
                    relationships = ResourceRelationships(
                        artists = RelationshipList(listOf(RelationshipRef("1", "artists"))),
                    ),
                ),
                IncludedResource(
                    id = "1",
                    type = "artists",
                    attributes = ResourceAttributes(name = "TV On The Radio"),
                ),
                IncludedResource(
                    id = "art1",
                    type = "artworks",
                    attributes = ResourceAttributes(
                        imageLinks = listOf(ImageLink(href = "https://example.com/video.jpg")),
                    ),
                ),
            ),
        )

        val hits = client.parseSearchHits(response)
        assertThat(hits).hasSize(2)
        assertThat(hits[0].song?.tidalTrackId).isEqualTo("95574931")
        assertThat(hits[0].video?.tidalTrackId).isEqualTo("v1")
        assertThat(hits[0].video?.artworkUrl).isEqualTo("https://example.com/video.jpg")
        assertThat(hits[0].video?.mediaType).isEqualTo("video")
        assertThat(hits[1].song).isNull()
        assertThat(hits[1].video?.tidalTrackId).isEqualTo("v2")
    }

    @Test
    fun parsePlaylistItemsIncludesVideosInOrder() {
        val client = TidalCatalogClient(
            authClient = TidalAuthClient("", ""),
            countryCode = "US",
        )
        val response = RelationshipItemsResponse(
            data = listOf(
                RelationshipRef("v9", "videos"),
                RelationshipRef("1", "tracks"),
            ),
            included = listOf(
                IncludedResource(
                    id = "1",
                    type = "tracks",
                    attributes = ResourceAttributes(title = "Audio"),
                    relationships = ResourceRelationships(
                        artists = RelationshipList(listOf(RelationshipRef("a", "artists"))),
                    ),
                ),
                IncludedResource(
                    id = "v9",
                    type = "videos",
                    attributes = ResourceAttributes(title = "Clip"),
                    relationships = ResourceRelationships(
                        artists = RelationshipList(listOf(RelationshipRef("a", "artists"))),
                    ),
                ),
                IncludedResource(
                    id = "a",
                    type = "artists",
                    attributes = ResourceAttributes(name = "Band"),
                ),
            ),
        )

        val items = client.parsePlaylistItems(response)
        assertThat(items.map { it.tidalTrackId }).containsExactly("v9", "1").inOrder()
        assertThat(items[0].mediaType).isEqualTo("video")
        assertThat(items[1].mediaType).isEqualTo("track")
    }

    @Test
    fun playlistIsoDurationDoesNotBreakAttributeParsing() {
        val attrs = ResourceAttributes(
            name = "sing-along time",
            duration = JsonPrimitive("PT22H24M41S"),
            numberOfItems = 326,
        )
        assertThat(attrs.durationSecondsOrZero()).isEqualTo(22 * 3600 + 24 * 60 + 41)
        assertThat(parseIso8601DurationSeconds("PT3M20S")).isEqualTo(200)
        assertThat(parseIso8601DurationSeconds("PT45S")).isEqualTo(45)
    }

    @Test
    fun catalogSearchUsesQueryFilterNotPathId() {
        assertThat(TidalCatalogClient.catalogSearchUrl())
            .isEqualTo("https://openapi.tidal.com/v2/searchResults")
        assertThat(TidalCatalogClient.SEARCH_QUERY_FILTER).isEqualTo("filter[query]")
    }

    @Test
    fun searchApiResponseDecodesCollectionDocument() {
        val decoded = Json.decodeFromString<SearchApiResponse>(
            """
            {
              "data": [{
                "id": "wolf",
                "type": "searchResults",
                "relationships": {
                  "tracks": { "data": [{ "id": "95574931", "type": "tracks" }] }
                }
              }],
              "included": [{
                "id": "95574931",
                "type": "tracks",
                "attributes": { "title": "Wolf Like Me" },
                "relationships": {
                  "artists": { "data": [{ "id": "1", "type": "artists" }] }
                }
              }, {
                "id": "1",
                "type": "artists",
                "attributes": { "name": "TV On The Radio" }
              }]
            }
            """.trimIndent(),
        )
        val client = TidalCatalogClient(
            authClient = TidalAuthClient("", ""),
            countryCode = "US",
        )
        val tracks = client.parseSearch(decoded)
        assertThat(tracks.map { it.tidalTrackId }).containsExactly("95574931")
        assertThat(tracks[0].title).isEqualTo("Wolf Like Me")
        assertThat(tracks[0].artist).isEqualTo("TV On The Radio")
    }

    @Test
    fun parseSearchHitsEmptyCollectionReturnsNoHits() {
        val client = TidalCatalogClient(
            authClient = TidalAuthClient("", ""),
            countryCode = "US",
        )
        assertThat(client.parseSearchHits(SearchApiResponse(data = emptyList()))).isEmpty()
    }

    @Test
    fun addTrackToLibraryKeepsCacheWhenPlaylistReloadFails() = runTest {
        val cache = InMemoryLibraryTrackCache()
        val existing = TrackRef("old-1", "Old Song", "Band")
        cache.save("playlist-1", listOf(existing))
        val http = catalogHttp { request ->
            when {
                request.method.value == "POST" && request.url.encodedPath.contains("/relationships/items") ->
                    respond("", HttpStatusCode.OK, jsonHeaders)
                request.method.value == "GET" && request.url.encodedPath.contains("/relationships/items") ->
                    respond(
                        """{"errors":[{"detail":"temporary playlist error"}]}""",
                        HttpStatusCode.InternalServerError,
                        jsonHeaders,
                    )
                else -> error("unexpected ${request.method} ${request.url}")
            }
        }
        val client = libraryCatalog(http, cache)
        client.getLibraryTracks()

        client.addTrackToLibrary(
            TrackRef("77892506", "Bitter Sweet Symphony", "The Verve"),
        )

        assertThat(client.cachedLibraryTrackIds()).containsExactly("old-1", "77892506")
        assertThat(cache.clearCount).isEqualTo(0)
        assertThat(cache.load("playlist-1")!!.map { it.tidalTrackId })
            .containsExactly("old-1", "77892506")
    }

    @Test
    fun addTrackToLibraryTreatsDuplicateConflictAsSuccess() = runTest {
        val cache = InMemoryLibraryTrackCache()
        val http = catalogHttp { request ->
            when {
                request.method.value == "POST" -> respond(
                    """{"errors":[{"detail":"Item already exists in playlist"}]}""",
                    HttpStatusCode.Conflict,
                    jsonHeaders,
                )
                request.method.value == "GET" -> respond(
                    """
                    {
                      "data": [{"id": "77892506", "type": "tracks"}],
                      "included": [
                        {
                          "id": "77892506",
                          "type": "tracks",
                          "attributes": {"title": "Bitter Sweet Symphony"},
                          "relationships": {"artists": {"data": [{"id": "a", "type": "artists"}]}}
                        },
                        {"id": "a", "type": "artists", "attributes": {"name": "The Verve"}}
                      ]
                    }
                    """.trimIndent(),
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
                else -> error("unexpected ${request.method} ${request.url}")
            }
        }
        val client = libraryCatalog(http, cache)

        client.addTrackToLibrary(TrackRef("77892506", "Bitter Sweet Symphony", "The Verve"))

        assertThat(client.cachedLibraryTrackIds()).contains("77892506")
        assertThat(cache.clearCount).isEqualTo(0)
    }

    @Test
    fun addTrackToLibrarySurfacesUrlFreeError() = runTest {
        val cache = InMemoryLibraryTrackCache()
        val http = catalogHttp { request ->
            respond(
                """{"errors":[{"detail":"Playlist is not writable"}]}""",
                HttpStatusCode.Forbidden,
                jsonHeaders,
            )
        }
        val client = libraryCatalog(http, cache)

        try {
            client.addTrackToLibrary(TrackRef("77892506", "Bitter Sweet Symphony", "The Verve"))
            throw AssertionError("expected add to fail")
        } catch (error: IllegalStateException) {
            assertThat(error).hasMessageThat().isEqualTo("Playlist is not writable")
            assertThat(error.message!!.lowercase()).doesNotContain("http")
        }
        assertThat(cache.clearCount).isEqualTo(0)
        assertThat(client.cachedLibraryTrackIds()).isEmpty()
    }

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/vnd.api+json")

    private fun catalogHttp(
        handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): HttpClient = HttpClient(MockEngine { handler(it) }) {
        expectSuccess = false
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                },
            )
        }
    }

    private fun libraryCatalog(
        http: HttpClient,
        cache: LibraryTrackCachePersistence,
    ): TidalCatalogClient {
        val store = TidalTokenStore(FakeSharedPreferences())
        store.saveUserTokens(
            accessToken = "access",
            refreshToken = "refresh",
            expiresInSeconds = 3_600,
        )
        store.saveLibraryPlaylist("playlist-1", "sing-along time")
        return TidalCatalogClient(
            authClient = TidalAuthClient("id", "secret", tokenStore = store, http = http),
            countryCode = "US",
            http = http,
            libraryTrackCache = cache,
        )
    }

    private class InMemoryLibraryTrackCache : LibraryTrackCachePersistence {
        private var payload: PersistedLibraryTracks? = null
        var clearCount = 0

        override fun load(playlistId: String): List<TrackRef>? {
            val current = payload ?: return null
            if (current.playlistId != playlistId) return null
            return current.tracks
        }

        override fun save(playlistId: String, tracks: List<TrackRef>) {
            payload = PersistedLibraryTracks(playlistId, tracks)
        }

        override fun clear() {
            clearCount += 1
            payload = null
        }
    }

    private class FakeSharedPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()

        override fun getString(key: String?, defValue: String?): String? =
            values[key] as? String ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as? Set<String>)?.toMutableSet() ?: defValues

        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

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

            override fun clear(): SharedPreferences.Editor = apply { values.clear() }

            override fun commit(): Boolean = true

            override fun apply() = Unit
        }
    }
}
