package com.singtidaltome.bridge

import com.singtidaltome.party.TrackRef
import kotlinx.serialization.Serializable

interface TidalBridge {
    fun isReady(): Boolean
    fun play()
    fun pause()
    fun skipToNext()
    fun skipToPrevious()
    fun skipToQueueItem(queueItemId: Long): Boolean
    suspend fun playTrack(track: TrackRef): Boolean
    fun readQueue(): List<BridgeQueueItem>
}

@Serializable
data class BridgeQueueItem(
    val queueId: Long,
    val mediaId: String? = null,
    val title: String? = null,
    val artist: String? = null,
)
