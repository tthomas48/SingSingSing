package com.singtidaltome.server

import android.content.Context
import android.util.Log
import com.singtidaltome.SingAlongApp
import com.singtidaltome.bridge.BridgeQueueHolder
import com.singtidaltome.party.AddTrackRequest
import com.singtidaltome.party.FavoriteTrackRequest
import com.singtidaltome.party.GuestActionRequest
import com.singtidaltome.party.JoinRequest
import com.singtidaltome.party.JoinResponse
import com.singtidaltome.party.PartySession
import com.singtidaltome.party.PlayQueueItemRequest
import com.singtidaltome.party.ReorderQueueRequest
import com.singtidaltome.party.SearchRequest
import com.singtidaltome.party.SearchResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.Inet4Address
import java.net.NetworkInterface

class PartyServer(
    private val context: Context,
    private val partySession: PartySession,
    private val scope: CoroutineScope,
    private val port: Int,
) {
    private var engine: ApplicationEngine? = null
    private var broadcastJob: Job? = null

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun start() {
        if (engine != null) return
        val server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(ContentNegotiation) { json(json) }
            install(CORS) { anyHost() }
            install(WebSockets)
            install(StatusPages) {
                exception<Throwable> { call, cause ->
                    Log.e(TAG, "Request failed", cause)
                    val message = cause.message
                        ?.takeIf { it.isNotBlank() && !it.contains("http", ignoreCase = true) }
                        ?: "Something went wrong — try again"
                    call.respondText(
                        text = """{"error":${json.encodeToString(message)}}""",
                        contentType = ContentType.Application.Json,
                        status = HttpStatusCode.BadRequest,
                    )
                }
            }
            routing {
                get("/") {
                    call.respondText(assetText("web/index.html"), ContentType.Text.Html)
                }
                get("/app.js") {
                    call.respondText(assetText("web/app.js"), ContentType.Text.JavaScript)
                }
                get("/styles.css") {
                    call.respondText(assetText("web/styles.css"), ContentType.Text.CSS)
                }
                get("/oauth/callback") {
                    val error = call.request.queryParameters["error"]
                    val errorDescription = call.request.queryParameters["error_description"]
                    if (!error.isNullOrBlank()) {
                        call.respondText(
                            oauthResultHtml(
                                title = "Sign-in cancelled",
                                body = errorDescription ?: error,
                                ok = false,
                            ),
                            ContentType.Text.Html,
                            HttpStatusCode.BadRequest,
                        )
                        return@get
                    }
                    val code = call.request.queryParameters["code"]
                    val state = call.request.queryParameters["state"]
                    if (code.isNullOrBlank()) {
                        call.respondText(
                            oauthResultHtml(
                                title = "Missing code",
                                body = "Tidal did not return an authorization code.",
                                ok = false,
                            ),
                            ContentType.Text.Html,
                            HttpStatusCode.BadRequest,
                        )
                        return@get
                    }
                    try {
                        val auth = SingAlongApp.instance.tidalAuth
                        auth.completePkceLogin(code = code, state = state)
                        runCatching { SingAlongApp.instance.tidalCatalog.currentUserId() }
                        SingAlongApp.instance.partySession.refreshLibrarySnapshot()
                        call.respondText(
                            oauthResultHtml(
                                title = "Signed in",
                                body = "You can close this tab and return to the TV. Then choose your karaoke library playlist in Settings.",
                                ok = true,
                            ),
                            ContentType.Text.Html,
                        )
                    } catch (error: Throwable) {
                        Log.e(TAG, "OAuth callback failed", error)
                        call.respondText(
                            oauthResultHtml(
                                title = "Sign-in failed",
                                body = error.message ?: "Unknown error",
                                ok = false,
                            ),
                            ContentType.Text.Html,
                            HttpStatusCode.BadRequest,
                        )
                    }
                }
                get("/api/health") {
                    call.respondText(
                        """{"ok":true,"port":$port}""",
                        ContentType.Application.Json,
                    )
                }
                get("/api/state") {
                    call.respond(partySession.snapshot.value)
                }
                post("/api/join") {
                    val body = call.receive<JoinRequest>()
                    val guest = partySession.join(body.name)
                    call.respond(JoinResponse(guest = guest, snapshot = partySession.snapshot.value))
                }
                post("/api/search") {
                    val body = call.receive<SearchRequest>()
                    Log.i(TAG, "POST /api/search query='${body.query}'")
                    try {
                        val tracks = partySession.search(body.query)
                        Log.i(TAG, "POST /api/search ok count=${tracks.size}")
                        call.respond(SearchResponse(tracks = tracks))
                    } catch (error: Throwable) {
                        Log.e(TAG, "POST /api/search failed query='${body.query}'", error)
                        throw error
                    }
                }
                get("/api/artists/{artistId}/tracks") {
                    val artistId = call.parameters["artistId"].orEmpty()
                    Log.i(TAG, "GET /api/artists/$artistId/tracks")
                    val tracks = partySession.artistTracks(artistId)
                    call.respond(SearchResponse(tracks = tracks))
                }
                get("/api/library") {
                    val query = call.request.queryParameters["q"].orEmpty()
                    call.respond(partySession.libraryTracks(query))
                }
                post("/api/library/favorite") {
                    val body = call.receive<FavoriteTrackRequest>()
                    partySession.favoriteTrack(body.guestId, body.track)
                    call.respond(partySession.snapshot.value)
                }
                post("/api/queue") {
                    val body = call.receive<AddTrackRequest>()
                    val item = partySession.addTrack(body.guestId, body.track)
                    call.respond(item)
                }
                post("/api/queue/reorder") {
                    val body = call.receive<ReorderQueueRequest>()
                    partySession.reorderQueue(body.guestId, body.itemId, body.toIndex)
                    call.respond(partySession.snapshot.value)
                }
                post("/api/queue/play") {
                    val body = call.receive<PlayQueueItemRequest>()
                    partySession.jumpTo(body.guestId, body.itemId)
                    call.respond(partySession.snapshot.value)
                }
                post("/api/skip") {
                    val body = call.receive<GuestActionRequest>()
                    partySession.skip(body.guestId)
                    call.respond(partySession.snapshot.value)
                }
                post("/api/previous") {
                    val body = call.receive<GuestActionRequest>()
                    partySession.previous(body.guestId)
                    call.respond(partySession.snapshot.value)
                }
                post("/api/pause") {
                    val body = call.receive<GuestActionRequest>()
                    partySession.pause(body.guestId)
                    call.respond(partySession.snapshot.value)
                }
                post("/api/play") {
                    val body = call.receive<GuestActionRequest>()
                    partySession.play(body.guestId)
                    call.respond(partySession.snapshot.value)
                }
                post("/api/next") {
                    val body = call.receive<GuestActionRequest>()
                    partySession.nextTransport(body.guestId)
                    call.respond(partySession.snapshot.value)
                }
                post("/api/open-lyrics") {
                    val body = call.receive<GuestActionRequest>()
                    partySession.openLyrics(body.guestId)
                    call.respond(partySession.snapshot.value)
                }
                get("/api/lyrics") {
                    call.respond(partySession.lyricsForNowPlaying())
                }
                get("/api/bridge/queue") {
                    call.respond(BridgeQueueHolder.current())
                }
                webSocket("/ws") {
                    send(Frame.Text(json.encodeToString(partySession.snapshot.value)))
                    val collector = launch {
                        partySession.events.collectLatest { snap ->
                            send(Frame.Text(json.encodeToString(snap)))
                        }
                    }
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                frame.readText()
                            }
                        }
                    } finally {
                        collector.cancel()
                    }
                }
            }
        }
        server.start(wait = false)
        engine = server
        broadcastJob = scope.launch {
            partySession.events.collectLatest {
                // WebSocket handlers subscribe individually; keep the shared flow active.
            }
        }
        Log.i(TAG, "Party server listening on ${joinUrl()}")
    }

    fun stop() {
        broadcastJob?.cancel()
        broadcastJob = null
        engine?.stop(1_000, 2_000)
        engine = null
    }

    fun joinUrl(): String = "http://${lanIp()}:$port/"

    fun oauthCallbackUrl(): String = "http://${lanIp()}:$port/oauth/callback"

    private fun oauthResultHtml(title: String, body: String, ok: Boolean): String {
        val color = if (ok) "#3ddc97" else "#ff6b6b"
        return """
            <!doctype html>
            <html><head><meta charset="utf-8"/><meta name="viewport" content="width=device-width,initial-scale=1"/>
            <title>$title</title>
            <style>
              body{font-family:system-ui,sans-serif;background:#0b1220;color:#f4f7fb;display:grid;place-items:center;min-height:100vh;margin:0}
              main{max-width:28rem;padding:2rem;background:#152238;border-radius:18px}
              h1{color:$color;margin:0 0 .75rem}
              p{line-height:1.45;color:#9db0c7}
            </style></head>
            <body><main><h1>$title</h1><p>${body.replace("<", "&lt;")}</p></main></body></html>
        """.trimIndent()
    }

    private fun lanIp(): String {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        for (network in interfaces) {
            if (!network.isUp || network.isLoopback) continue
            for (address in network.inetAddresses) {
                if (!address.isLoopbackAddress && address is Inet4Address) {
                    return address.hostAddress ?: continue
                }
            }
        }
        return "127.0.0.1"
    }

    private fun assetText(path: String): String =
        context.assets.open(path).bufferedReader().use { it.readText() }

    companion object {
        private const val TAG = "PartyServer"
    }
}
