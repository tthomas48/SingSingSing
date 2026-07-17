package com.singsingsing.bridge

/**
 * Process-wide handle so the HTTP debug endpoint can inspect Tidal's MediaController queue.
 */
object BridgeQueueHolder {
    @Volatile
    private var bridge: TidalBridge? = null

    fun attach(bridge: TidalBridge) {
        this.bridge = bridge
    }

    fun current(): List<BridgeQueueItem> = bridge?.readQueue().orEmpty()
}
