package se.swahnchat.app.platform.notify

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.swahnchat.app.R
import se.swahnchat.app.data.AppPrefs
import se.swahnchat.app.data.ChatRepository
import se.swahnchat.app.data.NotificationPrivacy
import se.swahnchat.app.data.SessionManager
import se.swahnchat.app.data.SessionState
import se.swahnchat.app.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton

/** Keeps the launcher shortcuts ([ConversationShortcuts]) in step with the conversation list, the privacy setting and the sign-in. */
@Singleton
class ShortcutSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: ChatRepository,
    private val prefs: AppPrefs,
    private val session: SessionManager,
    private val shortcuts: ConversationShortcuts,
    @ApplicationScope private val scope: CoroutineScope,
) {
    internal enum class Mode { PUBLISH, REMOVE_ALL, KEEP }
    internal data class Desired(val mode: Mode, val recent: List<ConversationShortcuts.Chat>, val existing: Set<String>)

    /** Call once from [se.swahnchat.app.AppStarter]. */
    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            combine(repo.conversationItems(context.getString(R.string.unknown_user)), prefs.notificationPrivacy, session.state) { items, privacy, state ->
                Desired(
                    mode = desiredMode(state, privacy),
                    recent = items.filter { !it.archived }.sortedByDescending { it.lastActivity ?: 0L }.take(ConversationShortcuts.MAX_SHORTCUTS)
                        .map { ConversationShortcuts.Chat(it.id, it.title) },
                    existing = items.mapTo(HashSet()) { it.id },
                )
            }.distinctUntilChanged().debounce(SETTLE_MS).collect { desired ->
                try {
                    withContext(Dispatchers.Default) { apply(desired) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // a launcher that refuses shortcuts is not a reason to stop
                }
            }
        }
    }

    private fun apply(d: Desired) = when (d.mode) {
        Mode.PUBLISH -> shortcuts.publish(d.recent, d.existing)
        Mode.REMOVE_ALL -> shortcuts.removeAll()
        Mode.KEEP -> Unit // still starting: what is on the launcher stays until we know
    }

    internal companion object {
        private const val SETTLE_MS = 1_500L

        /** Signed out, or names hidden: nothing may stay on the launcher. Still starting: leave it alone. */
        fun desiredMode(state: SessionState, privacy: NotificationPrivacy): Mode = when {
            state is SessionState.SignedOut || (state is SessionState.SignedIn && privacy == NotificationPrivacy.HIDDEN) -> Mode.REMOVE_ALL
            state is SessionState.SignedIn -> Mode.PUBLISH
            else -> Mode.KEEP
        }
    }
}
