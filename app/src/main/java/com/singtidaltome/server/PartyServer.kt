package com.singtidaltome.server

import android.content.Context
import android.util.Log
import com.singtidaltome.bridge.BridgeQueueHolder
import com.singtidaltome.party.AddTrackRequest
import com.singtidaltome.party.GuestActionRequest
import com.singtidaltome.party.JoinRequest
import com.singtidaltome.party.JoinResponse
import com.singtidaltome.party.PartySession
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
                    call.respondText(
                        text = """{"error":${json.encodeToString(cause.message ?: "request failed")}}""",
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
                    val tracks = partySession.search(body.query)
                    call.respond(SearchResponse(tracks = tracks))
                }
                post("/api/queue") {
                    val body = call.receive<AddTrackRequest>()
                    val item = partySession.addTrack(body.guestId, body.track)
                    call.respond(item)
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
