package com.singtidaltome.tidal

import com.singtidaltome.party.TrackRef
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class TidalCatalogClient(
    private val authClient: TidalAuthClient,
    private val countryCode: String,
    private val http: HttpClient = defaultHttpClient(),
) {
    fun isConfigured(): Boolean = authClient.isConfigured()

    suspend fun searchTracks(query: String, limit: Int = 20): List<TrackRef> {
        val cleaned = query.trim()
        if (cleaned.isEmpty()) return emptyList()
        if (!isConfigured()) {
            error("TIDAL credentials are not configured. Set TIDAL_CLIENT_ID and TIDAL_CLIENT_SECRET in local.properties.")
        }

        val token = authClient.accessToken()
        val encoded = URLEncoder.encode(cleaned, StandardCharsets.UTF_8)
        val response: SearchApiResponse = http.get("$BASE_URL/searchResults/$encoded") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/vnd.tidal.v1+json")
            parameter("countryCode", countryCode)
            parameter("include", "tracks,artists,albums")
        }.body()

        return parseSearch(response).take(limit)
    }

    internal fun parseSearch(response: SearchApiResponse): List<TrackRef> {
        val included = response.included.orEmpty()
        val tracks = included.filter { it.type == "tracks" }
        val artistsById = included.filter { it.type == "artists" }.associateBy { it.id }
        val albumsById = included.filter { it.type == "albums" }.associateBy { it.id }

        return tracks.map { track ->
            val artistIds = track.relationships?.artists?.data.orEmpty().map { it.id }
            val artistNames = artistIds.mapNotNull { artistsById[it]?.attributes?.name }
            val albumId = track.relationships?.albums?.data.orEmpty().firstOrNull()?.id
            val album = albumId?.let { albumsById[it] }
            TrackRef(
                tidalTrackId = track.id,
                title = track.attributes?.title.orEmpty(),
                artist = artistNames.joinToString(", ").ifBlank { "Unknown artist" },
                album = album?.attributes?.title.orEmpty(),
                durationSeconds = ((track.attributes?.duration ?: 0.0) ).toInt(),
                artworkUrl = album?.attributes?.imageLinks?.firstOrNull()?.href
                    ?: track.attributes?.imageLinks?.firstOrNull()?.href,
            )
        }.filter { it.tidalTrackId.isNotBlank() && it.title.isNotBlank() }
    }

    companion object {
        const val BASE_URL = "https://openapi.tidal.com/v2"

        fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
            expectSuccess = true
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

@Serializable
data class SearchApiResponse(
    val data: SearchData? = null,
    val included: List<IncludedResource>? = null,
)

@Serializable
data class SearchData(
    val id: String? = null,
    val type: String? = null,
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
    val duration: Double? = null,
    val imageLinks: List<ImageLink>? = null,
)

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
