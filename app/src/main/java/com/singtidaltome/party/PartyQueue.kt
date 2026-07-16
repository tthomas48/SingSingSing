package com.singtidaltome.party

/**
 * In-memory sing-along queue owned by our app (not Tidal's queue).
 *
 * Keeps the full session order:
 * `[history..., nowPlaying, upcoming...]` addressed by [currentIndex].
 */
class PartyQueue {
    private val items = mutableListOf<QueueItem>()
    private var currentIndex: Int = -1

    fun snapshotHistory(): List<QueueItem> =
        if (currentIndex <= 0) emptyList() else items.subList(0, currentIndex).toList()

    /** Upcoming tracks after now-playing. */
    fun snapshotQueue(): List<QueueItem> =
        if (currentIndex < 0 || currentIndex >= items.lastIndex) {
            emptyList()
        } else {
            items.subList(currentIndex + 1, items.size).toList()
        }

    fun nowPlaying(): QueueItem? =
        currentIndex.takeIf { it in items.indices }?.let { items[it] }

    /** True if track is now playing or still upcoming (history does not count). */
    fun containsActiveTrackId(tidalTrackId: String): Boolean {
        if (tidalTrackId.isBlank()) return false
        val now = nowPlaying()
        if (now?.track?.tidalTrackId == tidalTrackId) return true
        return snapshotQueue().any { it.track.tidalTrackId == tidalTrackId }
    }

    fun add(item: QueueItem): QueueItem {
        items.add(item)
        return item
    }

    fun remove(itemId: String): Boolean {
        val index = items.indexOfFirst { it.id == itemId }
        if (index < 0) return false
        if (index == currentIndex) return false
        items.removeAt(index)
        if (index < currentIndex) {
            currentIndex -= 1
        }
        return true
    }

    /**
     * Moves an upcoming item to [toIndex] within the upcoming list (0 = next up).
     */
    fun reorder(itemId: String, toIndex: Int): Boolean {
        if (currentIndex < 0) {
            val from = items.indexOfFirst { it.id == itemId }
            if (from < 0) return false
            val item = items.removeAt(from)
            val clamped = toIndex.coerceIn(0, items.size)
            items.add(clamped, item)
            return true
        }
        val upcomingStart = currentIndex + 1
        if (upcomingStart >= items.size) return false
        val absoluteFrom = items.indexOfFirst { it.id == itemId }
        if (absoluteFrom < upcomingStart) return false
        val relativeFrom = absoluteFrom - upcomingStart
        val upcoming = items.subList(upcomingStart, items.size).toMutableList()
        if (relativeFrom !in upcoming.indices) return false
        val item = upcoming.removeAt(relativeFrom)
        val clamped = toIndex.coerceIn(0, upcoming.size)
        upcoming.add(clamped, item)
        while (items.size > upcomingStart) {
            items.removeAt(items.lastIndex)
        }
        items.addAll(upcoming)
        return true
    }

    /**
     * Jumps to any session item without discarding others.
     */
    fun jumpTo(itemId: String): QueueItem? {
        val index = items.indexOfFirst { it.id == itemId }
        if (index < 0) return null
        currentIndex = index
        return items[index]
    }

    /**
     * Starts the next queued track when nothing is playing yet.
     */
    fun advance(): QueueItem? {
        if (currentIndex >= 0) return null
        if (items.isEmpty()) return null
        currentIndex = 0
        return items[currentIndex]
    }

    /**
     * Moves to the next upcoming track (keeps history).
     */
    fun skip(): QueueItem? {
        if (items.isEmpty()) return null
        if (currentIndex < 0) {
            currentIndex = 0
            return items[currentIndex]
        }
        if (currentIndex >= items.lastIndex) {
            return null
        }
        currentIndex += 1
        return items[currentIndex]
    }

    /**
     * Moves back one song in the session history.
     */
    fun replayPrevious(): QueueItem? {
        if (currentIndex <= 0) return null
        currentIndex -= 1
        return items[currentIndex]
    }

    fun clear() {
        items.clear()
        currentIndex = -1
    }
}
