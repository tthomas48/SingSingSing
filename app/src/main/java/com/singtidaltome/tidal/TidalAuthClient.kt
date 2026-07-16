package com.singtidaltome.tidal

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.http.Parameters
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * TIDAL OAuth client.
 *
 * Catalog search uses the [client credentials flow](https://developer.tidal.com/documentation/api-sdk/api-sdk-authorization).
 * Device-code login is implemented against the standard OAuth device endpoints for TV-friendly
 * user authorization when the developer app is enabled for that flow.
 */
class TidalAuthClient(
    private val clientId: String,
    private val clientSecret: String,
    private val http: HttpClient = defaultHttpClient(),
) {
    private val mutex = Mutex()
    private var cachedToken: CachedToken? = null

    fun isConfigured(): Boolean = clientId.isNotBlank() && clientSecret.isNotBlank()

    suspend fun accessToken(): String = mutex.withLock {
        val existing = cachedToken
        if (existing != null && existing.expiresAtEpochMs > System.currentTimeMillis() + 30_000) {
            return existing.accessToken
        }
        val token = fetchClientCredentials()
        cachedToken = CachedToken(
            accessToken = token.accessToken,
            expiresAtEpochMs = System.currentTimeMillis() + (token.expiresIn * 1000L),
        )
        token.accessToken
    }

    suspend fun fetchClientCredentials(): TokenResponse {
        require(isConfigured()) {
            "TIDAL_CLIENT_ID / TIDAL_CLIENT_SECRET are not set in local.properties"
        }
        val basic = Base64.getEncoder().encodeToString("$clientId:$clientSecret".toByteArray())
        return http.submitForm(
            url = TOKEN_URL,
            formParameters = Parameters.build {
                append("grant_type", "client_credentials")
            },
        ) {
            header("Authorization", "Basic $basic")
        }.body()
    }

    /**
     * Starts RFC 8628 device authorization. Returns user code + verification URI to show on TV.
     * Note: availability depends on the TIDAL developer app configuration.
     */
    suspend fun startDeviceAuthorization(scope: String = "r_usr w_usr"): DeviceAuthResponse {
        require(isConfigured()) { "TIDAL credentials are not configured" }
        val basic = Base64.getEncoder().encodeToString("$clientId:$clientSecret".toByteArray())
        return http.submitForm(
            url = DEVICE_AUTH_URL,
            formParameters = Parameters.build {
                append("client_id", clientId)
                append("scope", scope)
            },
        ) {
            header("Authorization", "Basic $basic")
        }.body()
    }

    suspend fun pollDeviceToken(deviceCode: String): TokenResponse {
        require(isConfigured()) { "TIDAL credentials are not configured" }
        val basic = Base64.getEncoder().encodeToString("$clientId:$clientSecret".toByteArray())
        return http.submitForm(
            url = TOKEN_URL,
            formParameters = Parameters.build {
                append("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                append("device_code", deviceCode)
                append("client_id", clientId)
            },
        ) {
            header("Authorization", "Basic $basic")
        }.body()
    }

    companion object {
        const val TOKEN_URL = "https://auth.tidal.com/v1/oauth2/token"
        const val DEVICE_AUTH_URL = "https://auth.tidal.com/v1/oauth2/device_authorization"

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
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("expires_in") val expiresIn: Long = 3600,
    @SerialName("refresh_token") val refreshToken: String? = null,
    val scope: String? = null,
)

@Serializable
data class DeviceAuthResponse(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("verification_uri_complete") val verificationUriComplete: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 300,
    val interval: Long = 5,
)

private data class CachedToken(
    val accessToken: String,
    val expiresAtEpochMs: Long,
)
