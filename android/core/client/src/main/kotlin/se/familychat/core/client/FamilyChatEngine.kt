package se.familychat.core.client

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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import se.familychat.core.crypto.ProtocolStorage
import se.familychat.core.crypto.SafetyNumber
import se.familychat.core.crypto.SafetyNumbers
import se.familychat.core.model.AddMembersRequest
import se.familychat.core.model.AuthResponse
import se.familychat.core.model.Conversation
import se.familychat.core.model.ConversationType
import se.familychat.core.model.CreateConversationRequest
import se.familychat.core.model.CreateInvitationRequest
import se.familychat.core.model.CreatedInvitation
import se.familychat.core.model.LoginRequest
import se.familychat.core.model.MarkReadRequest
import se.familychat.core.model.RedeemInviteRequest
import se.familychat.core.model.ServerFrame
import se.familychat.core.model.SetRoleRequest
import se.familychat.core.model.TypingState
import se.familychat.core.network.ApiClient
import se.familychat.core.network.ApiException
import se.familychat.core.network.BackoffPolicy
import se.familychat.core.network.BaseUrl
import se.familychat.core.network.LogoutReason
import se.familychat.core.network.RealtimeConnection
import se.familychat.core.network.ServerConnection
import se.familychat.core.network.TokenProvider
import se.familychat.core.network.Tokens
import java.io.File
import java.io.IOException
import java.util.Base64

/**
 * The FamilyChat messaging engine: the single entry point the app's repositories and ViewModels talk to.
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
class FamilyChatEngine(
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

    private val _typing = MutableStateFlow<TypingMap>(emptyMap())

    /** conversationId -> user ids currently typing (other people only). */
    val typing: StateFlow<TypingMap> = _typing.asStateFlow()

    private val _realtimeState = MutableStateFlow(se.familychat.core.network.RealtimeState.DISCONNECTED)

    /** State of the WebSocket (for a "connecting..." indicator). */
    val realtimeState: StateFlow<se.familychat.core.network.RealtimeState> = _realtimeState.asStateFlow()

    private var realtime: RealtimeConnection? = null
    private var realtimeJob: Job? = null
    private var workerJob: Job? = null
    private var sessionWatchJob: Job? = null
    private val syncRequests = Channel<Unit>(Channel.CONFLATED)
    private val outboxRequests = Channel<Unit>(Channel.CONFLATED)
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

    /** Signs in on this device (registering it as a new device on the server). */
    suspend fun login(serverUrl: String, username: String, password: String): Account = sessionMutex.withLock {
        val url = BaseUrl.normalize(serverUrl, config.allowInsecureLan).toString()
        val connection = connectionFactory(url)
        val auth = connection.api.login(LoginRequest(username.trim().lowercase(), password, config.deviceName))
        completeSignIn(url, connection, auth)
    }

    /** Creates an account from an invitation code and signs in. */
    suspend fun redeemInvite(
        serverUrl: String,
        code: String,
        username: String,
        password: String,
        displayName: String,
    ): Account = sessionMutex.withLock {
        val url = BaseUrl.normalize(serverUrl, config.allowInsecureLan).toString()
        val connection = connectionFactory(url)
        val auth = connection.api.redeemInvite(
            RedeemInviteRequest(code.trim(), username.trim().lowercase(), password, displayName.trim(), config.deviceName),
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
        remote.forEach { ctx.store.upsertConversation(it) }
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
        ctx.store.upsertConversation(c)
        c.members.forEach { directory.refreshDevices(it.userId) }
        ctx.emit(EngineEvent.ConversationChanged(c.id))
        return c
    }

    suspend fun createGroup(name: String, memberUserIds: List<String>): Conversation {
        val c = ctx.api.createConversation(CreateConversationRequest(ConversationType.GROUP, name.trim(), memberUserIds))
        ctx.store.upsertConversation(c)
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
        ctx.store.upsertConversation(c)
        ctx.emit(EngineEvent.ConversationChanged(c.id))
        return c
    }

    /** Admin only: creates an invitation code (shown once). */
    suspend fun createInvitation(note: String? = null, expiresInHours: Int? = null, admin: Boolean = false): CreatedInvitation =
        ctx.api.createInvitation(CreateInvitationRequest(note, expiresInHours, if (admin) true else null))

    // =========================================================================================================
    // Sending
    // =========================================================================================================

    /** Queues a text message and returns its id immediately (the message is PENDING until the server has it). */
    suspend fun sendText(conversationId: String, text: String, replyTo: String? = null): String =
        sender.enqueueText(conversationId, text, replyTo).also { requestOutbox() }

    /**
     * Queues a message with attachments. The files are read at send time and must stay in place until the message
     * leaves PENDING; they are never modified or deleted by the engine.
     */
    suspend fun sendAttachments(
        conversationId: String,
        files: List<OutgoingAttachment>,
        caption: String? = null,
        replyTo: String? = null,
    ): String = sender.enqueueAttachments(
        conversationId,
        files.map {
            OutboxAttachment(it.file.absolutePath, it.mime, it.name ?: it.file.name, it.kind, it.width, it.height, it.durationMs, it.thumbnailBase64)
        },
        caption, replyTo,
    ).also { requestOutbox() }

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
        val latest = ctx.store.getMessages(conversationId, 1).lastOrNull { it.seq != null }?.seq
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
        _realtimeState.value = se.familychat.core.network.RealtimeState.STOPPED
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

    /** The safety number between this device and [userId]'s device [deviceNumber], or null if no identity is pinned yet. */
    fun safetyNumber(userId: String, deviceNumber: Int): SafetyNumber? {
        val account = ctx.account ?: return null
        val remote = ctx.signalStore.pinnedIdentity(userId, deviceNumber) ?: return null
        return SafetyNumbers.compute(
            account.userId, account.deviceNumber, ctx.keyManager.identityPublicKey(),
            userId, deviceNumber, remote.identityKey,
        )
    }

    /** True if the user marked this device's pinned identity as verified. */
    fun isVerified(userId: String, deviceNumber: Int): Boolean =
        ctx.signalStore.pinnedIdentity(userId, deviceNumber)?.verified == true

    fun setVerified(userId: String, deviceNumber: Int, verified: Boolean) =
        ctx.signalStore.setVerified(userId, deviceNumber, verified)

    /**
     * Identity changes waiting for a decision: `(userId, deviceNumber)` with the key that was presented. The UI shows
     * a warning for them and offers [acceptIdentity] after the user compared safety numbers.
     */
    suspend fun pendingIdentityChanges(): List<IdentityChange> {
        val out = ArrayList<IdentityChange>()
        for (u in ctx.store.getUsers()) for (d in ctx.store.getDevices(u.id)) {
            val v = ctx.store.getMeta("${SyncProcessor.IDENTITY_META_PREFIX}${u.id}:${d.number}") ?: continue
            out += IdentityChange(u.id, d.number, v.takeIf { it.isNotEmpty() }?.let { Base64.getDecoder().decode(it) })
        }
        return out
    }

    /**
     * Trusts [newIdentityKey] for the device after the user confirmed it: pins it, drops the old session, retries
     * messages that could not be decrypted because of the change and re-queues sends that failed because of it.
     */
    suspend fun acceptIdentity(userId: String, deviceNumber: Int, newIdentityKey: ByteArray) {
        ctx.signalStore.acceptIdentity(userId, deviceNumber, newIdentityKey)
        ctx.store.setMeta("${SyncProcessor.IDENTITY_META_PREFIX}$userId:$deviceNumber", null)
        syncer.retryUndecrypted(userId, deviceNumber)
        sender.retryFailed(FailureReason.IDENTITY_CHANGED)
        requestOutbox()
    }
}

/** An identity key change that needs the user's decision. [newIdentityKey] may be null if it could not be determined. */
class IdentityChange(val userId: String, val deviceNumber: Int, val newIdentityKey: ByteArray?)

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

internal const val META_KEYS_UPLOADED = "keys_uploaded"
internal const val META_PENDING_READ = "pending_read:"
