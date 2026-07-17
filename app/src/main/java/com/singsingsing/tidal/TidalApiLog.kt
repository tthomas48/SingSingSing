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
import kotlinx.serialization.json.Json

/**
 * Thin wrapper that logs Tidal HTTP request URL/status/body for logcat debugging
 * (`adb logcat -s TidalApi`).
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
                error("Tidal ${method.value} $url failed: HTTP ${response.status.value} $bodyText")
            }
            @Suppress("UNCHECKED_CAST")
            when (T::class) {
                Unit::class -> Unit as T
                String::class -> bodyText as T
                else -> {
                    if (bodyText.isBlank()) {
                        error("Tidal ${method.value} $url returned empty body")
                    }
                    json.decodeFromString(bodyText)
                }
            }
        } catch (error: ClientRequestException) {
            val bodyText = runCatching { error.response.bodyAsText() }.getOrNull().orEmpty()
            logResponse(error.response.status, bodyText)
            throw IllegalStateException(
                "Tidal ${method.value} $url failed: HTTP ${error.response.status.value} $bodyText",
                error,
            )
        } catch (error: ServerResponseException) {
            val bodyText = runCatching { error.response.bodyAsText() }.getOrNull().orEmpty()
            logResponse(error.response.status, bodyText)
            throw IllegalStateException(
                "Tidal ${method.value} $url failed: HTTP ${error.response.status.value} $bodyText",
                error,
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
}
