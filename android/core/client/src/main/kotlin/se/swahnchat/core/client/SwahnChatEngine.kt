package se.swahnchat.core.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import se.swahnchat.core.crypto.ProtocolStorage
import se.swahnchat.core.crypto.SafetyNumber
import se.swahnchat.core.crypto.SafetyNumbers
import se.swahnchat.core.model.AddMembersRequest
import se.swahnchat.core.model.AttachmentPurpose
import se.swahnchat.core.model.AuthResponse
import se.swahnchat.core.model.CallSignal
import se.swahnchat.core.model.ChangePasswordRequest
import se.swahnchat.core.model.Device
import se.swahnchat.core.model.PushRegistration
import se.swahnchat.core.model.User
import se.swahnchat.core.model.Conversation
import se.swahnchat.core.model.ConversationType
import se.swahnchat.core.model.CreateConversationRequest
import se.swahnchat.core.model.CreateInvitationRequest
import se.swahnchat.core.model.CreatedInvitation
import se.swahnchat.core.model.LoginRequest
import se.swahnchat.core.model.MarkReadRequest
import se.swahnchat.core.model.RedeemInviteRequest
import se.swahnchat.core.model.ServerFrame
import se.swahnchat.core.model.SetRoleRequest
import se.swahnchat.core.model.TurnCredentials
import se.swahnchat.core.model.TypingState
import se.swahnchat.core.network.ApiClient
import se.swahnchat.core.network.ApiException
import se.swahnchat.core.network.BackoffPolicy
import se.swahnchat.core.network.BaseUrl
import se.swahnchat.core.network.LogoutReason
import se.swahnchat.core.network.RealtimeConnection
import se.swahnchat.core.network.ServerConnection
import se.swahnchat.core.network.TokenProvider
import se.swahnchat.core.network.Tokens
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64

/**
 * The Swahn Chat messaging engine: the single entry point the app's repositories and ViewModels talk to.
 *
 * It owns no UI state. Everything the UI shows is read from [LocalStore] (which the app implements on Room) and the
 * engine tells the UI what changed through [events]. All functions are `suspend` and thread-safe; they throw
 * [ApiException] (server said no), [java.io.IOException] (no connection) or `IllegalArgumentException` (bad input).
 *
 * Life cycle:
 * 1. App start: [restoreSession] (returns the stored [Account] or null). If null, show login.
 * 2. [login] or [redeemInvite] creates a new device: keys are generated and uploaded, the directory is loaded.
 * 3. [startRealtime] while the UI is in the foreground (WebSocket + sync on hints); [sync] and [processOutbox] from
 *    push or WorkManager when the app is in the background.
 * 4. [logout] revokes the device on the server and wipes all local data.
 *
 * Sending never blocks on the network: [sendText] & co. write a PENDING message and an outbox entry, then a background
 * worker delivers it (and retries when the connection returns). Use [processOutbox] to deliver synchronously.
 */
class SwahnChatEngine(
    store: LocalStore,
    protocolStorage: ProtocolStorage,
    private val tokens: TokenProvider,
    private val config: EngineConfig,
    private val scope: CoroutineScope,
    /** Creates the network connection for a server address; tests substitute a fake. */
    private val connectionFactory: (serverUrl: String) -> ServerConnection =
        { url -> ApiClient.create(url, tokens, config.allowInsecureLan) },
) {
    private val ctx = EngineContext(store, protocolStorage, config)
    private val directory = DeviceDirectory(ctx)
    private val sender = SendPipeline(ctx, directory)
    private val syncer = SyncProcessor(ctx, directory)
    private val attachments = AttachmentService(ctx)
    private val sessionMutex = Mutex()

    private val _account = MutableStateFlow<Account?>(null)

    /** The signed-in account, or null. */
    val account: StateFlow<Account?> = _account.asStateFlow()

    /** Change notifications; see [EngineEvent]. */
    val events: SharedFlow<EngineEvent> = ctx.events.asSharedFlow()

    /**
     * Call signals from the other member of a direct conversation or from this account's other devices, already
     * decrypted and checked (sender, conversation, age). Never replayed and never stored: collect it from app start.
     */
    val callSignals: SharedFlow<CallSignalEvent> = ctx.callSignals.asSharedFlow()

    private val _typing = MutableStateFlow<TypingMap>(emptyMap())

    /** conversationId -> user ids currently typing (other people only). */
    val typing: StateFlow<TypingMap> = _typing.asStateFlow()

    private val _realtimeState = MutableStateFlow(se.swahnchat.core.network.RealtimeState.DISCONNECTED)

    /** State of the WebSocket (for a "connecting..." indicator). */
    val realtimeState: StateFlow<se.swahnchat.core.network.RealtimeState> = _realtimeState.asStateFlow()

    private var realtime: RealtimeConnection? = null
    private var realtimeJob: Job? = null
    private var workerJob: Job? = null
    private var sessionWatchJob: Job? = null
    private val syncRequests = Channel<Unit>(Channel.CONFLATED)
    private val outboxRequests = Channel<Unit>(Channel.CONFLATED)
    private val scheduleChanged = Channel<Unit>(Channel.CONFLATED)
    private val typingJobs = HashMap<Pair<String, String>, Job>()
    private val lastTypingSent = HashMap<String, Long>()

    // =========================================================================================================
    // Session
    // =========================================================================================================

    /**
     * Loads the stored account and reconnects to its server. Returns null if there is no account (or its tokens are
     * gone, in which case local data is wiped so the next login starts clean).
     */
    suspend fun restoreSession(): Account? = sessionMutex.withLock {
        val stored = ctx.store.getAccount() ?: return@withLock null
        if (tokens.getTokens() == null) {
            wipeLocal()
            return@withLock null
        }
        attach(stored, connectionFactory(stored.serverUrl))
        stored
    }

    /**
     * Signs in on this device (registering it as a new device on the server). [deviceName] overrides the configured name
     * (the login screen lets the user choose one).
     */
    suspend fun login(
        serverUrl: String,
        username: String,
        password: String,
        deviceName: String = config.deviceName,
    ): Account = sessionMutex.withLock {
        val url = BaseUrl.normalize(serverUrl, config.allowInsecureLan).toString()
        val connection = connectionFactory(url)
        val auth = connection.api.login(LoginRequest(username.trim().lowercase(), password, deviceName.trim().ifEmpty { config.deviceName }))
        completeSignIn(url, connection, auth)
    }

    /** Creates an account from an invitation code and signs in. */
    suspend fun redeemInvite(
        serverUrl: String,
        code: String,
        username: String,
        password: String,
        displayName: String,
        deviceName: String = config.deviceName,
    ): Account = sessionMutex.withLock {
        val url = BaseUrl.normalize(serverUrl, config.allowInsecureLan).toString()
        val connection = connectionFactory(url)
        val auth = connection.api.redeemInvite(
            RedeemInviteRequest(
                code.trim(), username.trim().lowercase(), password, displayName.trim(),
                deviceName.trim().ifEmpty { config.deviceName },
            ),
        )
        completeSignIn(url, connection, auth)
    }

    private suspend fun completeSignIn(url: String, connection: ServerConnection, auth: AuthResponse): Account {
        wipeLocal() // a previous account must not leak into this one
        tokens.saveTokens(Tokens(auth.accessToken, auth.refreshToken, auth.accessExpiresAt, auth.refreshExpiresAt))
        val account = Account(
            serverUrl = url, userId = auth.user.id, deviceId = auth.device.id, deviceNumber = auth.device.number,
            username = auth.user.username, displayName = auth.user.displayName, isAdmin = auth.user.isAdmin,
            deviceName = auth.device.name,
        )
        ctx.store.saveAccount(account)
        ctx.store.upsertUsers(listOf(auth.user))
        attach(account, connection)
        try {
            ensureKeys()
            refreshDirectory()
        } catch (e: IOException) {
            // Signed in; keys and directory are retried by startRealtime()/sync. Nothing to roll back.
        }
        return account
    }

    private fun attach(account: Account, connection: ServerConnection) {
        ctx.account = account
        ctx.connection = connection
        _account.value = account
        sessionWatchJob?.cancel()
        sessionWatchJob = scope.launch {
            connection.sessionEvents.collect { reason ->
                stopRealtime()
                ctx.emit(EngineEvent.SessionEnded(reason))
            }
        }
        if (config.startWorkers) startWorkers()
    }

    /**
     * Signs out: tells the server to revoke this device (best effort), stops all background work and deletes every
     * local row, key, session and downloaded attachment. Safe to call when already signed out (e.g. after
     * [EngineEvent.SessionEnded]).
     */
    suspend fun logout() = sessionMutex.withLock {
        stopRealtime()
        val api = ctx.connection?.api
        if (api != null && tokens.getTokens() != null) {
            try {
                api.logout()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // offline or already revoked: wipe locally anyway
            }
        }
        wipeLocal()
        tokens.onLoggedOut(LogoutReason.USER)
    }

    private suspend fun wipeLocal() {
        stopRealtime()
        workerJob?.cancel(); workerJob = null
        sessionWatchJob?.cancel(); sessionWatchJob = null
        ctx.store.clearAll()
        ctx.protocolStorage.clearAll()
        config.attachmentDir.deleteRecursively()
        config.tempDir.deleteRecursively()
        ctx.account = null
        ctx.connection = null
        _account.value = null
        _typing.value = emptyMap()
    }

    // =========================================================================================================
    // Keys
    // =========================================================================================================

    /**
     * Uploads this device's keys on first run and afterwards tops up one-time prekeys (below 20 on the server) and rotates
     * the signed prekey (every 30 days). Cheap; call it on app start and after reconnects.
     */
    suspend fun ensureKeys() {
        val km = ctx.keyManager
        if (!km.hasIdentity()) {
            km.createIdentity()
        }
        val uploaded = ctx.store.getMeta(META_KEYS_UPLOADED) == "1"
        if (!uploaded) {
            ctx.api.uploadKeys(if (ctx.protocolStorage.loadSignedPreKeys().isEmpty()) km.buildInitialUpload() else km.buildFullReupload())
            ctx.store.setMeta(META_KEYS_UPLOADED, "1")
            return
        }
        val status = ctx.api.keyStatus()
        km.buildMaintenanceUpload(status)?.let { ctx.api.uploadKeys(it) }
    }

    // =========================================================================================================
    // Directory and conversations
    // =========================================================================================================

    /** Reloads users, conversations (removing ones we are no longer in) and the devices of everybody we talk to. */
    suspend fun refreshDirectory() {
        val account = ctx.requireAccount()
        directory.refreshUsers()
        val remote = ctx.api.listConversations()
        val remoteIds = remote.mapTo(HashSet()) { it.id }
        for (c in ctx.store.getConversations()) if (c.id !in remoteIds) {
            ctx.store.removeConversation(c.id)
            ctx.emit(EngineEvent.ConversationRemoved(c.id))
        }
        remote.forEach { directory.applyConversation(it) }
        val userIds = (remote.flatMap { c -> c.members.map { it.userId } } + account.userId).distinct()
        for (u in userIds) directory.refreshDevices(u)
        remote.forEach { ctx.emit(EngineEvent.ConversationChanged(it.id)) }
    }

    /** Re-reads one user's devices (e.g. before showing a safety number). */
    suspend fun refreshDevices(userId: String) {
        directory.refreshDevices(userId)
    }

    suspend fun createDirect(otherUserId: String): Conversation {
        val c = ctx.api.createConversation(CreateConversationRequest(ConversationType.DIRECT, null, listOf(otherUserId)))
        directory.applyConversation(c)
        c.members.forEach { directory.refreshDevices(it.userId) }
        ctx.emit(EngineEvent.ConversationChanged(c.id))
        return c
    }

    suspend fun createGroup(name: String, memberUserIds: List<String>, avatarAttachmentId: String? = null): Conversation {
        val c = ctx.api.createConversation(
            CreateConversationRequest(ConversationType.GROUP, name.trim(), memberUserIds, avatarAttachmentId),
        )
        directory.applyConversation(c)
        c.members.forEach { directory.refreshDevices(it.userId) }
        ctx.emit(EngineEvent.ConversationChanged(c.id))
        return c
    }

    suspend fun renameConversation(conversationId: String, name: String): Conversation =
        updated(ctx.api.updateConversation(conversationId, name = name.trim()))

    suspend fun addMembers(conversationId: String, userIds: List<String>): Conversation {
        val c = ctx.api.addMembers(conversationId, AddMembersRequest(userIds))
        userIds.forEach { directory.refreshDevices(it) }
        return updated(c)
    }

    suspend fun removeMember(conversationId: String, userId: String) {
        ctx.api.removeMember(conversationId, userId)
        updated(ctx.api.getConversation(conversationId)) // sessions with the removed user's devices are dropped on the next 409
    }

    /** Leaves a group (or removes ourselves); the conversation disappears locally. */
    suspend fun leave(conversationId: String) {
        val me = ctx.requireAccount().userId
        ctx.api.removeMember(conversationId, me)
        ctx.store.removeConversation(conversationId)
        ctx.emit(EngineEvent.ConversationRemoved(conversationId))
    }

    suspend fun setMemberRole(conversationId: String, userId: String, admin: Boolean): Conversation =
        updated(ctx.api.setMemberRole(conversationId, userId, SetRoleRequest(if (admin) "admin" else "member")))

    private suspend fun updated(c: Conversation): Conversation {
        directory.applyConversation(c)
        ctx.emit(EngineEvent.ConversationChanged(c.id))
        return c
    }

    /** Group admins only: sets the group picture from an already uploaded avatar ([uploadAvatar]). */
    suspend fun setConversationAvatar(conversationId: String, avatarAttachmentId: String): Conversation =
        updated(ctx.api.updateConversation(conversationId, avatarAttachmentId = avatarAttachmentId))

    suspend fun clearConversationAvatar(conversationId: String): Conversation =
        updated(ctx.api.updateConversation(conversationId, clearAvatar = true))

    // =========================================================================================================
    // Profile, devices, push
    // =========================================================================================================

    /** Uploads [bytes] (JPEG, PNG or WebP, at most 2 MiB) as an avatar and returns the attachment id. Avatars are not encrypted. */
    suspend fun uploadAvatar(bytes: ByteArray, mime: String = "image/jpeg"): String {
        require(bytes.size <= MAX_AVATAR_BYTES) { "Avatar is larger than 2 MiB" }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return ctx.api.uploadAttachment(bytes.toRequestBody(mime.toMediaType()), AttachmentPurpose.AVATAR, sha).id
    }

    /** Changes the display name and/or the avatar (an id from [uploadAvatar]) of this account. */
    suspend fun updateProfile(displayName: String? = null, avatarAttachmentId: String? = null, clearAvatar: Boolean = false): User {
        val user = ctx.api.updateMe(displayName?.trim(), avatarAttachmentId, clearAvatar)
        ctx.store.upsertUsers(listOf(user))
        val account = ctx.requireAccount()
        if (user.displayName != account.displayName) {
            val updated = account.copy(displayName = user.displayName)
            ctx.store.saveAccount(updated)
            ctx.account = updated
            _account.value = updated
        }
        ctx.emit(EngineEvent.DirectoryChanged(user.id))
        return user
    }

    /** The server revokes every other session and device of this account when this succeeds. */
    suspend fun changePassword(current: String, new: String) {
        ctx.api.changePassword(ChangePasswordRequest(current, new))
    }

    /** Active devices of this account (including this one: compare with [Account.deviceId]). */
    suspend fun myDevices(): List<Device> = ctx.api.listDevices(ctx.requireAccount().userId)

    /** Revokes one of this account's devices; it loses access at once. Not for the current device (use [logout]). */
    suspend fun revokeDevice(deviceId: String) {
        require(deviceId != ctx.requireAccount().deviceId) { "Use logout() for this device" }
        ctx.api.deleteDevice(deviceId)
    }

    /** Tells the server where to send content-free wake-ups (UnifiedPush endpoint of this device). */
    suspend fun registerPush(endpoint: String) {
        ctx.api.setPush(PushRegistration(endpoint = endpoint))
    }

    suspend fun clearPush() {
        ctx.api.clearPush()
    }

    /**
     * The avatar image [attachmentId] as a local file (downloaded once; avatar ids never change their content). Lives in
     * the attachment directory, so logout removes it.
     */
    suspend fun avatarFile(attachmentId: String): File {
        val safe = attachmentId.filter { it.isLetterOrDigit() || it == '-' }
        val dir = File(config.attachmentDir, "avatars")
        val target = File(dir, safe)
        if (target.isFile && target.length() > 0) return target
        return withContext(config.ioDispatcher) {
            dir.mkdirs()
            val part = File(dir, "$safe.part")
            part.delete()
            ctx.api.downloadAttachment(attachmentId, part)
            if (part.length() > MAX_AVATAR_BYTES) {
                part.delete()
                throw IOException("Avatar is larger than 2 MiB")
            }
            if (!part.renameTo(target)) throw IOException("Could not store avatar")
            target
        }
    }

    /** Admin only: creates an invitation code (shown once). */
    suspend fun createInvitation(note: String? = null, expiresInHours: Int? = null, admin: Boolean = false): CreatedInvitation =
        ctx.api.createInvitation(CreateInvitationRequest(note, expiresInHours, if (admin) true else null))

    // =========================================================================================================
    // Calls
    // =========================================================================================================

    /** Sends one call signal in a direct conversation right away (no outbox, no retry later); see [SendPipeline.sendSignal]. */
    suspend fun sendCallSignal(conversationId: String, signal: CallSignal) = sender.sendSignal(conversationId, signal)

    /**
     * Adds the history line of the call [callId] to the conversation, on this phone only: no request, no envelope, no outbox
     * entry. One per call (a repeated call id adds nothing); a conversation that is gone is ignored.
     */
    suspend fun recordCallNotice(conversationId: String, callId: String, notice: SystemEvent.Call) {
        val conversation = ctx.store.getConversation(conversationId) ?: return
        directory.postNotice(conversation, "sys-call-$callId", notice)
    }

    /** Fresh TURN/STUN credentials for one call (`GET /calls/turn`). */
    suspend fun fetchTurnCredentials(): TurnCredentials = ctx.api.turnCredentials()

    /** The other member of a direct conversation, or null if it is unknown or not direct. */
    suspend fun directPeer(conversationId: String): String? {
        val me = ctx.account?.userId ?: return null
        val c = ctx.store.getConversation(conversationId)?.takeIf { it.type == ConversationType.DIRECT } ?: return null
        return c.members.firstOrNull { it.userId != me }?.userId
    }

    /**
     * True when the device [deviceId] of [userId] is unverified and was created less than [maxAgeMs] ago by the server's
     * clock, or is not in the local device list (a call is only locked on to listed devices, so this is a safe default).
     */
    suspend fun isNewUnverifiedDevice(userId: String, deviceId: String, maxAgeMs: Long): Boolean {
        val device = ctx.store.getDevices(userId).firstOrNull { it.id == deviceId } ?: return true
        return !isVerified(deviceId) && ctx.serverNow() - device.createdAt < maxAgeMs
    }

    /** The current time on the server's clock as far as known (the local clock until a response told the difference). */
    fun serverNow(): Long = ctx.serverNow()

    // =========================================================================================================
    // Sending
    // =========================================================================================================

    /**
     * Queues a text message and returns its id immediately (the message is PENDING until the server has it). [mentions] are the
     * user ids named with `@` in [text] (a group only; each must be a member), carried inside the encrypted content. With
     * [sendAt] (epoch ms, one minute to 30 days ahead) the message stays on this phone until then (see [scheduledMessages]).
     */
    suspend fun sendText(
        conversationId: String,
        text: String,
        replyTo: String? = null,
        mentions: List<String> = emptyList(),
        sendAt: Long? = null,
    ): String = sender.enqueueText(conversationId, text, replyTo, mentions, sendAt).also { afterEnqueue(sendAt) }

    private fun afterEnqueue(sendAt: Long?) {
        if (sendAt != null) scheduleChanged.trySend(Unit) else requestOutbox()
    }

    /**
     * Queues a message with attachments. The files are read at send time and must stay in place until the message
     * leaves PENDING; they are never modified or deleted by the engine.
     */
    suspend fun sendAttachments(
        conversationId: String,
        files: List<OutgoingAttachment>,
        caption: String? = null,
        replyTo: String? = null,
        sendAt: Long? = null,
    ): String = sender.enqueueAttachments(
        conversationId,
        files.map {
            OutboxAttachment(it.file.absolutePath, it.mime, it.name ?: it.file.name, it.kind, it.width, it.height, it.durationMs, it.thumbnailBase64)
        },
        caption, replyTo, sendAt,
    ).also { afterEnqueue(sendAt) }

    // --- polls -----------------------------------------------------------------------------------------------------

    /** Sends a poll: [question] (up to 200 characters), 2 to 10 [options] (up to 100 each), [multiple] allows several choices. */
    suspend fun sendPoll(conversationId: String, question: String, options: List<String>, multiple: Boolean = false): String =
        sender.enqueuePoll(conversationId, question, options, multiple).also { requestOutbox() }

    /** Votes on a poll: [optionIds] replace your earlier vote (empty: withdraw it). Not possible once the poll is closed. */
    suspend fun votePoll(pollId: String, optionIds: List<Int>) {
        sender.enqueueVote(pollId, optionIds)
        requestOutbox()
    }

    /** The creator of a poll closes it; the votes stay, nobody can vote any more. */
    suspend fun closePoll(pollId: String) {
        sender.enqueueClosePoll(pollId)
        requestOutbox()
    }

    /** The votes that stand on a poll (a withdrawn vote is not among them). */
    suspend fun pollVotes(pollId: String): List<PollVote> = ctx.store.getPollVotes(pollId)

    // --- stars (local only) ------------------------------------------------------------------------------------------

    /** Stars or un-stars a message on this phone only; nothing is sent. */
    suspend fun setStarred(messageId: String, starred: Boolean) {
        val m = ctx.store.getMessage(messageId) ?: return
        if (starred) require(!m.deleted && !m.isSystem) { "That message cannot be starred" }
        ctx.store.setStarred(messageId, m.conversationId, starred, ctx.now())
        ctx.emit(EngineEvent.MessageChanged(m.conversationId, messageId))
    }

    suspend fun isStarred(messageId: String): Boolean = ctx.store.isStarred(messageId)

    /** Starred messages, the most recently starred first; of one conversation or (null) of all. */
    suspend fun starredMessages(conversationId: String? = null): List<StoredMessage> = ctx.store.starredMessages(conversationId)

    // --- scheduled messages ------------------------------------------------------------------------------------------

    /** Messages written for later and not sent yet, earliest first (of one conversation, or of all when null). */
    suspend fun scheduledMessages(conversationId: String? = null): List<StoredMessage> =
        ctx.store.getOutbox().filter { it.sendAt != null && (conversationId == null || it.conversationId == conversationId) }
            .sortedBy { it.sendAt }.mapNotNull { ctx.store.getMessage(it.messageId) }

    /** Moves a scheduled message to another time (a minute to 30 days from now). */
    suspend fun rescheduleMessage(messageId: String, sendAt: Long) {
        sender.reschedule(messageId, sendAt)
        scheduleChanged.trySend(Unit)
    }

    /** Sends a scheduled message now. */
    suspend fun sendScheduledNow(messageId: String) {
        sender.sendScheduledNow(messageId)
        scheduleChanged.trySend(Unit)
        requestOutbox()
    }

    /** Throws a scheduled message away (nothing was ever sent). Returns the removed message, whose copies of files the app may delete. */
    suspend fun cancelScheduled(messageId: String): StoredMessage? =
        sender.cancelScheduled(messageId).also { scheduleChanged.trySend(Unit) }

    /** When the next scheduled message is due (epoch ms), or null; for the app to arrange a wake-up (WorkManager). */
    suspend fun nextScheduledAt(): Long? = sender.nextScheduledAt()

    /** Group messages that mention this account and are still unread in [conversationId]. */
    suspend fun unreadMentions(conversationId: String): Int {
        val me = ctx.account?.userId ?: return 0
        return ctx.store.unreadMentionCount(conversationId, me)
    }

    /** Replaces the text of one of your own messages (everyone sees "edited"). */
    suspend fun editMessage(messageId: String, newText: String) {
        sender.enqueueEdit(messageId, newText)
        requestOutbox()
    }

    /** Deletes one of your own messages for everyone (content is wiped locally and on the recipients' devices). */
    suspend fun deleteMessage(messageId: String) {
        sender.enqueueDelete(messageId)
        requestOutbox()
    }

    /**
     * "Delete for me": removes the message from this device only. Nothing is sent, the other members keep it, and it is
     * remembered as deleted here so that a later sync, edit or receipt cannot bring it back. Downloaded attachment files go
     * too. Returns the removed message (the app may remove files of its own that it handed to the engine), or null if it was
     * already gone. System notices and messages that are still waiting to be sent cannot be removed this way.
     */
    suspend fun deleteMessageForMe(messageId: String): StoredMessage? {
        val m = ctx.store.getMessage(messageId) ?: return null
        require(!m.isSystem) { "A notice cannot be deleted" }
        require(!(m.outgoing && m.status == MessageStatus.PENDING)) { "The message has not been sent yet" }
        val removed = ctx.store.transaction { ctx.store.deleteMessageLocally(messageId, m.conversationId, ctx.now()) } ?: return null
        removed.attachments.forEach { ctx.deleteDownloadedFile(it.localPath) }
        ctx.emit(EngineEvent.MessageChanged(m.conversationId, messageId))
        return removed
    }

    /**
     * Deletes the decrypted copies of received attachments (of one conversation, or of all) and marks them as not downloaded,
     * so the messages stay and the files can be fetched again while the server still has them. Returns the bytes freed.
     * Files of messages you sent (the engine never owns those), downloads in progress and imported messages (their files came
     * from a history file and the server never had them, so they could not be fetched again) are left alone.
     */
    suspend fun clearDownloadedMedia(conversationId: String? = null): Long {
        var freed = 0L
        for (m in ctx.store.messagesWithAttachments(conversationId).filter { !it.imported }) {
            val owned = m.attachments.filter { it.downloadState == DownloadState.DONE && ctx.ownsFile(it.localPath) }
            if (owned.isEmpty()) continue
            for (a in owned) freed += ctx.deleteDownloadedFile(a.localPath)
            val ids = owned.mapTo(HashSet()) { it.id }
            ctx.store.updateMessage(m.id) { cur ->
                cur.copy(attachments = cur.attachments.map { if (it.id in ids) it.copy(downloadState = DownloadState.NONE, localPath = null) else it })
            }
            ctx.emit(EngineEvent.MessageChanged(m.conversationId, m.id))
        }
        return freed
    }

    // --- reactions -------------------------------------------------------------------------------------------------

    /**
     * Reacts to [messageId] with [emoji] (one single emoji), or takes your reaction back if it already is [emoji]; another
     * emoji replaces yours. Shown at once and sent encrypted to every device, like a message.
     */
    suspend fun react(messageId: String, emoji: String) {
        sender.enqueueReaction(messageId, emoji)
        requestOutbox()
    }

    /** The reactions that stand on [messageId], oldest first. */
    suspend fun reactionsOf(messageId: String): List<Reaction> = ctx.store.getReactions(messageId)

    // --- disappearing messages -------------------------------------------------------------------------------------

    /**
     * Sets the disappearing-messages timer of the conversation (0: off; otherwise 300 s to a year). The change goes to
     * the members as encrypted content and the server's pruning timer is patched too, but each phone's own timer follows only
     * the encrypted content of a member, never the server's field.
     */
    suspend fun setDisappearingTimer(conversationId: String, seconds: Long) {
        sender.enqueueTimer(conversationId, seconds)
        requestOutbox()
    }

    /** The timer this phone applies to new messages of the conversation, in seconds (0: off). */
    suspend fun disappearingTimer(conversationId: String): Long = ctx.store.getTimer(conversationId)?.seconds ?: 0L

    /**
     * Starts the clocks of messages that have just been shown: a message with `expires_in` is removed from this phone that long
     * after it was first displayed here (Signal's rule). Messages without a timer, or already counting, are left alone.
     * Returns the earliest removal time that is now scheduled (null: none), for the app to schedule [purgeExpired].
     */
    suspend fun markDisplayed(messageIds: Collection<String>): Long? {
        val now = ctx.now()
        var earliest: Long? = null
        for (id in messageIds) {
            val m = ctx.store.getMessage(id)?.takeIf { it.expireAt == null && !it.deleted && !it.isSystem && it.decryptError == null }
            val seconds = m?.expiresIn ?: continue
            val at = now + seconds * 1000
            ctx.store.updateMessage(id) { if (it.expireAt == null) it.copy(expireAt = at) else it }
            earliest = minOf(earliest ?: at, at)
        }
        return earliest
    }

    /**
     * Removes every message whose time has come: text, attachment files that the engine owns, search index rows (with the
     * row), reactions and receipts, leaving a tombstone so a replayed event cannot bring it back. Messages still waiting to
     * be sent stay. Returns the removed messages: the app deletes the source files it handed over itself.
     */
    suspend fun purgeExpired(): List<StoredMessage> {
        val removed = ArrayList<StoredMessage>()
        while (true) {
            val now = ctx.now()
            val due = ctx.store.expiredMessages(now)
            if (due.isEmpty()) break
            for (m in due) {
                val gone = ctx.store.transaction { ctx.store.deleteMessageLocally(m.id, m.conversationId, now) } ?: continue
                gone.attachments.forEach { ctx.deleteDownloadedFile(it.localPath) }
                removed += gone
                ctx.emit(EngineEvent.MessageChanged(m.conversationId, m.id))
            }
        }
        // the search index only marks the removed words as deleted until its segments are merged
        if (removed.isNotEmpty()) ctx.store.compactSearchIndex()
        return removed
    }

    /** When the next message is due to disappear from this phone (epoch ms), or null. */
    suspend fun nextExpiry(): Long? = ctx.store.nextExpiry()

    // --- forwarding ------------------------------------------------------------------------------------------------

    /**
     * Sends a copy of [messageId] to each of [conversationIds] (at most [MAX_FORWARD_TARGETS]), marked as forwarded inside the
     * encrypted content. Attachments are decrypted on this phone (downloaded first if need be), copied, and uploaded again
     * under fresh keys, since the server only takes attachment ids uploaded by the sender. The copy carries the timer of its
     * own conversation. Returns the new message ids.
     *
     * @throws java.io.IOException if an attachment cannot be fetched
     */
    suspend fun forwardMessage(messageId: String, conversationIds: List<String>): List<String> {
        val targets = conversationIds.distinct()
        require(targets.isNotEmpty() && targets.size <= MAX_FORWARD_TARGETS) { "1..$MAX_FORWARD_TARGETS conversations" }
        val source = ctx.store.getMessage(messageId) ?: throw IllegalArgumentException("Unknown message")
        require(!source.deleted && !source.isSystem && source.decryptError == null && source.poll == null) { "That message cannot be forwarded" }
        require(source.status != MessageStatus.PENDING && source.status != MessageStatus.FAILED) { "The message has not been sent yet" }
        for (t in targets) ctx.store.getConversation(t) ?: throw IllegalArgumentException("Unknown conversation")
        val files = source.attachments.map { a ->
            val path = a.localPath?.takeIf { a.downloadState == DownloadState.DONE && File(it).isFile }
            a to (path?.let(::File) ?: attachments.download(messageId, a.id))
        }
        ctx.config.forwardDir.mkdirs()
        val ids = ArrayList<String>()
        try {
            for (t in targets) {
                val copies = files.map { (a, f) ->
                    val copy = File.createTempFile("fwd-", ".bin", ctx.config.forwardDir)
                    f.copyTo(copy, overwrite = true)
                    OutboxAttachment(copy.absolutePath, a.mime, a.name, a.kind, a.width, a.height, a.durationMs, a.thumbnail)
                }
                ids += sender.enqueueForwarded(t, source.text, copies)
            }
        } finally {
            requestOutbox()
        }
        return ids
    }

    /** Re-queues a message that ended in FAILED. */
    suspend fun retryMessage(messageId: String) {
        sender.retry(messageId)
        requestOutbox()
    }

    /** Delivers everything in the outbox now (one pass). For WorkManager and tests; normally the worker does this. */
    suspend fun processOutbox(): OutboxResult {
        flushPendingReads()
        return sender.process()
    }

    private fun requestOutbox() {
        outboxRequests.trySend(Unit)
    }

    // =========================================================================================================
    // Receiving
    // =========================================================================================================

    /** Pulls and applies all new events, acknowledging after they are stored. Returns the number of events applied. */
    suspend fun sync(): Int = syncer.sync()

    /**
     * Downloads and decrypts one attachment of a received message (resumable). Returns the plain file; the message's
     * attachment record gets `localPath` and `DownloadState.DONE`.
     */
    suspend fun downloadAttachment(messageId: String, attachmentId: String, onProgress: ((Long) -> Unit)? = null): File =
        attachments.download(messageId, attachmentId, onProgress)

    /** Marks everything in the conversation as read (local unread count drops; the server tells the senders). */
    suspend fun markRead(conversationId: String) {
        val c = ctx.store.getConversation(conversationId) ?: return
        // imported history (from a file) never decides how far the conversation is read: its seq is not the server's word
        val latest = ctx.store.getMessages(conversationId, 50).lastOrNull { it.seq != null && !it.imported }?.seq
            ?: c.lastMessageSeq ?: return
        if (latest <= c.myReadSeq) return
        ctx.store.setMyReadSeq(conversationId, latest)
        ctx.emit(EngineEvent.ConversationChanged(conversationId))
        postRead(conversationId, latest)
    }

    private suspend fun postRead(conversationId: String, seq: Long) {
        try {
            ctx.api.markRead(conversationId, MarkReadRequest(seq))
            ctx.store.setMeta("$META_PENDING_READ$conversationId", null)
        } catch (e: IOException) {
            ctx.store.setMeta("$META_PENDING_READ$conversationId", seq.toString())
        } catch (e: ApiException.NotFound) {
            // no longer a member
        }
    }

    private suspend fun flushPendingReads() {
        for (c in ctx.store.getConversations()) {
            val seq = ctx.store.getMeta("$META_PENDING_READ${c.id}")?.toLongOrNull() ?: continue
            postRead(c.id, seq)
        }
    }

    // =========================================================================================================
    // Typing
    // =========================================================================================================

    /** Tells the other members you started/stopped typing (throttled: a "start" is re-sent at most every 3 s). */
    fun sendTyping(conversationId: String, active: Boolean) {
        val rt = realtime ?: return
        val now = ctx.now()
        if (active) {
            val last = lastTypingSent[conversationId] ?: 0L
            if (now - last < 3_000) return
            lastTypingSent[conversationId] = now
        } else {
            lastTypingSent.remove(conversationId)
        }
        rt.sendTyping(conversationId, if (active) TypingState.START else TypingState.STOP)
    }

    private fun onTypingFrame(f: ServerFrame.Typing) {
        val me = ctx.account?.userId
        if (f.userId == me) return
        val key = f.conversationId to f.userId
        synchronized(typingJobs) { typingJobs.remove(key)?.cancel() }
        if (f.state == TypingState.STOP) {
            removeTyping(f.conversationId, f.userId)
            return
        }
        _typing.update { it + (f.conversationId to ((it[f.conversationId] ?: emptySet()) + f.userId)) }
        val job = scope.launch {
            delay(config.typingTimeoutMs)
            removeTyping(f.conversationId, f.userId)
        }
        synchronized(typingJobs) { typingJobs[key] = job }
    }

    private fun removeTyping(conversationId: String, userId: String) {
        _typing.update { map ->
            val rest = (map[conversationId] ?: emptySet()) - userId
            if (rest.isEmpty()) map - conversationId else map + (conversationId to rest)
        }
    }

    // =========================================================================================================
    // Realtime and background workers
    // =========================================================================================================

    /**
     * Opens the WebSocket (reconnecting with backoff) and syncs on every hint and every (re)connect. Idempotent.
     * Call when the app comes to the foreground; call [stopRealtime] when it leaves.
     */
    fun startRealtime() {
        val connection = ctx.connection ?: throw NotSignedInException()
        synchronized(this) {
            if (realtimeJob?.isActive == true) return
            val rt = connection.realtime(scope)
            realtime = rt
            realtimeJob = scope.launch {
                // Subscribe before starting so the first hello cannot be missed.
                val collector = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    rt.frames.collect { frame ->
                        when (frame) {
                            is ServerFrame.Hello, is ServerFrame.Sync -> requestSync()
                            is ServerFrame.Typing -> onTypingFrame(frame)
                            else -> Unit
                        }
                    }
                }
                val stateCollector = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { rt.state.collect { _realtimeState.value = it } }
                rt.start()
                try {
                    // Key maintenance rides along with each foreground session; failures are not fatal.
                    try {
                        ensureKeys()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // retried with the next foreground session
                    }
                    collector.join()
                } finally {
                    stateCollector.cancel()
                }
            }
        }
    }

    fun stopRealtime() {
        synchronized(this) {
            realtime?.stop()
            realtime = null
            realtimeJob?.cancel()
            realtimeJob = null
        }
        _realtimeState.value = se.swahnchat.core.network.RealtimeState.STOPPED
        synchronized(typingJobs) { typingJobs.values.forEach { it.cancel() }; typingJobs.clear() }
        _typing.value = emptyMap()
    }

    /** Asks the background worker to run a sync soon (coalesces bursts). */
    fun requestSync() {
        syncRequests.trySend(Unit)
    }

    private fun startWorkers() {
        workerJob?.cancel()
        workerJob = scope.launch {
            launch {
                var failures = 0
                val backoff = BackoffPolicy(baseMs = 2_000, maxMs = 60_000)
                for (r in syncRequests) {
                    try {
                        syncer.sync()
                        flushPendingReads()
                        sender.process()
                        failures = 0
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ApiException) {
                        if (e.isLoggedOut) continue // sessionEvents reports it
                        failures = retryLater(failures, backoff, syncRequests)
                    } catch (e: Exception) {
                        failures = retryLater(failures, backoff, syncRequests)
                    }
                }
            }
            launch { watchSchedule() }
            launch {
                var failures = 0
                val backoff = BackoffPolicy(baseMs = 2_000, maxMs = 60_000)
                for (r in outboxRequests) {
                    try {
                        val result = sender.process()
                        failures = if (result.offline || result.pending > 0) retryLater(failures, backoff, outboxRequests) else 0
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failures = retryLater(failures, backoff, outboxRequests)
                    }
                }
            }
        }
    }

    /** Scheduled messages: wakes the outbox when the earliest one is due, and looks again when the schedule changes. */
    private suspend fun watchSchedule() {
        scheduleChanged.trySend(Unit)
        for (r in scheduleChanged) {
            var next = nextScheduledOrNull()
            while (next != null) {
                val wait = next - ctx.now()
                val changed = wait > 0 && kotlinx.coroutines.withTimeoutOrNull(wait) { scheduleChanged.receive() } != null
                if (!changed) {
                    outboxRequests.trySend(Unit)
                    delay(SCHEDULE_RECHECK_MS)
                }
                next = nextScheduledOrNull()
            }
        }
    }

    private suspend fun nextScheduledOrNull(): Long? = try {
        sender.nextScheduledAt()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** Re-arms [channel] after a delay so a transient failure is retried without an external trigger. */
    private fun retryLater(failures: Int, backoff: BackoffPolicy, channel: Channel<Unit>): Int {
        val wait = backoff.delayMs(failures)
        scope.launch {
            delay(wait)
            channel.trySend(Unit)
        }
        return failures + 1
    }

    // =========================================================================================================
    // Identity verification
    // =========================================================================================================

    /**
     * The safety number between this device and the device [deviceId] of [userId], or null if the device is not pinned.
     * Devices are identified by their UUID (`Device.id`).
     */
    fun safetyNumber(userId: String, deviceId: String): SafetyNumber? {
        val account = ctx.account ?: return null
        val remote = ctx.signalStore.pinnedIdentity(deviceId)?.takeIf { it.userId == userId } ?: return null
        return SafetyNumbers.compute(
            account.userId, account.deviceId, ctx.keyManager.identityPublicKey(),
            userId, deviceId, remote.identityKey,
        )
    }

    /**
     * The safety number this device would have with [userId]'s device [deviceId] if [identityKey] (a serialized
     * `IdentityKey`, for example [IdentityChange.newIdentityKey]) were its identity. Lets the user compare the number of a
     * changed key before accepting it.
     */
    fun safetyNumberFor(userId: String, deviceId: String, identityKey: ByteArray): SafetyNumber? {
        val account = ctx.account ?: return null
        return SafetyNumbers.compute(
            account.userId, account.deviceId, ctx.keyManager.identityPublicKey(), userId, deviceId, identityKey,
        )
    }

    /** True if the user marked this device's pinned identity as verified. */
    fun isVerified(deviceId: String): Boolean = ctx.signalStore.pinnedIdentity(deviceId)?.verified == true

    /**
     * Marks the pinned identity of an approved device as verified (safety numbers compared) or not. Once a user has a
     * verified device, every new device of that user is blocked until the local user accepts it ([acceptIdentity]).
     */
    fun setVerified(deviceId: String, verified: Boolean) = ctx.signalStore.setVerified(deviceId, verified)

    /**
     * Devices waiting for a decision: a changed identity key or owner, and new devices that appeared next to verified
     * ones. The UI shows a warning for them and offers [acceptIdentity] after the user compared safety numbers. Sending to
     * and receiving from them is blocked meanwhile.
     */
    suspend fun pendingIdentityChanges(): List<IdentityChange> {
        val out = ArrayList<IdentityChange>()
        for (u in ctx.store.getUsers()) for (d in ctx.store.getDevices(u.id)) {
            val conflict = directory.pendingConflict(d.id)
            if (conflict != null) {
                out += IdentityChange(u.id, d.id, d.name, conflict.takeIf { it.isNotEmpty() }, IdentityChange.Kind.CHANGED)
                continue
            }
            val pin = ctx.signalStore.pinnedIdentity(d.id)
            if (pin != null && !pin.approved && pin.userId == u.id) {
                out += IdentityChange(u.id, d.id, d.name, pin.identityKey, IdentityChange.Kind.NEW_DEVICE)
            }
        }
        return out
    }

    /**
     * Trusts [newIdentityKey] for the device after the user confirmed it: pins it (for [userId]), approves the device,
     * drops the old session, retries messages that could not be decrypted and re-queues sends that failed because of it.
     *
     * The key must be the one the server lists for the device right now; a key that only showed up in a prekey message or
     * bundle (and contradicts the device list) cannot be accepted. Returns false in that case, and nothing changes.
     */
    suspend fun acceptIdentity(userId: String, deviceId: String, newIdentityKey: ByteArray): Boolean {
        directory.refreshDevices(userId)
        val listed = ctx.store.getDevices(userId).firstOrNull { it.id == deviceId }?.identityKey
            ?.let { se.swahnchat.core.crypto.DeviceAddress.decodeIdentityKey(it) }
        if (listed == null || !listed.contentEquals(newIdentityKey)) return false
        ctx.crypto.exclusive { ctx.signalStore.acceptIdentity(userId, deviceId, newIdentityKey) }
        directory.clearConflict(deviceId)
        syncer.retryUndecrypted(userId, deviceId)
        sender.retryFailed(FailureReason.IDENTITY_CHANGED)
        requestOutbox()
        return true
    }
}

/**
 * A device that needs the user's decision. [newIdentityKey] is the key in question (the pinned key for
 * [Kind.NEW_DEVICE]); it may be null if it could not be determined.
 */
class IdentityChange(
    val userId: String,
    val deviceId: String,
    val deviceName: String,
    val newIdentityKey: ByteArray?,
    val kind: Kind,
) {
    enum class Kind {
        /** The identity key (or owner) of a known device changed. */
        CHANGED,

        /** A new device next to verified ones; blocked until accepted. */
        NEW_DEVICE,
    }
}

/** A file to send. [mime] is the real type; [kind] is `image|video|voice|file`. */
class OutgoingAttachment(
    val file: File,
    val mime: String,
    val kind: String,
    val name: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    /** base64 of a small JPEG preview (a few KB). */
    val thumbnailBase64: String? = null,
)

/** The most conversations one forward may go to. */
const val MAX_FORWARD_TARGETS = 5

internal const val MAX_AVATAR_BYTES = 2 * 1024 * 1024
/** A scheduled message goes out between a minute and 30 days from the moment it is scheduled. */
const val MIN_SCHEDULE_MS = 60_000L
const val MAX_SCHEDULE_MS = 30L * 24 * 60 * 60 * 1000

/** After waking the outbox for a due scheduled message, the schedule is looked at again after this long. */
internal const val SCHEDULE_RECHECK_MS = 5_000L
internal const val META_KEYS_UPLOADED = "keys_uploaded"
internal const val META_PENDING_READ = "pending_read:"
