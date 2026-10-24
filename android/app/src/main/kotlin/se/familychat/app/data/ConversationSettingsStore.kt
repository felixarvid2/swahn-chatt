package se.familychat.app.data

import androidx.room.withTransaction
import se.familychat.app.data.db.AppDatabase
import se.familychat.app.data.db.ConversationSettingsEntity
import javax.inject.Inject
import javax.inject.Singleton

/** How long a conversation stays muted. */
enum class MuteDuration(val millis: Long?) {
    HOUR_1(60L * 60_000),
    HOURS_8(8L * 60 * 60_000),
    WEEK_1(7L * 24 * 60 * 60_000),
    ALWAYS(null),
    ;

    /** The end of the mute when it starts at [now]. */
    fun until(now: Long): Long = millis?.let { now + it } ?: ConversationSettingsEntity.MUTED_ALWAYS
}

/** Whether [mutedUntil] (as stored) still mutes at [now]. */
fun isMuted(mutedUntil: Long, now: Long): Boolean = mutedUntil > now

/** The most conversations that can be pinned. */
const val MAX_PINNED = 5

/**
 * Pin, mute and archive: local only (never sent to the server or other devices). Rules:
 * - at most [MAX_PINNED] pinned; archiving a conversation unpins it;
 * - a new incoming message unarchives a conversation unless it is muted (`RoomLocalStore.upsertMessage`);
 * - muting keeps the unread count, only the notification is dropped (`Notifier`).
 */
@Singleton
class ConversationSettingsStore @Inject constructor(private val db: AppDatabase) {
    private val dao get() = db.storeDao()

    private suspend fun change(id: String, update: (ConversationSettingsEntity) -> ConversationSettingsEntity) = db.withTransaction {
        // settings of a conversation that does not exist (any more) are not kept
        if (dao.conversation(id) == null) return@withTransaction
        dao.putSettings(update(dao.settings(id) ?: ConversationSettingsEntity(id, null, 0, false)))
        dao.deleteSettingsIfDefault(id)
    }

    /** False when [MAX_PINNED] others are pinned already (nothing changes). */
    suspend fun pin(id: String, now: Long): Boolean = db.withTransaction {
        if (dao.pinnedCountExcept(id) >= MAX_PINNED) return@withTransaction false
        change(id) { it.copy(pinnedAt = it.pinnedAt ?: now, archived = false) }
        true
    }

    suspend fun unpin(id: String) = change(id) { it.copy(pinnedAt = null) }

    suspend fun mute(id: String, duration: MuteDuration, now: Long) = change(id) { it.copy(mutedUntil = duration.until(now)) }
    suspend fun unmute(id: String) = change(id) { it.copy(mutedUntil = 0) }

    suspend fun archive(id: String) = change(id) { it.copy(archived = true, pinnedAt = null) }
    suspend fun unarchive(id: String) = change(id) { it.copy(archived = false) }

    suspend fun settings(id: String): ConversationSettingsEntity? = dao.settings(id)

    /** Whether notifications of [id] are off at [now]. */
    suspend fun isMuted(id: String, now: Long): Boolean = dao.settings(id)?.let { isMuted(it.mutedUntil, now) } == true
}
