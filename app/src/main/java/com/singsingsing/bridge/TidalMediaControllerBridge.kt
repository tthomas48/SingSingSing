package com.singsingsing.bridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import com.singsingsing.party.PartySession
import com.singsingsing.party.TrackRef
import com.singsingsing.tidal.TidalMediaIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Controls the Tidal TV app through its active [MediaController].
 *
 * Requires notification-listener access so [MediaSessionManager.getActiveSessions] returns
 * third-party sessions such as `com.aspiro.tidal`.
 */
class TidalMediaControllerBridge(
    private val context: Context,
    private val partySession: PartySession,
    private val scope: CoroutineScope,
    private val listenerComponent: ComponentName,
) : TidalBridge {
    private val sessionManager =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager

    @Volatile
    private var controller: MediaController? = null
    private var pollJob: Job? = null

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
        attachToTidal(sessions.orEmpty())
    }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            publishState()
        }

        override fun onMetadataChanged(metadata: android.media.MediaMetadata?) {
            publishState()
        }
    }

    fun start() {
        try {
            sessionManager.addOnActiveSessionsChangedListener(sessionListener, listenerComponent)
            attachToTidal(sessionManager.getActiveSessions(listenerComponent))
        } catch (security: SecurityException) {
            Log.w(TAG, "Notification access not granted", security)
            scope.launch { partySession.setBridgeReady(false) }
        }
        pollJob = scope.launch {
            while (true) {
                refreshController()
                delay(2_000)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        try {
            sessionManager.removeOnActiveSessionsChangedListener(sessionListener)
        } catch (_: Exception) {
        }
        controller?.unregisterCallback(callback)
        controller = null
    }

    override fun isReady(): Boolean = controller != null

    override fun play() {
        controller?.transportControls?.play()
    }

    override fun pause() {
        controller?.transportControls?.pause()
    }

    override fun skipToNext() {
        controller?.transportControls?.skipToNext()
    }

    override fun skipToPrevious() {
        controller?.transportControls?.skipToPrevious()
    }

    override fun skipToQueueItem(queueItemId: Long): Boolean {
        val controls = controller?.transportControls ?: return false
        controls.skipToQueueItem(queueItemId)
        return true
    }

    override fun readQueue(): List<BridgeQueueItem> {
        return controller?.queue.orEmpty().map { item ->
            val desc = item.description
            BridgeQueueItem(
                queueId = item.queueId,
                mediaId = desc.mediaId,
                title = desc.title?.toString(),
                artist = desc.subtitle?.toString(),
            )
        }
    }

    override suspend fun playTrack(track: TrackRef): Boolean = withContext(Dispatchers.Main) {
        if (track.isVideo) {
            return@withContext playVideoDeepLink(track)
        }

        ensureTidalAlive()
        refreshController()
        val controls = controller?.transportControls
        if (controls == null) {
            Log.w(TAG, "No Tidal MediaController available")
            return@withContext false
        }

        val uriCandidates = playUriCandidates(track)
        for (uri in uriCandidates) {
            try {
                controls.playFromUri(uri, Bundle())
                Log.i(TAG, "playFromUri $uri")
                if (waitUntilMatches(track, URI_MATCH_TIMEOUT_MS)) {
                    Log.i(TAG, "playFromUri matched $uri mediaId=${currentMediaId()} title=${currentTitle()}")
                    return@withContext true
                }
            } catch (error: Exception) {
                Log.w(TAG, "playFromUri failed for $uri", error)
            }
        }

        val query = listOf(track.title, track.artist).filter { it.isNotBlank() }.joinToString(" ")
        val extras = Bundle().apply {
            putString(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
            putString(MediaStore.EXTRA_MEDIA_TITLE, track.title)
            putString(MediaStore.EXTRA_MEDIA_ARTIST, track.artist)
            putString(MediaStore.EXTRA_MEDIA_ALBUM, track.album)
        }
        controls.playFromSearch(query, extras)
        Log.i(TAG, "playFromSearch query=$query")
        val matched = waitUntilMatches(track, URI_MATCH_TIMEOUT_MS)
        Log.i(
            TAG,
            "playFromSearch result matched=$matched mediaId=${currentMediaId()} title=${currentTitle()}",
        )
        // Search can lag on media id; PartySession treats matching title/artist as owned.
        return@withContext true
    }

    /**
     * Tidal TV opens music videos via Activity deep link, not MediaController
     * playFromUri / playFromSearch (those resolve to audio). Do not open the TV
     * home launcher first — that races the video player.
     */
    private suspend fun playVideoDeepLink(track: TrackRef): Boolean {
        refreshController()
        runCatching { controller?.transportControls?.pause() }

        val candidates = videoDeepLinkUris(track.tidalTrackId)
        for (uri in candidates) {
            if (!launchVideoView(uri)) continue
            Log.i(TAG, "VIEW deep link launched $uri")
            if (waitUntilMatches(track, VIDEO_MATCH_TIMEOUT_MS)) {
                Log.i(
                    TAG,
                    "Video VIEW matched uri=$uri mediaId=${currentMediaId()} title=${currentTitle()}",
                )
                return true
            }
            Log.i(TAG, "Video VIEW unmatched, retrying $uri")
            if (!launchVideoView(uri)) continue
            if (waitUntilMatches(track, VIDEO_MATCH_TIMEOUT_MS)) {
                Log.i(
                    TAG,
                    "Video VIEW retry matched uri=$uri mediaId=${currentMediaId()} title=${currentTitle()}",
                )
                return true
            }
        }
        Log.e(
            TAG,
            "Could not start video ${track.tidalTrackId} via deep link mediaId=${currentMediaId()} title=${currentTitle()}",
        )
        return false
    }

    private fun launchVideoView(uri: Uri): Boolean =
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage(TIDAL_PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.onFailure { error ->
            Log.w(TAG, "VIEW deep link failed for $uri", error)
        }.getOrDefault(false)

    private suspend fun waitUntilMatches(track: TrackRef, timeoutMs: Long): Boolean {
        val steps = (timeoutMs / MATCH_POLL_MS).toInt().coerceAtLeast(1)
        repeat(steps) {
            refreshController()
            if (matchesNowPlaying(track)) return true
            delay(MATCH_POLL_MS)
        }
        refreshController()
        return matchesNowPlaying(track)
    }

    private fun currentMediaId(): String? =
        controller?.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_MEDIA_ID)

    private fun currentTitle(): String? =
        controller?.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)

    private fun currentArtist(): String? =
        controller?.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)

    private fun matchesNowPlaying(track: TrackRef): Boolean =
        TidalMediaIds.matchesNowPlaying(track, currentMediaId(), currentTitle(), currentArtist())

    private fun ensureTidalAlive() {
        val launch = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            setClassName(TIDAL_PACKAGE, TIDAL_TV_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(launch)
        } catch (error: Exception) {
            Log.w(TAG, "Unable to launch Tidal TV activity", error)
            // Fall back to MEDIA_PLAY_FROM_SEARCH activity path.
            val search = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                setPackage(TIDAL_PACKAGE)
                putExtra("query", " ")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(search) }
        }
    }

    private fun refreshController() {
        try {
            attachToTidal(sessionManager.getActiveSessions(listenerComponent))
        } catch (security: SecurityException) {
            scope.launch { partySession.setBridgeReady(false) }
        }
    }

    private fun attachToTidal(sessions: List<MediaController>) {
        val tidal = sessions.firstOrNull { it.packageName == TIDAL_PACKAGE }
        if (tidal?.sessionToken == controller?.sessionToken) {
            scope.launch { partySession.setBridgeReady(tidal != null) }
            publishState()
            return
        }
        controller?.unregisterCallback(callback)
        controller = tidal
        tidal?.registerCallback(callback)
        scope.launch { partySession.setBridgeReady(tidal != null) }
        publishState()
        Log.i(TAG, if (tidal != null) "Attached to Tidal MediaController" else "Tidal session not active")
    }

    private fun publishState() {
        val current = controller ?: return
        val metadata = current.metadata
        val state = current.playbackState
        val trackId = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_MEDIA_ID)
        val title = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
        val position = state?.position ?: 0L
        val playing = state?.state == PlaybackState.STATE_PLAYING
        Log.i(
            TAG,
            "session mediaId=$trackId title=$title artist=$artist pos=$position playing=$playing",
        )
        scope.launch {
            partySession.onTidalMetadata(trackId, title, artist, position, playing)
        }
    }

    companion object {
        private const val TAG = "TidalBridge"
        private const val URI_MATCH_TIMEOUT_MS = 3_000L
        private const val VIDEO_MATCH_TIMEOUT_MS = 8_000L
        private const val MATCH_POLL_MS = 250L
        const val TIDAL_PACKAGE = "com.aspiro.tidal"
        const val TIDAL_TV_LAUNCHER = "com.aspiro.wamp.tv.TvLauncherActivity"

        fun playUriCandidates(track: TrackRef): List<Uri> =
            playUriStrings(track).map { Uri.parse(it) }

        fun playUriStrings(track: TrackRef): List<String> {
            val kind = if (track.isVideo) "video" else "track"
            val id = track.tidalTrackId
            return listOf(
                "tidal://$kind/$id",
                "https://tidal.com/browse/$kind/$id",
                "https://tidal.com/$kind/$id",
            )
        }

        /** Confirmed on Google TV: VIEW https browse URL opens the music video player. */
        fun videoDeepLinkUris(videoId: String): List<Uri> =
            videoDeepLinkStrings(videoId).map { Uri.parse(it) }

        fun videoDeepLinkStrings(videoId: String): List<String> = listOf(
            "https://tidal.com/browse/video/$videoId",
            "tidal://video/$videoId",
        )
    }
}
