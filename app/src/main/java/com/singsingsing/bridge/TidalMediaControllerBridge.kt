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
                delay(700)
                if (matchesNowPlaying(track)) {
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
        delay(1_200)
        true
    }

    /**
     * Tidal TV opens music videos via Activity deep link, not MediaController
     * playFromUri / playFromSearch (those resolve to audio).
     */
    private suspend fun playVideoDeepLink(track: TrackRef): Boolean {
        ensureTidalAlive()
        val candidates = videoDeepLinkUris(track.tidalTrackId)
        for (uri in candidates) {
            val launched = runCatching {
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage(TIDAL_PACKAGE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            }.onFailure { error ->
                Log.w(TAG, "VIEW deep link failed for $uri", error)
            }.getOrDefault(false)
            if (!launched) continue

            Log.i(TAG, "VIEW deep link launched $uri")
            // Give Tidal time to switch into the video player and publish a session.
            delay(1_500)
            refreshController()
            val matched = matchesNowPlaying(track)
            Log.i(
                TAG,
                "Video VIEW result uri=$uri matched=$matched mediaId=${currentMediaId()} title=${currentTitle()}",
            )
            // VIEW is the proven TV path; metadata can lag or use a non-catalog media id.
            return true
        }
        Log.e(TAG, "Could not start video ${track.tidalTrackId} via deep link")
        return false
    }

    private fun currentMediaId(): String? =
        controller?.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_MEDIA_ID)

    private fun currentTitle(): String? =
        controller?.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)

    private fun matchesNowPlaying(track: TrackRef): Boolean {
        val metadata = controller?.metadata ?: return false
        val mediaId = metadata.getString(android.media.MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty()
        if (mediaId.isNotBlank() && mediaId == track.tidalTrackId) {
            return true
        }
        val title = metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        if (title.isBlank()) return false
        if (title.equals(track.title, ignoreCase = true)) {
            return true
        }
        val artist = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val artistNeedle = track.artist.substringBefore(",").trim()
        return artistNeedle.isNotBlank() && artist.contains(artistNeedle, ignoreCase = true) &&
            title.contains(track.title.take(12), ignoreCase = true)
    }

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
        scope.launch {
            partySession.onTidalMetadata(trackId, title, artist, position, playing)
        }
    }

    companion object {
        private const val TAG = "TidalBridge"
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
