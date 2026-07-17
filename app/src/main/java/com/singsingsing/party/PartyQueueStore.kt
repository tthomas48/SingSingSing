package com.singsingsing.party

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PersistedPartyQueue(
    val items: List<QueueItem>,
    val currentIndex: Int,
)

interface PartyQueuePersistence {
    fun load(): PersistedPartyQueue?
    fun save(queue: PersistedPartyQueue)
    fun clear()
}

/** Persists the complete party queue across process death and app upgrades. */
class PartyQueueStore(context: Context) : PartyQueuePersistence {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): PersistedPartyQueue? {
        val encoded = prefs.getString(KEY_QUEUE, null) ?: return null
        return decode(encoded)
    }

    override fun save(queue: PersistedPartyQueue) {
        prefs.edit().putString(KEY_QUEUE, encode(queue)).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_QUEUE).apply()
    }

    companion object {
        private const val PREFS_NAME = "party_queue"
        private const val KEY_QUEUE = "queue"
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        internal fun encode(queue: PersistedPartyQueue): String =
            json.encodeToString(PersistedPartyQueue.serializer(), queue)

        internal fun decode(encoded: String): PersistedPartyQueue? =
            runCatching {
                json.decodeFromString(PersistedPartyQueue.serializer(), encoded)
            }.getOrNull()
    }
}
