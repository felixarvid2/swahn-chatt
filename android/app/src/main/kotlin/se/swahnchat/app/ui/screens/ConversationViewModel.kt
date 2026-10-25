package se.swahnchat.app.ui.screens

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.swahnchat.app.data.AppPrefs
import se.swahnchat.app.data.AutoDownloadPolicy
import se.swahnchat.app.data.ChatRepository
import se.swahnchat.app.data.ConnectionStatus
import se.swahnchat.app.data.db.LatestMessageRow
import se.swahnchat.app.data.db.toModel
import se.swahnchat.app.di.ApplicationScope
import se.swahnchat.app.data.UiError
import se.swahnchat.app.data.toUiError
import se.swahnchat.app.media.AttachmentPreparer
import se.swahnchat.app.media.AttachmentTooLargeException
import se.swahnchat.app.media.MediaValidator
import se.swahnchat.app.media.UnusableAttachmentException
import se.swahnchat.app.media.VoicePlayer
import se.swahnchat.app.media.VoiceRecorder
import se.swahnchat.app.platform.ActiveConversation
import se.swahnchat.app.platform.ConnectivityMonitor
import se.swahnchat.app.platform.notify.Notifier
import se.swahnchat.app.platform.work.ExpiryManager
import se.swahnchat.app.platform.work.WorkScheduler
import se.swahnchat.app.media.PendingShares
import se.swahnchat.app.ui.components.PlaybackUi
import se.swahnchat.app.ui.model.AttachmentKindUi
import se.swahnchat.app.ui.model.AttachmentUi
import se.swahnchat.app.ui.model.AvatarUi
import se.swahnchat.app.ui.model.ChatRow
import se.swahnchat.app.ui.model.ChatRowContext
import se.swahnchat.app.ui.model.ConnectionUi
import se.swahnchat.app.ui.model.SafetyUi
import se.swahnchat.app.ui.model.toChatRow
import se.swahnchat.app.ui.model.withSeparators
import se.swahnchat.app.ui.model.ReplyUi
import se.swahnchat.app.ui.model.attachmentKindOf
import se.swahnchat.core.client.DownloadState
import se.swahnchat.core.client.call.CallManager
import se.swahnchat.core.client.call.CallState
import se.swahnchat.core.client.SwahnChatEngine
import se.swahnchat.core.client.LocalStore
import se.swahnchat.core.client.MessageGoneException
import se.swahnchat.core.client.OutgoingAttachment
import se.swahnchat.core.client.StoredMessage
import se.swahnchat.core.model.ConversationType
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@Immutable
data class RecordingUi(val elapsedMs: Long, val locked: Boolean)

@Immutable
data class ConversationUiState(
    val title: String = "",
    val isGroup: Boolean = false,
    val memberCount: Int = 0,
    val avatar: AvatarUi = AvatarUi(""),
    val typingNames: List<String> = emptyList(),
    /** The other person of a direct conversation (for the contact screen). */
    val otherUserId: String? = null,
    val replyTo: ReplyUi? = null,
    val editingMessageId: String? = null,
    val draft: String = "",
    val recording: RecordingUi? = null,
    val sending: Boolean = false,
    val loaded: Boolean = false,
    val removed: Boolean = false,
    /** The newest message of the conversation (the list shows it first when it is at the bottom); null: no messages. */
    val latestId: String? = null,
    val latestOutgoing: Boolean = false,
    /** Messages from others that arrived while the list was scrolled away from the bottom (or were unread on opening). */
    val newMessages: Int = 0,
    /** Unread messages when the conversation was opened (0: the list starts at the bottom). */
    val openUnread: Int = 0,
    val connection: ConnectionUi = ConnectionUi.HIDDEN,
    val safety: SafetyUi = SafetyUi(),
    /** Photos, videos and files chosen but not sent yet; they go out as one message with the text as caption. */
    val staged: List<StagedUi> = emptyList(),
    /** The disappearing-messages timer in seconds (0: off). */
    val timerSeconds: Long = 0,
    /** The screen is in the foreground; the clocks of disappearing messages only start while it is. */
    val resumed: Boolean = false,
    /** In a direct chat anyone can change the timer; in a group only administrators. */
    val canSetTimer: Boolean = true,
    /** Another call (or this one) is in progress: the call buttons are disabled. */
    val callActive: Boolean = false,
)

/** One chosen file in the preview strip above the composer. */
@Immutable
data class StagedUi(val id: String, val uri: Uri, val kind: AttachmentKindUi, val name: String?)

sealed interface ConversationEffect {
    data class Error(val error: UiError, val detail: String? = null) : ConversationEffect
    data class OpenFile(val path: String, val mime: String, val name: String?) : ConversationEffect

    /** A short message for the snackbar, as a string resource. */
    data class Notice(val message: Int) : ConversationEffect

    /** Scroll to this message; if it is not loaded yet, [ConversationViewModel.loadAround] has been asked for. */
    data class ScrollTo(val messageId: String) : ConversationEffect
}

/** What was unread when the conversation was opened (captured before it is marked read). */
class OpenAnchor(val readSeq: Long, val firstUnreadId: String?, val unreadCount: Int)

/** Where the paged list starts: [key] is an offset into the newest-first list (null: the newest message). */
private class PagerStart(val key: Int?, val generation: Int)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class ConversationViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val engine: SwahnChatEngine,
    private val repo: ChatRepository,
    private val store: LocalStore,
    private val preparer: AttachmentPreparer,
    private val pendingShares: PendingShares,
    private val expiry: ExpiryManager,
    private val recorder: VoiceRecorder,
    private val player: VoicePlayer,
    private val active: ActiveConversation,
    private val notifier: Notifier,
    private val work: WorkScheduler,
    private val prefs: AppPrefs,
    private val connectivity: ConnectivityMonitor,
    connectionStatus: ConnectionStatus,
    calls: CallManager,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {
    val conversationId: String = checkNotNull(savedState["id"])

    private val draft = MutableStateFlow("")
    private val replyTo = MutableStateFlow<String?>(null)
    private val editing = MutableStateFlow<String?>(null)
    private val recording = MutableStateFlow<RecordingUi?>(null)
    private val sending = MutableStateFlow(false)
    private val downloads = MutableStateFlow<Map<String, Float?>>(emptyMap())
    private val validated = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    private val missing = MutableStateFlow<Set<String>>(emptySet())
    private val autoRequested = ConcurrentHashMap.newKeySet<String>()
    private val validating = ConcurrentHashMap.newKeySet<String>()
    private val resumed = MutableStateFlow(false)
    private val staged = MutableStateFlow<List<StagedUi>>(emptyList())
    private val displayedIds = HashSet<String>()
    private val pagerStart = MutableStateFlow(PagerStart(null, 0))
    /** Messages above this sequence number count as "new" in the jump-to-bottom button; null while the list is at the bottom. */
    private val newSinceSeq = MutableStateFlow<Long?>(null)
    private var recordingTicker: Job? = null
    private var typingJob: Job? = null

    private val _effects = MutableSharedFlow<ConversationEffect>(extraBufferCapacity = 8)
    val effects: SharedFlow<ConversationEffect> = _effects.asSharedFlow()

    val playback: StateFlow<PlaybackUi> = player.state

    /** The unread state at the moment of opening; computed before anything marks the conversation read. */
    private val anchor: Deferred<OpenAnchor> = viewModelScope.async {
        try {
            val readSeq = store.getConversation(conversationId)?.myReadSeq ?: 0L
            val unread = store.unreadCount(conversationId)
            OpenAnchor(readSeq, if (unread > 0) repo.firstUnreadId(conversationId, readSeq) else null, unread)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            OpenAnchor(0L, null, 0) // no divider rather than no conversation
        }
    }

    private val pagedEntities = pagerStart.flatMapLatest { start ->
        val a = anchor.await()
        // a start at the newest message loads all unread messages at once, so the divider and the first unread are there
        val initial = if (start.key == null) (a.unreadCount + PAGE_SIZE).coerceIn(2 * PAGE_SIZE, MAX_INITIAL_LOAD) else 2 * PAGE_SIZE
        Pager(
            PagingConfig(pageSize = PAGE_SIZE, initialLoadSize = initial, prefetchDistance = PAGE_SIZE / 2, enablePlaceholders = false),
            initialKey = start.key,
        ) { repo.pagedMessages(conversationId) }.flow
    }.cachedIn(viewModelScope)

    private val rowContext = combine<Any?, ChatRowContext>(
        repo.userMap(), engine.account, downloads, validated, missing, repo.reactions(conversationId),
    ) { v ->
        @Suppress("UNCHECKED_CAST")
        val users = v[0] as Map<String, se.swahnchat.core.model.User>
        val account = v[1] as se.swahnchat.core.client.Account?
        @Suppress("UNCHECKED_CAST")
        val dl = v[2] as Map<String, Float?>
        @Suppress("UNCHECKED_CAST")
        val ok = v[3] as Map<String, Boolean>
        @Suppress("UNCHECKED_CAST")
        val gone = v[4] as Set<String>
        @Suppress("UNCHECKED_CAST")
        val reactions = v[5] as Map<String, List<se.swahnchat.core.client.Reaction>>
        val isGroup = store.getConversation(conversationId)?.type == ConversationType.GROUP
        ChatRowContext(account?.userId.orEmpty(), isGroup, users, emptyMap(), dl, ok, gone, reactions = reactions)
    }

    /**
     * The list, newest message first (the screen shows it with a reversed layout). A change of downloads or users maps the
     * cached pages again; the pages themselves are not reloaded.
     */
    val rows: Flow<PagingData<ChatRow>> = combine(pagedEntities, rowContext) { data, ctx ->
        val firstUnread = anchor.await().firstUnreadId
        data.map { entity ->
            val m = entity.toModel()
            m.toChatRow(ctx, m.replyTo?.let { store.getMessage(it) })
        }.withSeparators(ctx.zone, firstUnread)
    }

    private val header = combine(repo.conversation(conversationId), repo.members(conversationId), engine.typing, engine.account) { c, members, typing, account ->
        if (c == null) return@combine null
        val isGroup = c.type == ConversationType.GROUP
        val other = members.firstOrNull { !it.isMe }
        val title = if (isGroup) c.name.orEmpty() else other?.name.orEmpty()
        val typingNames = typing[conversationId].orEmpty().mapNotNull { id -> members.firstOrNull { it.userId == id }?.name }
        Header(
            title, isGroup, members.size,
            if (isGroup) AvatarUi(title, c.avatarAttachmentId) else other?.avatar ?: AvatarUi(title),
            typingNames, if (isGroup) null else other?.userId, members.any { it.isMe && it.isAdmin },
        )
    }

    private class Header(
        val title: String, val isGroup: Boolean, val memberCount: Int, val avatar: AvatarUi,
        val typingNames: List<String>, val otherUserId: String?, val iAmAdmin: Boolean,
    )

    private val composer = combine<Any?, Composer>(draft, replyTo, editing, recording, sending, staged, resumed) { v ->
        @Suppress("UNCHECKED_CAST")
        Composer(v[0] as String, v[1] as String?, v[2] as String?, v[3] as RecordingUi?, v[4] as Boolean, v[5] as List<StagedUi>, v[6] as Boolean)
    }

    private class Composer(
        val draft: String, val replyTo: String?, val editing: String?, val recording: RecordingUi?, val sending: Boolean,
        val staged: List<StagedUi>, val resumed: Boolean,
    )

    /** Everything around the list that comes from the database or the connection. */
    private class Around(
        val latest: LatestMessageRow?, val newMessages: Int, val connection: ConnectionUi, val safety: SafetyUi, val openUnread: Int,
        val timer: Long, val callActive: Boolean,
    )

    /** -1 until the unread state at opening is known (the screen keeps the list hidden meanwhile). */
    private val openUnread = MutableStateFlow(-1)

    /** A call is in progress (ringing, connecting or connected), so no second one can be placed from here. */
    private val callActive = calls.state.map { it is CallState.InCall }.distinctUntilChanged()

    private val around = combine<Any?, Around>(
        repo.latestMessage(conversationId),
        newSinceSeq.flatMapLatest { seq -> if (seq == null) flowOf(0) else repo.incomingAfter(conversationId, seq) },
        connectionStatus.state.onStart { emit(ConnectionUi.HIDDEN) },
        repo.safety(conversationId),
        openUnread,
        repo.timer(conversationId),
        callActive,
    ) { v ->
        Around(v[0] as LatestMessageRow?, v[1] as Int, v[2] as ConnectionUi, v[3] as SafetyUi, v[4] as Int, v[5] as Long, v[6] as Boolean)
    }

    val state: StateFlow<ConversationUiState> = combine(header, composer, around) { h, c, ar ->
        if (h == null) return@combine ConversationUiState(removed = true, loaded = true)
        val reply = c.replyTo?.let { id ->
            store.getMessage(id)?.let { m ->
                val name = if (m.outgoing) null else store.getUser(m.senderUserId)?.displayName ?: h.title
                ReplyUi(m.id, name, m.text, m.attachments.firstOrNull()?.let { a -> attachmentKindOf(a.kind) }, m.deleted)
            }
        }
        ConversationUiState(
            title = h.title, isGroup = h.isGroup, memberCount = h.memberCount, avatar = h.avatar,
            typingNames = h.typingNames, otherUserId = h.otherUserId, replyTo = reply, editingMessageId = c.editing,
            draft = c.draft, recording = c.recording, sending = c.sending, loaded = true,
            latestId = ar.latest?.id, latestOutgoing = ar.latest?.outgoing == true, newMessages = ar.newMessages,
            openUnread = ar.openUnread, connection = ar.connection, safety = ar.safety,
            staged = c.staged, resumed = c.resumed, timerSeconds = ar.timer, canSetTimer = !h.isGroup || h.iAmAdmin, callActive = ar.callActive,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationUiState())

    init {
        viewModelScope.launch {
            val a = anchor.await()
            openUnread.value = a.unreadCount
            // opened on unread messages: the button counts them until the user has read down to the bottom
            if (a.unreadCount > 0) newSinceSeq.value = a.readSeq
        }
        viewModelScope.launch {
            // keep the conversation read while it is on screen (but only after the unread state was captured)
            anchor.await()
            combine(resumed, repo.newestIncomingSeq(conversationId)) { r, seq -> r to seq }.collect { (r, seq) ->
                if (r && seq != null) runCatching { engine.markRead(conversationId) }
            }
        }
        // something shared from another app: the text in the composer, the files in the preview strip
        fun takeShare() {
            pendingShares.take(conversationId)?.let { share ->
                share.text?.let { if (it.isNotBlank()) draft.value = (draft.value + " " + it).trim() }
                viewModelScope.launch { stage(share.files.map { Uri.fromFile(it) }) }
            }
        }
        takeShare()
        viewModelScope.launch { pendingShares.arrivals.collect { if (it == conversationId) takeShare() } }
        viewModelScope.launch {
            // restore the unsent text, then keep it stored while the user types (not while a sent message is edited)
            repo.draft(conversationId)?.let { if (draft.value.isEmpty() && editing.value == null) draft.value = it }
            combine(draft, editing) { d, e -> if (e == null) d else null }
                .filterNotNull().drop(1).debounce(DRAFT_SAVE_DELAY_MS).distinctUntilChanged()
                .collect { repo.saveDraft(conversationId, it) }
        }
    }

    // --- screen lifecycle --------------------------------------------------------------------------------------------

    fun onResume() {
        active.enter(conversationId)
        notifier.cancel(conversationId)
        resumed.value = true
    }

    fun onPause() {
        flushDraft()
        active.leave(conversationId)
        resumed.value = false
        engine.sendTyping(conversationId, false)
        // Android stops background microphone use anyway (and a hidden recording is not something to keep running)
        if (recording.value != null) cancelRecording()
    }

    override fun onCleared() {
        flushDraft()
        active.leave(conversationId)
        recorder.cancel()
        player.stop()
    }

    /** Stores the unsent text now (the debounce would lose the last keystrokes when the screen goes away). */
    private fun flushDraft() {
        if (editing.value != null) return
        val text = draft.value
        appScope.launch { repo.saveDraft(conversationId, text) }
    }

    // --- the list ----------------------------------------------------------------------------------------------------

    /**
     * The screen reports whether the list shows the newest message at the bottom. Leaving the bottom starts counting the
     * messages that arrive meanwhile (for the jump-to-bottom button); getting back resets the count.
     */
    fun onAtBottom(atBottom: Boolean) {
        if (atBottom) {
            newSinceSeq.value = null
        } else if (newSinceSeq.value == null) {
            viewModelScope.launch { if (newSinceSeq.value == null) newSinceSeq.value = repo.maxSeq(conversationId) }
        }
    }

    /** Restarts the list at the newest message (it was showing an older part, after a jump to a quoted message). */
    fun jumpToLatest() {
        pagerStart.update { PagerStart(null, it.generation + 1) }
    }

    /** Restarts the list a little above [messageId] so that it is loaded (a quoted message far back); false: not found. */
    fun loadAround(messageId: String) {
        viewModelScope.launch {
            val offset = repo.offsetOf(conversationId, messageId) ?: return@launch
            pagerStart.update { PagerStart((offset - LOAD_AROUND_CONTEXT).coerceAtLeast(0), it.generation + 1) }
        }
    }

    /** A message came into view: fetch or check its attachments (photos and voice messages are fetched automatically). */
    fun onMessageShown(messageId: String) {
        viewModelScope.launch {
            val m = store.getMessage(messageId) ?: return@launch
            if (m.attachments.isNotEmpty()) scheduleWork(listOf(m))
        }
    }

    fun cycleVoiceSpeed() = player.cycleSpeed()

    // --- composer ----------------------------------------------------------------------------------------------------

    fun onDraft(text: String) {
        draft.value = text
        if (text.isNotEmpty() && editing.value == null) {
            engine.sendTyping(conversationId, true)
            typingJob?.cancel()
            typingJob = viewModelScope.launch {
                delay(4_000)
                engine.sendTyping(conversationId, false)
            }
        }
    }

    fun send() {
        if (staged.value.isNotEmpty() && editing.value == null) {
            sendStaged()
            return
        }
        val text = draft.value.trim()
        if (text.isEmpty() || sending.value) return
        sending.value = true
        viewModelScope.launch {
            try {
                val editId = editing.value
                if (editId != null) {
                    engine.editMessage(editId, text)
                } else {
                    engine.sendText(conversationId, text, replyTo.value)
                }
                draft.value = ""
                replyTo.value = null
                editing.value = null
                repo.saveDraft(conversationId, "")
                engine.sendTyping(conversationId, false)
                work.enqueueOutbox()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _effects.tryEmit(ConversationEffect.Error(e.toUiError(), e.message))
            } finally {
                sending.value = false
            }
        }
    }

    fun startReply(messageId: String) {
        editing.value = null
        replyTo.value = messageId
    }

    fun cancelReply() {
        replyTo.value = null
    }

    fun startEdit(messageId: String) {
        viewModelScope.launch {
            val m = store.getMessage(messageId) ?: return@launch
            replyTo.value = null
            editing.value = messageId
            draft.value = m.text.orEmpty()
        }
    }

    fun cancelEdit() {
        editing.value = null
        draft.value = ""
    }

    fun delete(messageId: String) {
        viewModelScope.launch {
            try {
                engine.deleteMessage(messageId)
                work.enqueueOutbox()
            } catch (e: Exception) {
                _effects.tryEmit(ConversationEffect.Error(e.toUiError(), e.message))
            }
        }
    }

    /**
     * "Delete for me": the message disappears from this phone only (nothing is sent), together with its downloaded files and
     * the copies of attachments you sent. It is remembered as deleted, so it does not come back with a later sync.
     */
    fun deleteForMe(messageId: String) {
        viewModelScope.launch {
            try {
                val removed = engine.deleteMessageForMe(messageId)
                removed?.attachments?.forEach { preparer.deleteOutgoingCopy(it.localPath) }
                if (replyTo.value == messageId) replyTo.value = null
                if (editing.value == messageId) cancelEdit()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _effects.tryEmit(ConversationEffect.Error(e.toUiError(), e.message))
            }
        }
    }

    fun retry(messageId: String) {
        viewModelScope.launch {
            try {
                engine.retryMessage(messageId)
                work.enqueueOutbox()
            } catch (e: Exception) {
                _effects.tryEmit(ConversationEffect.Error(e.toUiError(), e.message))
            }
        }
    }

    // --- attachments -------------------------------------------------------------------------------------------------

    /** Photos, videos or documents the user picked: shown above the composer, sent when the user presses send. */
    fun attach(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch { stage(uris) }
    }

    private suspend fun stage(uris: List<Uri>) {
        val room = MAX_ATTACHMENTS - staged.value.size
        if (uris.size > room) _effects.tryEmit(ConversationEffect.Notice(se.swahnchat.app.R.string.staged_too_many))
        val picked = uris.take(room.coerceAtLeast(0)).map { preparer.describe(it) }
        staged.update { cur ->
            cur + picked.map { StagedUi(java.util.UUID.randomUUID().toString(), it.uri, attachmentKindOf(it.kind), it.name) }
        }
    }

    /** Takes a chosen file out of the preview strip. */
    fun unstage(id: String) {
        staged.value.firstOrNull { it.id == id }?.let { preparer.deleteStagedCopy(it.uri) }
        staged.update { cur -> cur.filterNot { it.id == id } }
    }

    /** Prepares (compresses, thumbnails) everything in the preview strip and sends it as one message with the text as caption. */
    private fun sendStaged() {
        val items = staged.value
        if (items.isEmpty() || sending.value) return
        sending.value = true
        viewModelScope.launch {
            val prepared = ArrayList<OutgoingAttachment>()
            try {
                for (item in items) prepared += preparer.prepare(item.uri)
                val caption = draft.value.trim().ifEmpty { null }
                engine.sendAttachments(conversationId, prepared, caption, replyTo.value)
                draft.value = ""
                staged.value = emptyList()
                items.forEach { preparer.deleteStagedCopy(it.uri) }
                repo.saveDraft(conversationId, "")
                replyTo.value = null
                work.enqueueOutbox()
            } catch (e: CancellationException) {
                prepared.forEach { it.file.delete() }
                throw e
            } catch (e: AttachmentTooLargeException) {
                prepared.forEach { it.file.delete() }
                _effects.tryEmit(ConversationEffect.Error(UiError.TOO_LARGE))
            } catch (e: UnusableAttachmentException) {
                prepared.forEach { it.file.delete() }
                _effects.tryEmit(ConversationEffect.Error(UiError.INVALID_INPUT, e.message))
            } catch (e: Exception) {
                prepared.forEach { it.file.delete() }
                _effects.tryEmit(ConversationEffect.Error(e.toUiError(), e.message))
            } finally {
                sending.value = false
            }
        }
    }

    // --- reactions, timer, disappearing ---------------------------------------------------------------------------------

    /** React with [emoji], or take your reaction back if it already is [emoji]. */
    fun react(messageId: String, emoji: String) {
        viewModelScope.launch {
            try {
                engine.react(messageId, emoji)
                work.enqueueOutbox()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _effects.tryEmit(ConversationEffect.Error(e.toUiError(), e.message))
            }
        }
    }

    /** Sets the disappearing-messages timer (0: off). */
    fun setTimer(seconds: Long) {
        viewModelScope.launch {
            try {
                engine.setDisappearingTimer(conversationId, seconds)
                work.enqueueOutbox()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _effects.tryEmit(ConversationEffect.Error(e.toUiError(), e.message))
            }
        }
    }

    /**
     * A message with a timer is on screen: its clock starts now (once; Signal's rule). Only while the screen is in the
     * foreground, so messages that arrive while the phone is in a pocket are not counted as seen.
     */
    fun onMessageDisplayed(messageId: String) {
        if (!resumed.value || !displayedIds.add(messageId)) return
        viewModelScope.launch {
            val earliest = engine.markDisplayed(listOf(messageId))
            expiry.onDisplayed(earliest)
        }
    }

    // --- voice messages ----------------------------------------------------------------------------------------------

    /** Starts recording (RECORD_AUDIO must be granted). */
    fun startRecording(): Boolean {
        if (recording.value != null) return true
        player.stop()
        if (!recorder.start()) {
            _effects.tryEmit(ConversationEffect.Error(UiError.UNKNOWN, "microphone"))
            return false
        }
        recording.value = RecordingUi(0, locked = false)
        recordingTicker?.cancel()
        recordingTicker = viewModelScope.launch {
            while (recorder.isRecording) {
                val elapsed = recorder.elapsedMs()
                recording.update { it?.copy(elapsedMs = elapsed) }
                if (elapsed >= VoiceRecorder.MAX_DURATION_MS) {
                    finishRecording(send = true)
                    break
                }
                delay(200)
            }
        }
        return true
    }

    /** A short tap: keep recording, the bar then shows cancel and send buttons. */
    fun lockRecording() {
        recording.update { it?.copy(locked = true) }
    }

    fun cancelRecording() {
        recordingTicker?.cancel()
        recorder.cancel()
        recording.value = null
    }

    fun finishRecording(send: Boolean) {
        recordingTicker?.cancel()
        recording.value = null
        if (!send) {
            recorder.cancel()
            return
        }
        val result = recorder.finish() ?: return
        viewModelScope.launch {
            try {
                engine.sendAttachments(conversationId, listOf(preparer.voice(result.file, result.durationMs)), null, replyTo.value)
                replyTo.value = null
                work.enqueueOutbox()
            } catch (e: Exception) {
                result.file.delete()
                _effects.tryEmit(ConversationEffect.Error(e.toUiError(), e.message))
            }
        }
    }

    // --- downloads and playback --------------------------------------------------------------------------------------

    fun download(messageId: String, attachment: AttachmentUi) {
        val id = attachment.id
        if (downloads.value.containsKey(id)) return
        downloads.update { it + (id to null) }
        viewModelScope.launch {
            try {
                val size = attachment.size.takeIf { it > 0 }
                engine.downloadAttachment(messageId, id) { bytes ->
                    if (size != null) {
                        val fraction = (bytes.toFloat() / size).coerceIn(0f, 1f)
                        // every change rebuilds the visible rows: report in 2% steps, not per chunk
                        downloads.update { cur ->
                            val before = cur[id]
                            if (id in cur && (before == null || fraction >= before + PROGRESS_STEP || fraction >= 1f)) cur + (id to fraction) else cur
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: MessageGoneException) {
                // deleted while it was downloading: nothing to tell
            } catch (e: java.io.IOException) {
                _effects.tryEmit(ConversationEffect.Error(UiError.NETWORK, e.message))
            } catch (e: Exception) {
                _effects.tryEmit(ConversationEffect.Error(UiError.UNKNOWN, e.message))
            } finally {
                downloads.update { it - id }
                missing.update { it - id }
                validated.update { it - id }
            }
        }
    }

    fun open(attachment: AttachmentUi) {
        val path = (attachment.transfer as? se.swahnchat.app.ui.model.TransferUi.Ready)?.path ?: return
        _effects.tryEmit(ConversationEffect.OpenFile(path, attachment.mime, attachment.name))
    }

    fun toggleVoice(attachment: AttachmentUi) {
        val path = (attachment.transfer as? se.swahnchat.app.ui.model.TransferUi.Ready)?.path ?: return
        player.toggle(attachment.id, File(path))
    }

    fun jumpTo(messageId: String) {
        _effects.tryEmit(ConversationEffect.ScrollTo(messageId))
    }

    /** Checks decrypted files before they are shown, and fetches photos and voice messages automatically. */
    private fun scheduleWork(list: List<StoredMessage>) {
        val toValidate = ArrayList<Pair<String, Pair<String, String>>>() // attachment id -> (path, kind)
        val gone = HashSet<String>()
        for (m in list) for (a in m.attachments) {
            val path = a.localPath
            if (a.downloadState == DownloadState.DONE && path != null) {
                if (a.id in missing.value) {
                    continue
                } else if (validated.value[a.id] == null) {
                    if (File(path).isFile) {
                        if (validating.add(a.id)) toValidate += a.id to (path to a.kind)
                    } else {
                        gone += a.id
                    }
                }
            } else if (a.downloadState == DownloadState.NONE && a.key != null && shouldAutoDownload(a.kind, a.size) && autoRequested.add(a.id)) {
                download(m.id, AttachmentUi(a.id, AttachmentKindUi.FILE, a.mime, a.name, a.size, null, null, null, null, se.swahnchat.app.ui.model.TransferUi.Remote))
            }
        }
        if (gone.isNotEmpty()) {
            missing.update { it + gone }
            // The file is gone: let the engine fetch it again like a new attachment.
            for (m in list) for (a in m.attachments) if (a.id in gone && shouldAutoDownload(a.kind, a.size) && autoRequested.add(a.id)) {
                viewModelScope.launch {
                    store.updateMessage(m.id) { cur ->
                        cur.copy(attachments = cur.attachments.map { x -> if (x.id == a.id) x.copy(downloadState = DownloadState.NONE, localPath = null) else x })
                    }
                    missing.update { it - a.id }
                    validated.update { it - a.id }
                }
            }
        }
        if (toValidate.isNotEmpty()) {
            viewModelScope.launch {
                val results = withContext(Dispatchers.Default) {
                    toValidate.associate { (id, pk) -> id to MediaValidator.isValid(File(pk.first), pk.second) }
                }
                validated.update { it + results }
                validating.removeAll(results.keys)
            }
        }
    }

    /**
     * Whether an attachment is fetched without a tap: the user's setting for its kind (Always / Wi-Fi only / Never) and the
     * network as it is right now. A declined attachment is not remembered as requested, so it is looked at again when it comes
     * into view later (on Wi-Fi, or after the setting changed); tapping it always downloads it.
     */
    private fun shouldAutoDownload(kind: String, size: Long) =
        AutoDownloadPolicy.shouldDownload(prefs.autoDownload.value, kind, size, connectivity.isMetered())

    private companion object {
        const val PAGE_SIZE = 40
        const val MAX_INITIAL_LOAD = 400
        const val LOAD_AROUND_CONTEXT = 10
        const val DRAFT_SAVE_DELAY_MS = 500L
        const val PROGRESS_STEP = 0.02f
        const val MAX_ATTACHMENTS = 10
    }
}
