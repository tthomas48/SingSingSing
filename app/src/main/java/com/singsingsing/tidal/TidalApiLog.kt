package com.singsingsing.tidal

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Thin wrapper that logs Tidal HTTP request URL/status/body for logcat debugging
 * (`adb logcat -s TidalApi`). Guest-facing messages never include URLs — the party
 * server hides any error text containing "http".
 */
internal object TidalApiLog {
    const val TAG = "TidalApi"
    private const val BODY_LIMIT = 4000

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend inline fun <reified T> get(
        http: HttpClient,
        url: String,
        noinline block: HttpRequestBuilder.() -> Unit = {},
    ): T = execute(http, HttpMethod.Get, url, block)

    suspend inline fun <reified T> post(
        http: HttpClient,
        url: String,
        noinline block: HttpRequestBuilder.() -> Unit = {},
    ): T = execute(http, HttpMethod.Post, url, block)

    suspend inline fun <reified T> execute(
        http: HttpClient,
        method: HttpMethod,
        url: String,
        noinline block: HttpRequestBuilder.() -> Unit,
    ): T {
        Log.i(TAG, "→ ${method.value} $url")
        return try {
            val response = http.request(url) {
                this.method = method
                block()
            }
            val bodyText = response.bodyAsText()
            logResponse(response.status, bodyText)
            if (response.status.value >= 400) {
                throw TidalApiException(
                    status = response.status.value,
                    body = bodyText,
                    userMessage = userFacingError(response.status.value, bodyText),
                )
            }
            @Suppress("UNCHECKED_CAST")
            when (T::class) {
                Unit::class -> Unit as T
                String::class -> bodyText as T
                else -> {
                    if (bodyText.isBlank()) {
                        error("Tidal returned an empty response")
                    }
                    json.decodeFromString(bodyText)
                }
            }
        } catch (error: TidalApiException) {
            throw error
        } catch (error: ClientRequestException) {
            val bodyText = runCatching { error.response.bodyAsText() }.getOrNull().orEmpty()
            logResponse(error.response.status, bodyText)
            throw TidalApiException(
                status = error.response.status.value,
                body = bodyText,
                userMessage = userFacingError(error.response.status.value, bodyText),
            )
        } catch (error: ServerResponseException) {
            val bodyText = runCatching { error.response.bodyAsText() }.getOrNull().orEmpty()
            logResponse(error.response.status, bodyText)
            throw TidalApiException(
                status = error.response.status.value,
                body = bodyText,
                userMessage = userFacingError(error.response.status.value, bodyText),
            )
        } catch (error: IllegalStateException) {
            throw error
        } catch (error: Throwable) {
            Log.e(
                TAG,
                "→ ${method.value} $url blew up: ${error.javaClass.simpleName}: ${error.message}",
                error,
            )
            throw error
        }
    }

    fun logResponse(status: HttpStatusCode, body: String) {
        val clipped = if (body.length > BODY_LIMIT) {
            body.take(BODY_LIMIT) + "…(+${body.length - BODY_LIMIT} chars)"
        } else {
            body
        }
        if (status.value >= 400) {
            Log.e(TAG, "← HTTP ${status.value} $clipped")
        } else {
            Log.i(TAG, "← HTTP ${status.value} $clipped")
        }
    }

    fun userFacingError(status: Int, body: String): String {
        val detail = jsonApiErrorDetail(body)
            ?.takeIf { it.isNotBlank() && !it.contains("http", ignoreCase = true) }
        if (!detail.isNullOrBlank()) return detail
        return "Couldn't update the karaoke library (status $status)"
    }

    fun isAlreadyInPlaylist(status: Int, body: String): Boolean {
        if (status == HttpStatusCode.Conflict.value) return true
        val haystack = listOfNotNull(body, jsonApiErrorDetail(body))
            .joinToString(" ")
            .lowercase()
        if (haystack.isBlank()) return false
        return "duplicate" in haystack ||
            ("already" in haystack && ("exist" in haystack || "playlist" in haystack || "item" in haystack))
    }

    internal fun jsonApiErrorDetail(body: String): String? {
        if (body.isBlank()) return null
        val parsed = runCatching {
            json.decodeFromString(JsonApiErrorDocument.serializer(), body)
        }.getOrNull() ?: return null
        return parsed.errors
            .orEmpty()
            .mapNotNull { it.detail?.takeIf { detail -> detail.isNotBlank() } ?: it.title }
            .firstOrNull()
    }
}

internal class TidalApiException(
    val status: Int,
    val body: String,
    userMessage: String,
) : IllegalStateException(userMessage) {
    val isAlreadyInPlaylist: Boolean
        get() = TidalApiLog.isAlreadyInPlaylist(status, body)
}

@Serializable
internal data class JsonApiErrorDocument(
    val errors: List<JsonApiError>? = null,
)

@Serializable
internal data class JsonApiError(
    val status: String? = null,
    val code: String? = null,
    val title: String? = null,
    val detail: String? = null,
)
