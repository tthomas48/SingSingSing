package com.singtidaltome.lyrics

import com.singtidaltome.party.LyricsResponse
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

class LrcLibClient(
    private val http: HttpClient = defaultHttpClient(),
) {
    suspend fun fetchLyrics(track: TrackRef): LyricsResponse {
        return try {
            if (track.durationSeconds > 0 && track.album.isNotBlank()) {
                val exact: LrcLibTrack = http.get("$BASE_URL/api/get") {
                    header(HttpHeaders.UserAgent, USER_AGENT)
                    parameter("track_name", track.title)
                    parameter("artist_name", track.artist.substringBefore(",").trim())
                    parameter("album_name", track.album)
                    parameter("duration", track.durationSeconds)
                }.body()
                return toResponse(exact)
            }
            searchFallback(track)
        } catch (_: Exception) {
            searchFallback(track)
        }
    }

    private suspend fun searchFallback(track: TrackRef): LyricsResponse {
        val results: List<LrcLibTrack> = http.get("$BASE_URL/api/search") {
            header(HttpHeaders.UserAgent, USER_AGENT)
            parameter("track_name", track.title)
            parameter("artist_name", track.artist.substringBefore(",").trim())
        }.body()
        val best = results.firstOrNull() ?: return LyricsResponse()
        return toResponse(best)
    }

    private fun toResponse(track: LrcLibTrack): LyricsResponse {
        val synced = track.syncedLyrics
        return LyricsResponse(
            plainLyrics = track.plainLyrics,
            syncedLyrics = synced,
            instrumental = track.instrumental,
            lines = LrcParser.parse(synced),
        )
    }

    companion object {
        const val BASE_URL = "https://lrclib.net"
        const val USER_AGENT = "SingTidalToMe/0.1.0 (https://github.com/sing-tidal-to-me)"

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
data class LrcLibTrack(
    val id: Long? = null,
    val trackName: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    val duration: Double? = null,
    val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
)
