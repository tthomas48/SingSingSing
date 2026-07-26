package com.singsingsing.tidal

import android.util.Log
import com.singsingsing.party.MEDIA_TYPE_TRACK
import com.singsingsing.party.MEDIA_TYPE_VIDEO
import com.singsingsing.party.PlaylistSummary
import com.singsingsing.party.SearchHit
import com.singsingsing.party.TrackRef
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class TidalCatalogClient(
    private val authClient: TidalAuthClient,
    private val countryCode: String,
    private val http: HttpClient = defaultHttpClient(),
    private val libraryTrackCache: LibraryTrackCachePersistence? = null,
) {
    @Volatile
    private var libraryCache: LibraryCache? = null

    fun isConfigured(): Boolean = authClient.isConfigured()

    fun isLibraryConfigured(): Boolean = authClient.isLibraryConfigured()

    fun libraryPlaylistName(): String? = authClient.libraryPlaylistName()

    fun cachedLibraryTrackIds(): Set<String> = libraryCache?.trackIds.orEmpty()

    fun invalidateLibraryCache() {
        libraryCache = null
        libraryTrackCache?.clear()
    }

    suspend fun search(query: String, limit: Int = 20): List<SearchHit> {
        val cleaned = query.trim()
        if (cleaned.isEmpty()) return emptyList()
        if (!isConfigured()) {
            error("TIDAL credentials are not configured. Set TIDAL_CLIENT_ID and TIDAL_CLIENT_SECRET in local.properties.")
        }

        val token = authClient.accessToken()
        val encoded = URLEncoder.encode(cleaned, StandardCharsets.UTF_8)
            .replace("+", "%20")
        val url = "$BASE_URL/searchResults/$encoded"
        Log.i(
            TidalApiLog.TAG,
            "search query='$cleaned' encoded='$encoded' country=$countryCode limit=$limit tokenLen=${token.length}",
        )
        val response: SearchApiResponse = try {
            TidalApiLog.get(http, url) {
                header(HttpHeaders.Authorization, "Bearer $token")
                header(HttpHeaders.Accept, ACCEPT_JSON_API)
                parameter("countryCode", countryCode)
                parameter("include", "tracks.artists,tracks.albums,videos.artists,videos.thumbnailArt")
            }
        } catch (error: Throwable) {
            Log.e(TidalApiLog.TAG, "search failed for query='$cleaned'", error)
            throw IllegalStateException("Tidal search failed — try again", error)
        }

        val hits = parseSearchHits(response).take(limit)
        Log.i(
            TidalApiLog.TAG,
            "search query='$cleaned' included=${response.included.orEmpty().size} hits=${hits.size} " +
                "titles=${hits.take(5).joinToString { "${it.title} [${it.artist}] song=${it.song != null} video=${it.video != null}" }}",
        )
        return hits
    }

    suspend fun getArtistSearchHits(artistId: String, limit: Int = 25): List<SearchHit> {
        val cleaned = artistId.trim()
        require(cleaned.isNotEmpty()) { "artistId is required" }
        val token = authClient.accessToken()
        Log.i(TidalApiLog.TAG, "getArtistSearchHits artistId=$cleaned")

        val tracksUrl = "$BASE_URL/artists/$cleaned/relationships/tracks"
        val tracksResponse: RelationshipItemsResponse = TidalApiLog.get(http, tracksUrl) {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, ACCEPT_JSON_API)
            parameter("countryCode", countryCode)
            parameter("collapseBy", "FINGERPRINT")
            parameter("include", "tracks.artists,tracks.albums")
            parameter("page[limit]", limit.toString())
        }
        val tracks = parsePlaylistItems(tracksResponse)

        val videosUrl = "$BASE_URL/artists/$cleaned/relationships/videos"
        val videos = runCatching {
            val videosResponse: RelationshipItemsResponse = TidalApiLog.get(http, videosUrl) {
                header(HttpHeaders.Authorization, "Bearer $token")
                header(HttpHeaders.Accept, ACCEPT_JSON_API)
                parameter("countryCode", countryCode)
                parameter("include", "videos.artists,videos.thumbnailArt")
                parameter("page[limit]", limit.toString())
            }
            parseRelationshipVideos(videosResponse)
        }.onFailure { error ->
            Log.w(TidalApiLog.TAG, "getArtistSearchHits videos failed artistId=$cleaned", error)
        }.getOrDefault(emptyList())

        return pairSearchHits(tracks, videos).take(limit)
    }

    suspend fun currentUserId(): String {
        authClient.savedUserId()?.let {
            Log.i(TidalApiLog.TAG, "Using cached Tidal user id=$it")
            return it
        }
        val token = authClient.userAccessToken()
        Log.i(TidalApiLog.TAG, "Resolving /users/me (token len=${token.length})")
        val response: UserMeResponse = TidalApiLog.get(http, "$BASE_URL/users/me") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, ACCEPT_JSON_API)
        }
        val id = response.data?.id ?: error("Could not resolve Tidal user id (empty data.id)")
        Log.i(TidalApiLog.TAG, "Resolved Tidal user id=$id type=${response.data?.type}")
        authClient.saveUserId(id)
        return id
    }

    suspend fun listUserPlaylists(): List<PlaylistSummary> {
        val token = authClient.userAccessToken()
        val userId = currentUserId()
        Log.i(TidalApiLog.TAG, "Listing playlists for userId=$userId country=$countryCode")

        val owned = runCatching {
            fetchPlaylistsByOwner(token, userId)
        }.onFailure { error ->
            Log.e(TidalApiLog.TAG, "filter[owners.id] playlist list failed", error)
        }.getOrNull()

        if (!owned.isNullOrEmpty()) {
            Log.i(TidalApiLog.TAG, "Owned playlists count=${owned.size}: ${owned.joinToString { it.name }}")
            return owned
        }

        Log.w(TidalApiLog.TAG, "Falling back to userCollections playlists relationship")
        val collection = runCatching {
            fetchPlaylistsFromUserCollection(token, userId)
        }.onFailure { error ->
            Log.e(TidalApiLog.TAG, "userCollections playlist list failed", error)
        }.getOrNull()

        if (!collection.isNullOrEmpty()) {
            Log.i(
                TidalApiLog.TAG,
                "Collection playlists count=${collection.size}: ${collection.joinToString { it.name }}",
            )
            return collection
        }

        val message = buildString {
            append("No playlists returned for userId=$userId. ")
            append("ownedError=${owned == null}; collectionEmpty=${collection.isNullOrEmpty()}. ")
            append("Check logcat tag TidalApi for full request/response bodies.")
        }
        Log.e(TidalApiLog.TAG, message)
        error(message)
    }

    private suspend fun fetchPlaylistsByOwner(
        token: String,
        userId: String,
    ): List<PlaylistSummary> {
        val url = "$BASE_URL/playlists"
        val response: MultiPlaylistResponse = TidalApiLog.get(http, url) {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, ACCEPT_JSON_API)
            parameter("countryCode", countryCode)
            parameter("filter[owners.id]", userId)
            parameter("page[limit]", "50")
        }
        Log.i(TidalApiLog.TAG, "filter[owners.id] raw data size=${response.data.orEmpty().size}")
        return mapPlaylistSummaries(response.data)
    }

    private suspend fun fetchPlaylistsFromUserCollection(
        token: String,
        userId: String,
    ): List<PlaylistSummary> {
        val url = "$BASE_URL/userCollections/$userId/relationships/playlists"
        val response: RelationshipItemsResponse = TidalApiLog.get(http, url) {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, ACCEPT_JSON_API)
            parameter("countryCode", countryCode)
            parameter("include", "playlists")
            parameter("page[limit]", "50")
        }
        val fromIncluded = response.included.orEmpty()
            .filter { it.type == "playlists" }
            .mapNotNull { playlist ->
                val id = playlist.id
                val name = playlist.attributes?.name?.ifBlank { null } ?: return@mapNotNull null
                PlaylistSummary(
                    id = id,
                    name = name,
                    numberOfItems = playlist.attributes?.numberOfItems ?: 0,
                )
            }
        if (fromIncluded.isNotEmpty()) {
            return fromIncluded.sortedBy { it.name.lowercase() }
        }
        // Some responses only return relationship ids — fetch each playlist meta.
        val ids = response.data.orEmpty()
            .filter { it.type == "playlists" }
            .map { it.id }
        Log.i(TidalApiLog.TAG, "Collection relationship playlist ids=$ids")
        if (ids.isEmpty()) return emptyList()
        val multi: MultiPlaylistResponse = TidalApiLog.get(http, "$BASE_URL/playlists") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, ACCEPT_JSON_API)
            parameter("countryCode", countryCode)
            parameter("filter[id]", ids.joinToString(","))
            parameter("page[limit]", "50")
        }
        return mapPlaylistSummaries(multi.data)
    }

    private fun mapPlaylistSummaries(data: List<PlaylistResource>?): List<PlaylistSummary> =
        data.orEmpty().mapNotNull { playlist ->
            val id = playlist.id ?: return@mapNotNull null
            val name = playlist.attributes?.name?.ifBlank { null } ?: return@mapNotNull null
            PlaylistSummary(
                id = id,
                name = name,
                numberOfItems = playlist.attributes?.numberOfItems ?: 0,
            )
        }.sortedBy { it.name.lowercase() }

    suspend fun getLibraryTracks(forceRefresh: Boolean = false): List<TrackRef> {
        val playlistId = authClient.libraryPlaylistId()
            ?: return emptyList()
        val cached = libraryCache
        if (!forceRefresh && cached != null && cached.playlistId == playlistId) {
            return cached.tracks
        }
        if (!forceRefresh) {
            val diskTracks = libraryTrackCache?.load(playlistId)
            if (diskTracks != null) {
                libraryCache = LibraryCache(
                    playlistId = playlistId,
                    tracks = diskTracks,
                    trackIds = diskTracks.map { it.tidalTrackId }.toSet(),
                )
                Log.i(TidalApiLog.TAG, "Restored library cache from disk playlistId=$playlistId count=${diskTracks.size}")
                return diskTracks
            }
        }
        val tracks = getPlaylistTracks(playlistId)
        rememberLibraryTracks(playlistId, tracks)
        return tracks
    }

    private fun rememberLibraryTracks(playlistId: String, tracks: List<TrackRef>) {
        libraryCache = LibraryCache(
            playlistId = playlistId,
            tracks = tracks,
            trackIds = tracks.map { it.tidalTrackId }.toSet(),
        )
        libraryTrackCache?.save(playlistId, tracks)
    }

    suspend fun searchLibrary(query: String): List<TrackRef> {
        val tracks = getLibraryTracks()
        val cleaned = query.trim()
        if (cleaned.isEmpty()) return tracks
        val needles = cleaned.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        return tracks.filter { track ->
            val hay = "${track.title} ${track.artist} ${track.album}".lowercase()
            needles.all { hay.contains(it) }
        }
    }

    suspend fun getPlaylistTracks(playlistId: String): List<TrackRef> {
        val token = authClient.userAccessToken()
        val collected = mutableListOf<TrackRef>()
        var cursor: String? = null
        var page = 0
        do {
            page += 1
            val url = "$BASE_URL/playlists/$playlistId/relationships/items"
            Log.i(TidalApiLog.TAG, "Fetching playlist items playlistId=$playlistId page=$page cursor=$cursor")
            val response: RelationshipItemsResponse = TidalApiLog.get(http, url) {
                header(HttpHeaders.Authorization, "Bearer $token")
                header(HttpHeaders.Accept, ACCEPT_JSON_API)
                parameter("countryCode", countryCode)
                parameter("include", "items.tracks:artists,items.tracks:albums,items.videos:artists,items.videos:thumbnailArt")
                parameter("page[limit]", "50")
                if (cursor != null) parameter("page[cursor]", cursor)
            }
            val pageTracks = parsePlaylistItems(response)
            Log.i(
                TidalApiLog.TAG,
                "Playlist page=$page data=${response.data.orEmpty().size} included=${response.included.orEmpty().size} parsed=${pageTracks.size}",
            )
            collected += pageTracks
            cursor = response.links?.nextCursor()
        } while (cursor != null)
        Log.i(TidalApiLog.TAG, "Playlist $playlistId total tracks=${collected.distinctBy { it.tidalTrackId }.size}")
        return collected.distinctBy { it.tidalTrackId }
    }

    suspend fun addTrackToLibrary(trackId: String, mediaType: String = MEDIA_TYPE_TRACK) {
        val playlistId = authClient.libraryPlaylistId()
            ?: error("Host hasn't set a karaoke library yet")
        addTrackToPlaylist(playlistId, trackId, mediaType)
        invalidateLibraryCache()
        getLibraryTracks(forceRefresh = true)
    }

    suspend fun addTrackToPlaylist(
        playlistId: String,
        trackId: String,
        mediaType: String = MEDIA_TYPE_TRACK,
    ) {
        val token = authClient.userAccessToken()
        val resourceType = if (mediaType == MEDIA_TYPE_VIDEO) "videos" else "tracks"
        val url = "$BASE_URL/playlists/$playlistId/relationships/items"
        Log.i(TidalApiLog.TAG, "Adding $resourceType id=$trackId to playlistId=$playlistId")
        TidalApiLog.post<String>(http, url) {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, ACCEPT_JSON_API)
            contentType(ContentType.parse(ACCEPT_JSON_API))
            parameter("countryCode", countryCode)
            setBody(
                AddPlaylistItemsBody(
                    data = listOf(RelationshipRef(id = trackId, type = resourceType)),
                ),
            )
        }
    }

    internal fun parseSearchHits(response: SearchApiResponse): List<SearchHit> {
        val included = response.included.orEmpty()
        val trackIdsInOrder = response.data?.relationships?.tracks?.data.orEmpty()
            .filter { it.type == "tracks" }
            .map { it.id }
        val videoIdsInOrder = response.data?.relationships?.videos?.data.orEmpty()
            .filter { it.type == "videos" }
            .map { it.id }
        val tracksById = mapIncludedMedia(included, type = "tracks", mediaType = MEDIA_TYPE_TRACK)
            .associateBy { it.tidalTrackId }
        val videosById = mapIncludedMedia(included, type = "videos", mediaType = MEDIA_TYPE_VIDEO)
            .associateBy { it.tidalTrackId }

        val tracks = if (trackIdsInOrder.isEmpty()) {
            tracksById.values.toList()
        } else {
            trackIdsInOrder.mapNotNull { tracksById[it] }
        }
        val videos = if (videoIdsInOrder.isEmpty()) {
            // Only use included videos when the relationship lists them, or when no track
            // relationship ordering exists either (fallback for sparse fixtures).
            if (trackIdsInOrder.isEmpty()) videosById.values.toList() else emptyList()
        } else {
            videoIdsInOrder.mapNotNull { videosById[it] }
        }
        return pairSearchHits(tracks, videos)
    }

    /** Track-only parse kept for playlist/artist track relationship responses. */
    internal fun parseSearch(response: SearchApiResponse): List<TrackRef> =
        parseSearchHits(response).mapNotNull { it.song }

    internal fun parsePlaylistItems(response: RelationshipItemsResponse): List<TrackRef> {
        val included = response.included.orEmpty()
        val itemRefs = response.data.orEmpty()
            .filter { it.type == "tracks" || it.type == "videos" }
        val mediaById = (
            mapIncludedMedia(included, type = "tracks", mediaType = MEDIA_TYPE_TRACK) +
                mapIncludedMedia(included, type = "videos", mediaType = MEDIA_TYPE_VIDEO)
            ).associateBy { it.tidalTrackId }

        return if (itemRefs.isEmpty()) {
            mediaById.values.toList()
        } else {
            itemRefs.mapNotNull { mediaById[it.id] }.ifEmpty { mediaById.values.toList() }
        }
    }

    internal fun parseRelationshipVideos(response: RelationshipItemsResponse): List<TrackRef> {
        val included = response.included.orEmpty()
        val videoIdsInOrder = response.data.orEmpty()
            .filter { it.type == "videos" }
            .map { it.id }
        val videosById = mapIncludedMedia(included, type = "videos", mediaType = MEDIA_TYPE_VIDEO)
            .associateBy { it.tidalTrackId }
        return if (videoIdsInOrder.isEmpty()) {
            videosById.values.toList()
        } else {
            videoIdsInOrder.mapNotNull { videosById[it] }
        }
    }

    private fun mapIncludedMedia(
        included: List<IncludedResource>,
        type: String,
        mediaType: String,
    ): List<TrackRef> {
        val resources = included.filter { it.type == type }
        val artistsById = included.filter { it.type == "artists" }.associateBy { it.id }
        val albumsById = included.filter { it.type == "albums" }.associateBy { it.id }
        val artworksById = included.filter { it.type == "artworks" }.associateBy { it.id }

        return resources.map { resource ->
            val artistIds = resource.relationships?.artists?.data.orEmpty().map { it.id }
            val artistNames = artistIds.mapNotNull { artistsById[it]?.attributes?.name }
            val albumId = resource.relationships?.albums?.data.orEmpty().firstOrNull()?.id
            val album = albumId?.let { albumsById[it] }
            val thumbId = resource.relationships?.thumbnailArt?.data.orEmpty().firstOrNull()?.id
            val thumbnail = thumbId?.let { artworksById[it] }
            TrackRef(
                tidalTrackId = resource.id,
                title = resource.attributes?.title.orEmpty(),
                artist = artistNames.joinToString(", ").ifBlank { "Unknown artist" },
                album = album?.attributes?.title.orEmpty(),
                durationSeconds = resource.attributes?.durationSecondsOrZero() ?: 0,
                artworkUrl = thumbnail?.attributes?.imageLinks?.firstOrNull()?.href
                    ?: album?.attributes?.imageLinks?.firstOrNull()?.href
                    ?: resource.attributes?.imageLinks?.firstOrNull()?.href,
                artistId = artistIds.firstOrNull(),
                mediaType = mediaType,
            )
        }.filter { it.tidalTrackId.isNotBlank() && it.title.isNotBlank() }
    }

    companion object {
        const val BASE_URL = "https://openapi.tidal.com/v2"
        const val ACCEPT_JSON_API = "application/vnd.api+json"

        fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
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
    }
}

private data class LibraryCache(
    val playlistId: String,
    val tracks: List<TrackRef>,
    val trackIds: Set<String>,
)

private fun ApiLinks.nextCursor(): String? {
    val next = next ?: return null
    val marker = "page%5Bcursor%5D="
    val alt = "page[cursor]="
    val fromEncoded = next.substringAfter(marker, missingDelimiterValue = "")
        .substringBefore('&')
        .takeIf { it.isNotBlank() }
    if (fromEncoded != null) return java.net.URLDecoder.decode(fromEncoded, "UTF-8")
    return next.substringAfter(alt, missingDelimiterValue = "")
        .substringBefore('&')
        .takeIf { it.isNotBlank() }
}

@Serializable
data class SearchApiResponse(
    val data: SearchData? = null,
    val included: List<IncludedResource>? = null,
)

@Serializable
data class SearchData(
    val id: String? = null,
    val type: String? = null,
    val relationships: ResourceRelationships? = null,
)

@Serializable
data class IncludedResource(
    val id: String,
    val type: String,
    val attributes: ResourceAttributes? = null,
    val relationships: ResourceRelationships? = null,
)

@Serializable
data class ResourceAttributes(
    val title: String? = null,
    val name: String? = null,
    /**
     * Tracks return a numeric duration (seconds); playlists return ISO-8601 strings like "PT3M20S".
     */
    val duration: JsonElement? = null,
    val numberOfItems: Int? = null,
    val imageLinks: List<ImageLink>? = null,
) {
    fun durationSecondsOrZero(): Int = duration.toDurationSeconds() ?: 0
}

internal fun JsonElement?.toDurationSeconds(): Int? {
    val primitive = this as? JsonPrimitive ?: return null
    primitive.doubleOrNull?.let { return it.toInt() }
    val text = primitive.contentOrNull?.trim().orEmpty()
    if (text.isEmpty()) return null
    text.toDoubleOrNull()?.let { return it.toInt() }
    return parseIso8601DurationSeconds(text)
}

internal fun parseIso8601DurationSeconds(value: String): Int? {
    // Supports common playlist forms like PT22H24M41S / PT3M20S / PT45S
    val match = Regex(
        """^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?$""",
        RegexOption.IGNORE_CASE,
    ).matchEntire(value.trim()) ?: return null
    val hours = match.groupValues[1].toIntOrNull() ?: 0
    val minutes = match.groupValues[2].toIntOrNull() ?: 0
    val seconds = match.groupValues[3].toDoubleOrNull()?.toInt() ?: 0
    return hours * 3600 + minutes * 60 + seconds
}

@Serializable
data class ImageLink(
    val href: String? = null,
    val meta: Map<String, String>? = null,
)

@Serializable
data class ResourceRelationships(
    val artists: RelationshipList? = null,
    val albums: RelationshipList? = null,
    val tracks: RelationshipList? = null,
    val videos: RelationshipList? = null,
    val thumbnailArt: RelationshipList? = null,
)

@Serializable
data class RelationshipList(
    val data: List<RelationshipRef>? = null,
)

@Serializable
data class RelationshipRef(
    val id: String,
    val type: String,
)

@Serializable
data class RelationshipItemsResponse(
    val data: List<RelationshipRef>? = null,
    val included: List<IncludedResource>? = null,
    val links: ApiLinks? = null,
)

@Serializable
data class ApiLinks(
    val next: String? = null,
    val self: String? = null,
)

@Serializable
data class AddPlaylistItemsBody(
    val data: List<RelationshipRef>,
)

@Serializable
data class UserMeResponse(
    val data: UserData? = null,
)

@Serializable
data class UserData(
    val id: String? = null,
    val type: String? = null,
)

@Serializable
data class MultiPlaylistResponse(
    val data: List<PlaylistResource>? = null,
)

@Serializable
data class PlaylistResource(
    val id: String? = null,
    val type: String? = null,
    val attributes: ResourceAttributes? = null,
)
