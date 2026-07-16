package com.singtidaltome.party

/**
 * In-memory sing-along queue owned by our app (not Tidal's queue).
 *
 * Semantics:
 * - Guests append tracks; each item keeps attribution.
 * - [advance] moves the front of the queue into now-playing.
 * - [skip] discards the current track and advances.
 * - [replayPrevious] requeues the current track at the front and restores the last played item.
 */
class PartyQueue {
    private val queue = ArrayDeque<QueueItem>()
    private var nowPlayingItem: QueueItem? = null
    private var previousItem: QueueItem? = null

    fun snapshotQueue(): List<QueueItem> = queue.toList()

    fun nowPlaying(): QueueItem? = nowPlayingItem

    fun add(item: QueueItem): QueueItem {
        queue.addLast(item)
        return item
    }

    fun remove(itemId: String): Boolean {
        val existing = queue.firstOrNull { it.id == itemId } ?: return false
        queue.remove(existing)
        return true
    }

    /**
     * Starts the next queued track. Returns the new now-playing item, or null if empty.
     */
    fun advance(): QueueItem? {
        val next = queue.removeFirstOrNull() ?: return null
        if (nowPlayingItem != null) {
            previousItem = nowPlayingItem
        }
        nowPlayingItem = next
        return next
    }

    /**
     * Skips the current track and starts the next one (if any).
     */
    fun skip(): QueueItem? {
        if (nowPlayingItem == null && queue.isEmpty()) return null
        if (queue.isEmpty()) {
            previousItem = nowPlayingItem
            nowPlayingItem = null
            return null
        }
        return advance()
    }

    /**
     * Goes back to the previous track if we still remember it.
     * Current track is pushed to the front of the queue.
     */
    fun replayPrevious(): QueueItem? {
        val previous = previousItem ?: return null
        nowPlayingItem?.let { queue.addFirst(it) }
        nowPlayingItem = previous
        previousItem = null
        return previous
    }

    fun clear() {
        queue.clear()
        nowPlayingItem = null
        previousItem = null
    }
}
