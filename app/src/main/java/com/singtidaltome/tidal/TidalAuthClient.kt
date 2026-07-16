package com.singtidaltome.tidal

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * TIDAL OAuth client.
 *
 * Catalog search uses client credentials. User login uses Authorization Code + PKCE
 * (device-code flow is reserved for Tidal's own apps).
 */
class TidalAuthClient(
    private val clientId: String,
    private val clientSecret: String,
    private val tokenStore: TidalTokenStore? = null,
    private val http: HttpClient = defaultHttpClient(),
) {
    private val mutex = Mutex()
    private var cachedAppToken: CachedToken? = null
    private var pendingLogin: PendingPkceLogin? = null

    fun isConfigured(): Boolean = clientId.isNotBlank() && clientSecret.isNotBlank()

    fun hasUserSession(): Boolean = tokenStore?.hasUserSession() == true

    fun libraryPlaylistId(): String? = tokenStore?.libraryPlaylistId()

    fun libraryPlaylistName(): String? = tokenStore?.libraryPlaylistName()

    fun isLibraryConfigured(): Boolean = tokenStore?.isLibraryConfigured() == true

    fun saveLibraryPlaylist(id: String, name: String) {
        tokenStore?.saveLibraryPlaylist(id, name)
    }

    fun savedUserId(): String? = tokenStore?.userId()

    fun saveUserId(userId: String) {
        tokenStore?.saveUserId(userId)
    }

    /** App-level token for catalog search. */
    suspend fun accessToken(): String = mutex.withLock {
        val existing = cachedAppToken
        if (existing != null && existing.expiresAtEpochMs > System.currentTimeMillis() + 30_000) {
            return existing.accessToken
        }
        val token = fetchClientCredentials()
        cachedAppToken = CachedToken(
            accessToken = token.accessToken,
            expiresAtEpochMs = System.currentTimeMillis() + (token.expiresIn * 1000L),
        )
        token.accessToken
    }

    /** User token for playlist read/write. */
    suspend fun userAccessToken(): String = mutex.withLock {
        val store = tokenStore ?: error("User auth is not available")
        val cached = store.accessToken()
        val expiresAt = store.accessExpiresAtEpochMs()
        if (!cached.isNullOrBlank() && expiresAt > System.currentTimeMillis() + 30_000) {
            return cached
        }
        val refresh = store.refreshToken()
            ?: error("Sign in to Tidal in Settings to use the karaoke library")
        val token = refreshAccessToken(refresh)
        store.saveUserTokens(
            accessToken = token.accessToken,
            refreshToken = token.refreshToken ?: refresh,
            expiresInSeconds = token.expiresIn,
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
     * Starts Authorization Code + PKCE login. Open [PkceLoginSession.authorizeUrl] on a phone
     * (or scan the QR). [redirectUri] must be registered on the Tidal developer app and must
     * point at this TV's party server `/oauth/callback`.
     */
    fun beginPkceLogin(redirectUri: String, scope: String = DEFAULT_USER_SCOPE): PkceLoginSession {
        require(isConfigured()) { "TIDAL credentials are not configured" }
        val verifier = generateCodeVerifier()
        val challenge = codeChallengeS256(verifier)
        val state = generateCodeVerifier().take(32)
        pendingLogin = PendingPkceLogin(
            codeVerifier = verifier,
            state = state,
            redirectUri = redirectUri,
        )
        val authorizeUrl = buildString {
            append(AUTHORIZE_URL)
            append("?response_type=code")
            append("&client_id=").append(enc(clientId))
            append("&redirect_uri=").append(enc(redirectUri))
            append("&scope=").append(enc(scope))
            append("&code_challenge_method=S256")
            append("&code_challenge=").append(enc(challenge))
            append("&state=").append(enc(state))
        }
        return PkceLoginSession(authorizeUrl = authorizeUrl, redirectUri = redirectUri, state = state)
    }

    suspend fun completePkceLogin(code: String, state: String?): TokenResponse {
        val pending = pendingLogin
            ?: error("No sign-in in progress — start Sign in to Tidal again from Settings")
        if (!state.isNullOrBlank() && state != pending.state) {
            error("Sign-in state mismatch — try again from Settings")
        }
        return try {
            exchangeAuthorizationCode(
                code = code,
                redirectUri = pending.redirectUri,
                codeVerifier = pending.codeVerifier,
            ).also { token ->
                tokenStore?.saveUserTokens(
                    accessToken = token.accessToken,
                    refreshToken = token.refreshToken,
                    expiresInSeconds = token.expiresIn,
                )
                pendingLogin = null
            }
        } catch (error: ClientRequestException) {
            throw IllegalStateException(friendlyHttpError(error), error)
        }
    }

    private suspend fun exchangeAuthorizationCode(
        code: String,
        redirectUri: String,
        codeVerifier: String,
    ): TokenResponse {
        val basic = Base64.getEncoder().encodeToString("$clientId:$clientSecret".toByteArray())
        return http.submitForm(
            url = TOKEN_URL,
            formParameters = Parameters.build {
                append("grant_type", "authorization_code")
                append("client_id", clientId)
                append("code", code)
                append("redirect_uri", redirectUri)
                append("code_verifier", codeVerifier)
            },
        ) {
            header("Authorization", "Basic $basic")
        }.body()
    }

    private suspend fun refreshAccessToken(refreshToken: String): TokenResponse {
        val basic = Base64.getEncoder().encodeToString("$clientId:$clientSecret".toByteArray())
        return http.submitForm(
            url = TOKEN_URL,
            formParameters = Parameters.build {
                append("grant_type", "refresh_token")
                append("refresh_token", refreshToken)
            },
        ) {
            header("Authorization", "Basic $basic")
        }.body()
    }

    companion object {
        const val TOKEN_URL = "https://auth.tidal.com/v1/oauth2/token"
        const val AUTHORIZE_URL = "https://login.tidal.com/authorize"
        const val DEFAULT_USER_SCOPE =
            "playlists.read playlists.write collection.read user.read"

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

        fun generateCodeVerifier(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        fun codeChallengeS256(verifier: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(StandardCharsets.US_ASCII))
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        }

        private fun enc(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8)

        suspend fun friendlyHttpError(error: ClientRequestException): String {
            val body = runCatching { error.response.bodyAsText() }.getOrNull().orEmpty()
            val parsed = runCatching {
                errorJson.decodeFromString(OAuthErrorBody.serializer(), body)
            }.getOrNull()
            return parsed?.errorDescription
                ?: parsed?.error
                ?: when (error.response.status.value) {
                    400 -> "Tidal rejected the sign-in request"
                    401 -> "Tidal credentials were rejected"
                    else -> "Tidal sign-in failed (${error.response.status.value})"
                }
        }

        private val errorJson = Json { ignoreUnknownKeys = true }
    }
}

data class PkceLoginSession(
    val authorizeUrl: String,
    val redirectUri: String,
    val state: String,
)

private data class PendingPkceLogin(
    val codeVerifier: String,
    val state: String,
    val redirectUri: String,
)

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("expires_in") val expiresIn: Long = 3600,
    @SerialName("refresh_token") val refreshToken: String? = null,
    val scope: String? = null,
)

@Serializable
data class OAuthErrorBody(
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
)

private data class CachedToken(
    val accessToken: String,
    val expiresAtEpochMs: Long,
)
