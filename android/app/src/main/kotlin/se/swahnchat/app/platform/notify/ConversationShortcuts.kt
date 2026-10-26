package se.swahnchat.app.platform.notify

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import se.swahnchat.app.MainActivity
import se.swahnchat.app.R
import se.swahnchat.app.data.AppPrefs
import se.swahnchat.app.data.NotificationPrivacy
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Launcher shortcuts for the most recent chats (long press on the app icon) that are also what the share sheet offers as
 * "send to ...". They are long-lived, which is what Android requires to treat a notification as a conversation, so the
 * message notifications use the same ids (the conversation id).
 *
 * Shortcuts show names on the launcher, so [NotificationPrivacy.HIDDEN] publishes none and removes those that exist; they are
 * removed on sign-out as well, and for a chat that was left or deleted. A tap opens the chat through the normal deep link,
 * so the app lock still applies. Nothing here may throw: it runs from collectors and from the notification path.
 */
@Singleton
class ConversationShortcuts @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: AppPrefs,
) {
    /**
     * Makes sure a shortcut for [conversationId] exists (a notification refers to it); it is pushed on top of the others.
     * Returns whether one exists, i.e. whether the notification may name it as its `shortcutId`. Never with hidden names.
     */
    fun ensure(conversationId: String, title: String): Boolean {
        if (prefs.notificationPrivacy.value == NotificationPrivacy.HIDDEN) return false
        return try {
            if (conversationId !in knownIds()) ShortcutManagerCompat.pushDynamicShortcut(context, build(Chat(conversationId, title), 0))
            true
        } catch (e: Exception) {
            false
        }
    }

    /** One chat is gone (left, deleted): its shortcut goes, a copy the user pinned to the launcher is disabled. */
    fun remove(conversationId: String) {
        try {
            disablePinned(listOf(conversationId)) // first: the update needs the shortcut to exist
            ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(conversationId))
            ShortcutManagerCompat.removeLongLivedShortcuts(context, listOf(conversationId))
        } catch (e: Exception) {
            // nothing to do about it
        }
    }

    /** Removes every shortcut of this app (sign-out, hidden names); the ones pinned to the launcher are disabled and lose the name. */
    fun removeAll() {
        try {
            val ids = knownIds().toList()
            disablePinned(ids) // first: the update needs the shortcuts to exist
            ShortcutManagerCompat.removeAllDynamicShortcuts(context)
            if (ids.isNotEmpty()) ShortcutManagerCompat.removeLongLivedShortcuts(context, ids)
        } catch (e: Exception) {
            // nothing to do about it
        }
    }

    /**
     * The launcher keeps a shortcut the user pinned even after it was removed here, with the chat's name, until it is
     * disabled; a disabled one stays visible but cannot be used. So the label and the icon (the first letter of the name)
     * are replaced by neutral ones first, then it is disabled with a message that names nothing.
     */
    private fun disablePinned(ids: List<String>) {
        if (ids.isEmpty()) return
        val message = context.getString(R.string.shortcut_disabled)
        try {
            // updateShortcuts ignores ids that are neither published nor pinned
            val neutral = ids.map { id ->
                ShortcutInfoCompat.Builder(context, id)
                    .setShortLabel(message)
                    .setLongLabel(message)
                    .setIntent(Intent(Intent.ACTION_MAIN).setClass(context, MainActivity::class.java))
                    .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                    .build()
            }
            ShortcutManagerCompat.updateShortcuts(context, neutral)
        } catch (e: Exception) {
            // still disabled below
        }
        ShortcutManagerCompat.disableShortcuts(context, ids, message)
    }

    /** Replaces the dynamic shortcuts by [recent] (most recent first) and drops long-lived ones of chats that are not in [existing] any more. */
    internal fun publish(recent: List<Chat>, existing: Set<String>) {
        if (prefs.notificationPrivacy.value == NotificationPrivacy.HIDDEN) { // names must not reach the launcher, pinned copies included
            removeAll()
            return
        }
        ShortcutManagerCompat.setDynamicShortcuts(context, recent.mapIndexed { rank, chat -> build(chat, rank) })
        // chats that are gone: their long-lived (cached) shortcuts would otherwise keep the name on the share sheet
        val stale = knownIds() - existing
        if (stale.isNotEmpty()) {
            disablePinned(stale.toList())
            ShortcutManagerCompat.removeLongLivedShortcuts(context, stale.toList())
        }
    }

    internal data class Chat(val id: String, val title: String)

    internal fun knownIds(): Set<String> = ShortcutManagerCompat
        .getShortcuts(
            context,
            ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_CACHED or ShortcutManagerCompat.FLAG_MATCH_PINNED,
        )
        .filter { it.isEnabled } // a pinned shortcut that was disabled is already neutral; it is not a name on the launcher any more
        .mapTo(HashSet()) { it.id }

    private fun build(chat: Chat, rank: Int): ShortcutInfoCompat {
        val open = Intent(Intent.ACTION_VIEW, Notifier.deepLink(chat.id), context, MainActivity::class.java)
        return ShortcutInfoCompat.Builder(context, chat.id)
            .setShortLabel(chat.title.take(SHORT_LABEL))
            .setLongLabel(chat.title)
            .setLongLived(true)
            .setRank(rank)
            .setIntent(open)
            .setCategories(setOf(CATEGORY_SHARE))
            .setPerson(Person.Builder().setName(chat.title).setKey(chat.id).build())
            .setIcon(IconCompat.createWithAdaptiveBitmap(initialBitmap(chat)))
            .build()
    }

    /** The first letter on a colour taken from the id (the same chat always has the same colour); no picture leaves the app. */
    private fun initialBitmap(chat: Chat): Bitmap {
        val size = ICON_PX
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = PALETTE[(chat.id.hashCode() and 0x7FFFFFFF) % PALETTE.size]
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        paint.color = 0xFFFFFFFF.toInt()
        paint.textSize = size * 0.4f // the adaptive icon shows the middle 2/3 only
        paint.textAlign = Paint.Align.CENTER
        val letter = chat.title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        canvas.drawText(letter, size / 2f, size / 2f - (paint.descent() + paint.ascent()) / 2f, paint)
        return bitmap
    }

    companion object {
        /** The category of the `<share-target>` in `res/xml/shortcuts.xml`: shortcuts with it are offered by the share sheet. */
        const val CATEGORY_SHARE = "se.swahnchat.app.directshare.CHAT"
        const val MAX_SHORTCUTS = 4
        private const val SHORT_LABEL = 10
        private const val ICON_PX = 216
        private val PALETTE = intArrayOf(0xFF1F6F6B.toInt(), 0xFF1B5FAA.toInt(), 0xFF6B4EA0.toInt(), 0xFFA8365F.toInt(), 0xFF9A4A00.toInt(), 0xFF2E6B2F.toInt())
    }
}
