package com.singtidaltome.bridge

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
import com.singtidaltome.party.PartySession
import com.singtidaltome.party.TrackRef
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
        ensureTidalAlive()
        refreshController()
        val controls = controller?.transportControls
        if (controls == null) {
            Log.w(TAG, "No Tidal MediaController available")
            return@withContext false
        }

        val uriCandidates = listOf(
            Uri.parse("tidal://track/${track.tidalTrackId}"),
            Uri.parse("https://tidal.com/browse/track/${track.tidalTrackId}"),
            Uri.parse("https://tidal.com/track/${track.tidalTrackId}"),
        )
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

    private fun matchesNowPlaying(track: TrackRef): Boolean {
        val metadata = controller?.metadata ?: return false
        val title = metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        return title.equals(track.title, ignoreCase = true) ||
            artist.contains(track.artist.substringBefore(","), ignoreCase = true)
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
    }
}
