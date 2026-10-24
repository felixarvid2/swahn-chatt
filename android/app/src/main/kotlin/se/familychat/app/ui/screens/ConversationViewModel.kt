package se.familychat.app.ui.screens

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.familychat.app.data.ChatRepository
import se.familychat.app.data.UiError
import se.familychat.app.data.toUiError
import se.familychat.app.media.AttachmentPreparer
import se.familychat.app.media.AttachmentTooLargeException
import se.familychat.app.media.MediaValidator
import se.familychat.app.media.UnusableAttachmentException
import se.familychat.app.media.VoicePlayer
import se.familychat.app.media.VoiceRecorder
import se.familychat.app.platform.ActiveConversation
import se.familychat.app.platform.notify.Notifier
import se.familychat.app.platform.work.WorkScheduler
import se.familychat.app.ui.components.PlaybackUi
import se.familychat.app.ui.model.AttachmentKindUi
import se.familychat.app.ui.model.AttachmentUi
import se.familychat.app.ui.model.AvatarUi
import se.familychat.app.ui.model.ChatRow
import se.familychat.app.ui.model.ChatRowContext
import se.familychat.app.ui.model.ReplyUi
import se.familychat.app.ui.model.attachmentKindOf
import se.familychat.app.ui.model.buildChatRows
import se.familychat.core.client.DownloadState
import se.familychat.core.client.FamilyChatEngine
import se.familychat.core.client.LocalStore
import se.familychat.core.client.OutgoingAttachment
import se.familychat.core.client.StoredMessage
import se.familychat.core.model.ConversationType
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
    val rows: List<ChatRow> = emptyList(),
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
)

sealed interface ConversationEffect {
    data class Error(val error: UiError, val detail: String? = null) : ConversationEffect
    data class OpenFile(val path: String, val mime: String, val name: String?) : ConversationEffect
    data class ScrollTo(val messageId: String) : ConversationEffect
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ConversationViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val engine: FamilyChatEngine,
    private val repo: ChatRepository,
    private val store: LocalStore,
    private val preparer: AttachmentPreparer,
    private val recorder: VoiceRecorder,
    private val player: VoicePlayer,
    private val active: ActiveConversation,
    private val notifier: Notifier,
    private val work: WorkScheduler,
) : ViewModel() {
    val conversationId: String = checkNotNull(savedState["id"])

    private val draft = MutableStateFlow("")
    private val replyTo = MutableStateFlow<String?>(null)
    private val editing = MutableStateFlow<String?>(null)
    private val recording = MutableStateFlow<RecordingUi?>(null)
    private val sending = MutableStateFlow(false)
    private val windowSize = MutableStateFlow(INITIAL_WINDOW)
    private val downloads = MutableStateFlow<Map<String, Float?>>(emptyMap())
    private val validated = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    private val missing = MutableStateFlow<Set<String>>(emptySet())
    private val autoRequested = ConcurrentHashMap.newKeySet<String>()
    private val resumed = MutableStateFlow(false)
    private var recordingTicker: Job? = null
    private var typingJob: Job? = null

    private val _effects = MutableSharedFlow<ConversationEffect>(extraBufferCapacity = 8)
    val effects: SharedFlow<ConversationEffect> = _effects.asSharedFlow()

    val playback: StateFlow<PlaybackUi> = player.state

    private val messages = windowSize.flatMapLatest { repo.messages(conversationId, it) }

    private val rowsFlow = combine(messages, repo.userMap(), engine.account, downloads, validated, missing) { values ->
        @Suppress("UNCHECKED_CAST") val list = values[0] as List<StoredMessage>
        @Suppress("UNCHECKED_CAST") val users = values[1] as Map<String, se.familychat.core.model.User>
        val account = values[2] as se.familychat.core.client.Account?
        @Suppress("UNCHECKED_CAST") val dl = values[3] as Map<String, Float?>
        @Suppress("UNCHECKED_CAST") val ok = values[4] as Map<String, Boolean>
        @Suppress("UNCHECKED_CAST") val gone = values[5] as Set<String>
        val me = account?.userId.orEmpty()
        val isGroup = store.getConversation(conversationId)?.type == ConversationType.GROUP
        val inWindow = list.mapTo(HashSet()) { it.id }
        val referenced = list.mapNotNull { it.replyTo }.filter { it !in inWindow }.distinct()
            .mapNotNull { store.getMessage(it) }.associateBy { it.id }
        scheduleWork(list)
        buildChatRows(list, ChatRowContext(me, isGroup, users, referenced, dl, ok, gone))
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
            typingNames, if (isGroup) null else other?.userId,
        )
    }

    private class Header(
        val title: String, val isGroup: Boolean, val memberCount: Int, val avatar: AvatarUi,
        val typingNames: List<String>, val otherUserId: String?,
    )

    private val composer = combine(draft, replyTo, editing, recording, sending) { d, r, e, rec, s -> Composer(d, r, e, rec, s) }

    private class Composer(val draft: String, val replyTo: String?, val editing: String?, val recording: RecordingUi?, val sending: Boolean)

    val state: StateFlow<ConversationUiState> = combine(header, rowsFlow, composer) { h, rows, c ->
        if (h == null) return@combine ConversationUiState(removed = true, loaded = true)
        val reply = c.replyTo?.let { id ->
            rows.filterIsInstance<ChatRow.Message>().firstOrNull { it.message.id == id }?.message?.let {
                ReplyUi(it.id, if (it.outgoing) null else it.senderName ?: h.title, it.text, it.attachments.firstOrNull()?.kind, it.deleted)
            } ?: store.getMessage(id)?.let { ReplyUi(it.id, null, it.text, it.attachments.firstOrNull()?.let { a -> attachmentKindOf(a.kind) }, it.deleted) }
        }
        ConversationUiState(
            title = h.title, isGroup = h.isGroup, memberCount = h.memberCount, avatar = h.avatar, rows = rows,
            typingNames = h.typingNames, otherUserId = h.otherUserId, replyTo = reply, editingMessageId = c.editing,
            draft = c.draft, recording = c.recording, sending = c.sending, loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationUiState())

    init {
        viewModelScope.launch {
            // keep the conversation read while it is on screen
            combine(resumed, messages) { r, m -> r to m.lastOrNull { !it.outgoing }?.seq }.collect { (r, seq) ->
                if (r && seq != null) runCatching { engine.markRead(conversationId) }
            }
        }
    }

    // --- screen lifecycle --------------------------------------------------------------------------------------------

    fun onResume() {
        active.enter(conversationId)
        notifier.cancel(conversationId)
        resumed.value = true
    }

    fun onPause() {
        active.leave(conversationId)
        resumed.value = false
        engine.sendTyping(conversationId, false)
        // Android stops background microphone use anyway (and a hidden recording is not something to keep running)
        if (recording.value != null) cancelRecording()
    }

    override fun onCleared() {
        active.leave(conversationId)
        recorder.cancel()
        player.stop()
    }

    fun loadOlder() {
        windowSize.update { it + WINDOW_STEP }
    }

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

    /** Photos, videos or documents the user picked. They are prepared (compressed, thumbnailed) and sent as one message. */
    fun attach(uris: List<Uri>) {
        if (uris.isEmpty()) return
        sending.value = true
        viewModelScope.launch {
            val prepared = ArrayList<OutgoingAttachment>()
            try {
                for (uri in uris.take(MAX_ATTACHMENTS)) prepared += preparer.prepare(uri)
                val caption = draft.value.trim().ifEmpty { null }
                engine.sendAttachments(conversationId, prepared, caption, replyTo.value)
                draft.value = ""
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
                    if (size != null) downloads.update { cur -> if (id in cur) cur + (id to (bytes.toFloat() / size).coerceIn(0f, 1f)) else cur }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _effects.tryEmit(ConversationEffect.Error(if (e is java.io.IOException) UiError.NETWORK else UiError.UNKNOWN, e.message))
            } finally {
                downloads.update { it - id }
                missing.update { it - id }
                validated.update { it - id }
            }
        }
    }

    fun open(attachment: AttachmentUi) {
        val path = (attachment.transfer as? se.familychat.app.ui.model.TransferUi.Ready)?.path ?: return
        _effects.tryEmit(ConversationEffect.OpenFile(path, attachment.mime, attachment.name))
    }

    fun toggleVoice(attachment: AttachmentUi) {
        val path = (attachment.transfer as? se.familychat.app.ui.model.TransferUi.Ready)?.path ?: return
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
                        toValidate += a.id to (path to a.kind)
                    } else {
                        gone += a.id
                    }
                }
            } else if (a.downloadState == DownloadState.NONE && (!m.outgoing || a.key != null) && autoRequested.add(a.id) && shouldAutoDownload(a.kind, a.size)) {
                download(m.id, AttachmentUi(a.id, AttachmentKindUi.FILE, a.mime, a.name, a.size, null, null, null, null, se.familychat.app.ui.model.TransferUi.Remote))
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
            }
        }
    }

    private fun shouldAutoDownload(kind: String, size: Long) = when (kind) {
        "image" -> size <= AUTO_IMAGE_BYTES
        "voice" -> size <= AUTO_VOICE_BYTES
        else -> false
    }

    private companion object {
        const val INITIAL_WINDOW = 80
        const val WINDOW_STEP = 80
        const val MAX_ATTACHMENTS = 10
        const val AUTO_IMAGE_BYTES = 20L * 1024 * 1024
        const val AUTO_VOICE_BYTES = 8L * 1024 * 1024
    }
}
