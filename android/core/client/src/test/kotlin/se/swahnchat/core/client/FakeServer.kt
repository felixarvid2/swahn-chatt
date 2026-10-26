package se.swahnchat.core.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import okhttp3.RequestBody
import okio.Buffer
import se.swahnchat.core.model.*
import se.swahnchat.core.network.*
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * An in-process stand-in for the backend with the semantics the engine relies on: per-device event queues,
 * mismatched-device checks (409), idempotent sends, delivery receipts on ack, read receipts, one-time prekey
 * consumption, attachments. It only ever sees ciphertext, like the real server.
 */
class FakeServer {
    private val lock = Any()
    private val seqCounter = AtomicLong(0)
    private val messageSeq = AtomicLong(0)

    class DeviceRec(
        val id: String, val userId: String, val number: Int, var name: String,
        var keys: UploadKeysRequest? = null,
        val ec: ArrayDeque<OneTimePreKeyDto> = ArrayDeque(), val pq: ArrayDeque<PqPreKeyDto> = ArrayDeque(),
        var revoked: Boolean = false, var acked: Long = 0, var createdAt: Long = 0,
    )

    class UserRec(val id: String, val username: String, var displayName: String, var password: String, var isAdmin: Boolean) {
        var suspended = false
        var removed = false
        /** Presence as the backend keeps it: opt-in, off by default; the last-seen time is dropped when sharing is switched off. */
        var sharePresence = false
        var online = false
        var presenceLastSeenAt: Long? = null
    }

    class ConvRec(
        val id: String, val type: String, var name: String?, val createdBy: String,
        val members: LinkedHashMap<String, MutableMember> = LinkedHashMap(),
        var disappearingSeconds: Long? = null,
    )

    class MutableMember(val userId: String, var role: String, var readSeq: Long = 0)

    class MsgRec(
        val msg: Message, val kind: String, val envelopes: Map<String, MessageEnvelope>,
        var deleted: Boolean = false,
    )

    val users = LinkedHashMap<String, UserRec>()
    val devices = LinkedHashMap<String, DeviceRec>()
    val conversations = LinkedHashMap<String, ConvRec>()
    val messages = LinkedHashMap<String, MsgRec>() // by message id
    val queues = HashMap<String, MutableList<Event>>() // deviceId -> events
    private val deliveredPairs = HashSet<String>()
    private val attachmentBlobs = HashMap<String, ByteArray>()
    private class UploadRec(val id: String, val size: Long, val sha256: String?, val bytes: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream())
    private val uploads = HashMap<String, UploadRec>()

    /** Largest chunk the fake accepts (tests shrink it to force several requests). */
    @Volatile var maxChunkBytes = 8L * 1024 * 1024

    /** The next N chunk requests fail with an IOException after the server stored the chunk (a lost response). */
    @Volatile var loseChunkResponses = 0

    /** Set the next N chunk requests to fail before reaching the server. */
    @Volatile var failChunks = 0

    /** Messages (and kinds) the server stored, with the `expires_at` it computed from its own timer. */
    val expiresAt = HashMap<String, Long?>()

    /**
     * A malicious or hacked server sets the conversation's pruning timer and tells every member's devices, with no encrypted
     * `timer` content behind it.
     */
    fun serverSetsTimer(conversationId: String, seconds: Long?) {
        synchronized(lock) {
            val c = conversations.getValue(conversationId)
            c.disappearingSeconds = seconds
            pushConversation(c)
        }
    }
    private val realtimes = CopyOnWriteArrayList<FakeRealtime>()

    /** Chronological log of API calls (e.g. "sync:0", "ack:7", "send:<id>") for ordering assertions. */
    /** The next this many mark-read calls fail like a dropped connection. */
    @Volatile var failReads = 0
    val log: MutableList<String> = CopyOnWriteArrayList()

    /** The server's clock for call messages (`server_ts`); ordinary messages keep the fixed 1000 the tests expect. */
    @Volatile var serverTime: () -> Long = System::currentTimeMillis

    /** What `GET /calls/turn` answers; [failTurn] makes it fail like a server that is down. */
    @Volatile var turn = TurnCredentials(listOf("stun:fake.example:3478", "turn:fake.example:3478?transport=udp"), "1:user", "secret", 3600)
    @Volatile var failTurn = false
    val turnFetches = java.util.concurrent.atomic.AtomicInteger()

    private val callSendTimes = HashMap<String, ArrayDeque<Long>>() // sender user -> recent call sends (60 per minute)

    /** The `call` messages the server holds, in order (they are never part of history). */
    fun callMessages(): List<MsgRec> = synchronized(lock) { messages.values.filter { it.kind == MessageKind.CALL } }

    // --- fault injection ----------------------------------------------------------------------------------------------
    @Volatile var failSends = 0 // next N sendMessage calls throw IOException before reaching the server
    @Volatile var loseSendResponses = 0 // next N sends are stored, then the response is "lost"
    @Volatile var failAcks = 0
    @Volatile var onAck: ((deviceId: String, cursor: Long) -> Unit)? = null

    /** Runs after a message is stored and before the sender gets the response (to let receipts overtake the response). */
    @Volatile var beforeSendResponse: (suspend () -> Unit)? = null
    @Volatile var corruptAttachments = false
    @Volatile var alwaysMismatch = false
    @Volatile var patchFails = 0

    // --- a malicious server --------------------------------------------------------------------------------------------
    /** Rewrites the device list the server returns for a user (number remaps, injected or hidden devices). */
    @Volatile var listDevicesHook: ((userId: String, honest: List<Device>) -> List<Device>)? = null

    /** Rewrites a key bundle response (another identity key, another device id). */
    @Volatile var getKeysHook: ((userId: String, deviceId: String?, honest: KeyBundleResponse) -> KeyBundleResponse)? = null

    /** If set, the next sends fail with this 409 (once per element) before reaching the normal checks. */
    val forcedMismatches = java.util.concurrent.ConcurrentLinkedQueue<ApiException.MismatchedDevices>()

    /** Puts an arbitrary event into a device's queue, as a server that controls every response could. */
    fun injectEvent(deviceId: String, type: String, conversationId: String?, data: JsonElement): Event =
        enqueue(deviceId, type, conversationId, data)

    /**
     * The server adds a device with its own keys to [userId]'s account (a rogue device). It is listed, receives
     * envelopes and counts for the `mismatched_devices` check like any device; [announce] controls whether anybody gets
     * a `devices_changed` event (a malicious server would not send one).
     */
    fun addDevice(userId: String, upload: UploadKeysRequest, name: String = "rogue", announce: Boolean = false, id: String = UUID.randomUUID().toString()): String =
        synchronized(lock) {
            val used = devices.values.filter { it.userId == userId && !it.revoked }.map { it.number }.toSet()
            val number = (1..127).first { it !in used }
            val d = DeviceRec(id, userId, number, name, upload)
            upload.oneTimePrekeys.orEmpty().forEach { d.ec.add(it) }
            upload.oneTimePqPrekeys.orEmpty().forEach { d.pq.add(it) }
            devices[id] = d
            queues[id] = ArrayList()
            if (announce) notifyDevicesChanged(userId)
            id
        }

    /** The server replaces the identity (and prekeys) of a known device by [upload]'s: same uuid, another key. */
    fun swapIdentity(deviceId: String, upload: UploadKeysRequest, announce: Boolean = false) {
        synchronized(lock) {
            val d = devices.getValue(deviceId)
            d.keys = upload
            d.ec.clear(); d.pq.clear()
            upload.oneTimePrekeys.orEmpty().forEach { d.ec.add(it) }
            upload.oneTimePqPrekeys.orEmpty().forEach { d.pq.add(it) }
            if (announce) notifyDevicesChanged(d.userId)
        }
    }

    /** Tells every interested device that [userId]'s devices changed (what the real server does on key uploads). */
    fun announceDevicesChanged(userId: String) {
        synchronized(lock) { notifyDevicesChanged(userId) }
    }

    // --- accounts -----------------------------------------------------------------------------------------------------

    fun newUser(username: String, displayName: String = username, admin: Boolean = false, password: String = "password-$username"): UserRec =
        synchronized(lock) {
            UserRec(UUID.randomUUID().toString(), username, displayName, password, admin).also { users[it.id] = it }
        }

    private fun newDevice(userId: String, name: String): DeviceRec = synchronized(lock) {
        val used = devices.values.filter { it.userId == userId && !it.revoked }.map { it.number }.toSet()
        val number = (1..127).first { it !in used } // lowest free number, like a tight allocator
        DeviceRec(UUID.randomUUID().toString(), userId, number, name).also { devices[it.id] = it; queues[it.id] = ArrayList() }
    }

    fun revokeDevice(deviceId: String) {
        synchronized(lock) {
            val d = devices.getValue(deviceId)
            d.revoked = true
            d.keys = null
            notifyDevicesChanged(d.userId)
        }
    }

    /** A connection that is not yet bound to a device; `login`/`redeemInvite` binds it. */
    fun connect(deviceId: String = ""): FakeConnection = FakeConnection(this, deviceId).also { it.serverClock.record(serverTime()) }

    /** Log in over the fake API without an engine (used by helper code). */
    private fun auth(u: UserRec, d: DeviceRec) = AuthResponse(
        "access-${d.id}", 0, "refresh-${d.id}", 0, userDto(u), deviceDto(d),
    )

    /** Like `GET /users`: `online` and `last_seen_at` only for users who share their presence. */
    private fun userDto(u: UserRec) =
        if (u.sharePresence) User(u.id, u.username, u.displayName, null, u.isAdmin, 0, u.online, if (u.online) serverTime() else u.presenceLastSeenAt)
        else User(u.id, u.username, u.displayName, null, u.isAdmin, 0)

    /** Test control: [username] connects to or leaves the server (only visible to others if that user shares presence). */
    fun setOnline(username: String, online: Boolean, lastSeenAt: Long? = null) = synchronized(lock) {
        val u = users.values.first { it.username == username }
        u.online = online
        if (!online) u.presenceLastSeenAt = lastSeenAt ?: serverTime()
    }
    private fun deviceDto(d: DeviceRec) = Device(d.id, d.userId, d.number, d.name, d.createdAt, 0, d.keys?.identityKey)

    // --- administration, password reset, account deletion (state the tests read and steer) ---------------------------------

    /** Reset codes issued and not yet redeemed: code -> user id. */
    val resetCodes = LinkedHashMap<String, String>()

    /** The audit rows the fake keeps (newest last): action and acting user. */
    val audit = ArrayList<AuditEvent>()

    /** Devices whose "send a test push" was queued, and what the push server answers for them. */
    val testPushes = ArrayList<String>()
    @Volatile var pushAnswer: Int = 201
    val lastPush = HashMap<String, Pair<Long, Int>>()
    @Volatile var pushEndpointRegistered = true

    /** The wrong-password attempts the fake saw on `DELETE /users/me`. */
    @Volatile var deleteAttempts = 0

    private fun addAudit(action: String, actor: String?, target: String?) {
        audit += AuditEvent((audit.size + 1).toLong(), System.currentTimeMillis(), actor, action, target, null)
    }

    private fun activeAdmins() = users.values.filter { it.isAdmin && !it.suspended && !it.removed }

    private fun removeAccount(u: UserRec) {
        u.removed = true
        for (d in activeDevices(u.id)) { d.revoked = true; d.keys = null }
        for (c in conversations.values) if (c.members.remove(u.id) != null) pushConversation(c)
        notifyDevicesChanged(u.id)
    }

    private fun adminUserDto(u: UserRec) = AdminUser(
        u.id, u.username, u.displayName, u.isAdmin, 0, if (u.removed) 1L else null, if (u.suspended) 1L else null, null,
        activeDevices(u.id).size, 0,
        activeDevices(u.id).map { AdminDevice(it.id, it.number, it.name, 0, pushEndpointRegistered, lastPush[it.id]?.first, lastPush[it.id]?.second, 0) },
    )

    // --- internals ----------------------------------------------------------------------------------------------------

    private fun enqueue(deviceId: String, type: String, conversationId: String?, data: JsonElement): Event = synchronized(lock) {
        val e = Event(seqCounter.incrementAndGet(), type, System.currentTimeMillis(), conversationId, data)
        queues.getValue(deviceId).add(e)
        realtimes.filter { it.deviceId == deviceId }.forEach { it.pushSync(e.seq) }
        e
    }

    private fun activeDevices(userId: String) = devices.values.filter { it.userId == userId && !it.revoked }

    private fun convDto(c: ConvRec, forUser: String? = null): Conversation {
        val lastSeq = messages.values.filter { it.msg.conversationId == c.id && it.kind == MessageKind.MESSAGE }.maxOfOrNull { it.msg.seq }
        return Conversation(
            c.id, c.type, c.name, null, c.createdBy, 0, 0, null, lastSeq,
            forUser?.let { c.members[it]?.readSeq } ?: 0, 0,
            c.members.values.map { ConversationMember(it.userId, it.role, 0) },
            c.disappearingSeconds,
        )
    }

    private fun pushConversation(c: ConvRec) {
        for (m in c.members.keys) for (d in activeDevices(m)) {
            enqueue(d.id, EventType.CONVERSATION_UPDATED, c.id, SwahnChatJson.encodeToJsonElement(convDto(c, m)))
        }
    }

    private fun notifyDevicesChanged(userId: String) {
        val peers = HashSet<String>()
        for (c in conversations.values) if (userId in c.members) peers += c.members.keys
        peers += userId
        for (p in peers) for (d in activeDevices(p)) {
            enqueue(d.id, EventType.DEVICES_CHANGED, null, SwahnChatJson.encodeToJsonElement(UserIdData(userId)))
        }
    }

    // --- the API as one authenticated device sees it -------------------------------------------------------------------

    inner class Api(var deviceId: String) : SwahnChatApi {
        private val me: DeviceRec get() = devices.getValue(deviceId)

        override suspend fun healthz() = Unit

        override suspend fun redeemInvite(request: RedeemInviteRequest): AuthResponse = synchronized(lock) {
            if (users.values.any { it.username == request.username }) throw ApiException.Conflict("username_taken", "taken")
            val u = newUser(request.username, request.displayName, password = request.password)
            val d = newDevice(u.id, request.deviceName)
            deviceId = d.id
            auth(u, d)
        }

        override suspend fun login(request: LoginRequest): AuthResponse = synchronized(lock) {
            val u = users.values.firstOrNull { it.username == request.username && it.password == request.password && !it.removed }
                ?: throw ApiException.InvalidCredentials("bad credentials")
            if (u.suspended) throw ApiException.Forbidden("account_suspended", "suspended")
            val d = newDevice(u.id, request.deviceName)
            deviceId = d.id
            auth(u, d)
        }

        override suspend fun resetPassword(request: PasswordResetRequest): AuthResponse = synchronized(lock) {
            val userId = resetCodes.remove(request.code) ?: throw ApiException.NotFound("invalid_reset_code", "invalid reset code")
            val u = users.getValue(userId)
            if (u.removed) throw ApiException.NotFound("invalid_reset_code", "invalid reset code")
            if (request.newPassword.length < 10) throw ApiException.Invalid("invalid_request", "weak password")
            u.password = request.newPassword
            for (d in activeDevices(userId)) { d.revoked = true; d.keys = null }
            notifyDevicesChanged(userId)
            val d = newDevice(userId, request.deviceName)
            deviceId = d.id
            addAudit("password_reset_redeemed", userId, userId)
            auth(u, d)
        }

        override suspend fun refresh(request: RefreshRequest): AuthResponse = throw UnsupportedOperationException()
        override suspend fun logout() { synchronized(lock) { revokeDevice(deviceId) } }
        override suspend fun createInvitation(request: CreateInvitationRequest) = CreatedInvitation(UUID.randomUUID().toString(), "CODE", null)
        override suspend fun listInvitations() = emptyList<Invitation>()
        override suspend fun deleteInvitation(id: String) = Unit
        override suspend fun deleteUser(userId: String, request: AdminConfirmRequest) = synchronized(lock) {
            confirmAdmin(request)
            val u = users[userId] ?: throw ApiException.NotFound("not_found", "no such user")
            if (userId == me.userId) throw ApiException.Conflict("cannot_remove_self", "yourself")
            removeAccount(u)
            addAudit("user_removed", me.userId, userId)
        }

        /** The calling administrator's own password, as the server asks for it. */
        private fun confirmAdmin(request: AdminConfirmRequest) {
            if (users.getValue(me.userId).password != request.password) throw ApiException.Forbidden("invalid_credentials", "wrong password")
        }

        private fun requireAdmin() {
            if (users.getValue(me.userId).isAdmin.not()) throw ApiException.Forbidden("forbidden", "admins only")
        }

        override suspend fun adminUsers(): List<AdminUser> = synchronized(lock) { requireAdmin(); users.values.map { adminUserDto(it) } }

        override suspend fun suspendUser(userId: String) = synchronized(lock) {
            requireAdmin()
            val u = users[userId]?.takeIf { !it.removed } ?: throw ApiException.NotFound("not_found", "no such user")
            if (userId == me.userId) throw ApiException.Conflict("cannot_suspend_self", "yourself")
            if (u.isAdmin && activeAdmins().size <= 1) throw ApiException.Conflict("last_admin", "last admin")
            u.suspended = true
            addAudit("user_suspended", me.userId, userId)
        }

        override suspend fun unsuspendUser(userId: String) = synchronized(lock) {
            requireAdmin()
            val u = users[userId]?.takeIf { !it.removed } ?: throw ApiException.NotFound("not_found", "no such user")
            u.suspended = false
            addAudit("user_unsuspended", me.userId, userId)
        }

        override suspend fun setUserAdmin(userId: String, admin: Boolean): AdminUser = synchronized(lock) {
            requireAdmin()
            val u = users[userId]?.takeIf { !it.removed } ?: throw ApiException.NotFound("not_found", "no such user")
            if (!admin && u.isAdmin && activeAdmins().size <= 1) throw ApiException.Conflict("last_admin", "last admin")
            if (u.isAdmin != admin) addAudit(if (admin) "admin_granted" else "admin_revoked", me.userId, userId)
            u.isAdmin = admin
            adminUserDto(u)
        }

        override suspend fun issuePasswordReset(userId: String, request: AdminConfirmRequest): PasswordResetCreated = synchronized(lock) {
            requireAdmin()
            confirmAdmin(request)
            if (userId == me.userId) throw ApiException.Conflict("conflict", "not for yourself")
            val u = users[userId]?.takeIf { !it.removed } ?: throw ApiException.NotFound("not_found", "no such user")
            resetCodes.entries.removeAll { it.value == userId } // a new code cancels the older ones
            val code = "RESET-" + UUID.randomUUID().toString().take(8).uppercase()
            resetCodes[code] = u.id
            addAudit("password_reset_issued", me.userId, userId)
            PasswordResetCreated(code, System.currentTimeMillis() + 24 * 3600_000L)
        }

        override suspend fun revokeUserDevice(userId: String, deviceId: String) = synchronized(lock) {
            requireAdmin()
            val d = devices[deviceId]?.takeIf { it.userId == userId && !it.revoked } ?: throw ApiException.NotFound("not_found", "no such device")
            if (d.id == me.id) throw ApiException.Conflict("current_device", "use logout")
            revokeDevice(d.id)
            addAudit("device_revoked", me.userId, userId)
        }

        override suspend fun adminStats(): AdminStats = synchronized(lock) {
            requireAdmin()
            AdminStats(
                users = users.values.count { !it.removed }, activeDevices = devices.values.count { !it.revoked },
                conversations = conversations.size, messages = messages.size.toLong(), dbBytes = 4096, diskFreeBytes = 1L shl 30,
                version = "0.5.0-fake", migration = MigrationInfo("head1", "head1"),
            )
        }

        override suspend fun adminAudit(beforeId: Long?, limit: Int): List<AuditEvent> = synchronized(lock) {
            requireAdmin()
            audit.reversed().filter { beforeId == null || it.id < beforeId }.take(limit)
        }

        override suspend fun listUsers(): List<User> = synchronized(lock) { users.values.map { userDto(it) } }
        override suspend fun me(): User = synchronized(lock) { userDto(users.getValue(me.userId)) }
        override suspend fun mySettings(): UserSettings = synchronized(lock) { UserSettings(users.getValue(me.userId).sharePresence) }
        override suspend fun updateMySettings(sharePresence: Boolean?): UserSettings = synchronized(lock) {
            log += "settings:$sharePresence"
            val u = users.getValue(me.userId)
            if (sharePresence != null && sharePresence != u.sharePresence) {
                u.sharePresence = sharePresence
                u.presenceLastSeenAt = null // nothing from the sharing period is kept
            }
            UserSettings(u.sharePresence)
        }
        override suspend fun updateMe(displayName: String?, avatarAttachmentId: String?, clearAvatar: Boolean): User = me()
        override suspend fun changePassword(request: ChangePasswordRequest) = Unit
        override suspend fun listDevices(userId: String): List<Device> = synchronized(lock) {
            log += "devices:$userId"
            val honest = activeDevices(userId).map { deviceDto(it) }
            listDevicesHook?.invoke(userId, honest) ?: honest
        }

        override suspend fun myDevice(): Device = synchronized(lock) {
            deviceDto(me).copy(lastPushAt = lastPush[deviceId]?.first, lastPushStatus = lastPush[deviceId]?.second)
        }
        override suspend fun setPush(request: PushRegistration) = Unit
        override suspend fun clearPush() = Unit
        override suspend fun deleteDevice(deviceId: String) { revokeDevice(deviceId) }

        override suspend fun deleteAccount(request: DeleteAccountRequest) = synchronized(lock) {
            val u = users.getValue(me.userId)
            deleteAttempts++
            if (u.password != request.password) throw ApiException.Forbidden("invalid_credentials", "wrong password")
            if (u.isAdmin && activeAdmins().size <= 1) throw ApiException.Conflict("last_admin", "last admin")
            removeAccount(u)
            addAudit("user_removed", u.id, u.id)
        }

        override suspend fun testPush() = synchronized(lock) {
            if (!pushEndpointRegistered) throw ApiException.Conflict("push_not_configured", "no endpoint")
            testPushes += deviceId
            lastPush[deviceId] = System.currentTimeMillis() to pushAnswer
        }

        override suspend fun renameDevice(deviceId: String, name: String): Device = synchronized(lock) {
            val d = devices[deviceId]?.takeIf { it.userId == me.userId && !it.revoked } ?: throw ApiException.NotFound("not_found", "no such device")
            if (name.trim().length !in 1..64) throw ApiException.Invalid("invalid_request", "name")
            if (d.name != name.trim()) {
                d.name = name.trim()
                notifyDevicesChanged(d.userId)
            }
            deviceDto(d)
        }

        override suspend fun turnCredentials(): TurnCredentials {
            turnFetches.incrementAndGet()
            if (failTurn) throw IOException("simulated turn failure")
            return turn
        }

        override suspend fun uploadKeys(request: UploadKeysRequest) {
            synchronized(lock) {
                log += "uploadKeys"
                val d = me
                if (d.keys == null) {
                    require(request.identityKey != null && request.signedPrekey != null && request.pqLastResortPrekey != null)
                    d.keys = request
                } else {
                    val k = d.keys!!
                    if (request.identityKey != null && request.identityKey != k.identityKey) throw ApiException.Conflict("identity_key_mismatch", "x")
                    d.keys = k.copy(
                        signedPrekey = request.signedPrekey ?: k.signedPrekey,
                        pqLastResortPrekey = request.pqLastResortPrekey ?: k.pqLastResortPrekey,
                    )
                }
                val existing = d.ec.map { it.keyId }.toSet()
                request.oneTimePrekeys.orEmpty().filter { it.keyId !in existing }.forEach { d.ec.add(it) }
                val existingPq = d.pq.map { it.keyId }.toSet()
                request.oneTimePqPrekeys.orEmpty().filter { it.keyId !in existingPq }.forEach { d.pq.add(it) }
                notifyDevicesChanged(d.userId)
            }
        }

        override suspend fun keyStatus(): KeyStatus = synchronized(lock) {
            val d = me
            KeyStatus(d.ec.size, d.pq.size, d.keys?.signedPrekey?.keyId, d.keys?.pqLastResortPrekey?.keyId)
        }

        override suspend fun getKeys(userId: String, deviceId: String?): KeyBundleResponse = synchronized(lock) {
            log += "keys:$userId:${deviceId?.take(4)}"
            val list = activeDevices(userId).filter { it.keys != null && it.id != me.id && (deviceId == null || it.id == deviceId) }
            val honest = KeyBundleResponse(
                userId,
                list.map { d ->
                    val k = d.keys!!
                    DeviceKeyBundle(
                        d.id, d.number, k.registrationId!!, k.identityKey!!, k.signedPrekey!!,
                        d.pq.removeFirstOrNull() ?: k.pqLastResortPrekey!!, d.ec.removeFirstOrNull(),
                    )
                },
            )
            getKeysHook?.invoke(userId, deviceId, honest) ?: honest
        }

        override suspend fun listConversations(): List<Conversation> = synchronized(lock) {
            conversations.values.filter { me.userId in it.members }.map { convDto(it, me.userId) }
        }

        override suspend fun createConversation(request: CreateConversationRequest): Conversation = synchronized(lock) {
            val ids = (listOf(me.userId) + request.memberUserIds).distinct()
            if (request.type == ConversationType.DIRECT) {
                conversations.values.firstOrNull { it.type == "direct" && it.members.keys == ids.toSet() }?.let { return@synchronized convDto(it, me.userId) }
            }
            val c = ConvRec(UUID.randomUUID().toString(), request.type, request.name, me.userId)
            ids.forEach { c.members[it] = MutableMember(it, if (it == me.userId && request.type == "group") "admin" else "member") }
            conversations[c.id] = c
            pushConversation(c)
            convDto(c, me.userId)
        }

        override suspend fun getConversation(id: String): Conversation = synchronized(lock) {
            val c = conversations[id]?.takeIf { me.userId in it.members } ?: throw ApiException.NotFound("not_found", "x")
            convDto(c, me.userId)
        }

        override suspend fun updateConversation(
            id: String,
            name: String?,
            avatarAttachmentId: String?,
            clearAvatar: Boolean,
            disappearingSeconds: Long?,
            clearDisappearing: Boolean,
        ): Conversation =
            synchronized(lock) {
                log += "patch:$id:${if (clearDisappearing) "null" else disappearingSeconds}"
                val c = conversations.getValue(id)
                if (patchFails > 0) { patchFails--; throw IOException("simulated patch failure") }
                name?.let { c.name = it }
                if (clearDisappearing) c.disappearingSeconds = null
                else disappearingSeconds?.let { c.disappearingSeconds = it }
                pushConversation(c)
                convDto(c, me.userId)
            }

        override suspend fun addMembers(id: String, request: AddMembersRequest): Conversation = synchronized(lock) {
            val c = conversations.getValue(id)
            request.userIds.forEach { c.members.getOrPut(it) { MutableMember(it, "member") } }
            pushConversation(c)
            convDto(c, me.userId)
        }

        override suspend fun setMemberRole(id: String, userId: String, request: SetRoleRequest): Conversation = synchronized(lock) {
            val c = conversations.getValue(id)
            c.members.getValue(userId).role = request.role
            pushConversation(c)
            convDto(c, me.userId)
        }

        override suspend fun removeMember(id: String, userId: String) {
            synchronized(lock) {
                val c = conversations.getValue(id)
                c.members.remove(userId)
                for (d in activeDevices(userId)) enqueue(d.id, EventType.CONVERSATION_REMOVED, id, SwahnChatJson.encodeToJsonElement(ConversationRemovedData(id)))
                pushConversation(c)
            }
        }

        override suspend fun listMessages(conversationId: String, beforeSeq: Long?, limit: Int) = emptyList<Message>()

        override suspend fun markRead(conversationId: String, request: MarkReadRequest) {
            if (failReads > 0) { failReads--; throw IOException("simulated network failure") }
            synchronized(lock) {
                log += if (request.notify) "read:$conversationId:${request.upToSeq}" else "read:$conversationId:${request.upToSeq}:silent"
                val c = conversations[conversationId]?.takeIf { me.userId in it.members } ?: throw ApiException.NotFound("not_found", "x")
                val mem = c.members.getValue(me.userId)
                if (request.upToSeq <= mem.readSeq) return
                mem.readSeq = request.upToSeq
                val data = SwahnChatJson.encodeToJsonElement(
                    ReceiptData(conversationId, me.userId, ReceiptData.READ, emptyList(), request.upToSeq),
                )
                // a silent read (notify=false) reaches only the caller's own other devices, like the backend
                for (u in c.members.keys) if (request.notify || u == me.userId) {
                    for (d in activeDevices(u)) if (d.id != me.id) enqueue(d.id, EventType.RECEIPT, conversationId, data)
                }
            }
        }

        override suspend fun sendMessage(request: SendMessageRequest): SendMessageResponse {
            if (failSends > 0) { failSends--; throw IOException("simulated network failure") }
            val response = synchronized(lock) { doSend(request) }
            beforeSendResponse?.invoke()
            if (loseSendResponses > 0) { loseSendResponses--; throw IOException("simulated lost response") }
            return response
        }

        /** What the backend checks for `kind: call` before the device set: direct only, no target or attachments, 60 a minute. */
        private fun checkCall(c: ConvRec, r: SendMessageRequest) {
            if (c.type != ConversationType.DIRECT) throw ApiException.Conflict("calls_direct_only", "calls are for direct conversations")
            if (r.targetMessageId != null || r.attachmentIds.isNotEmpty()) {
                throw ApiException.Invalid("invalid_request", "a call has no target or attachments")
            }
            val now = serverTime()
            val recent = callSendTimes.getOrPut(me.userId) { ArrayDeque() }
            while (recent.isNotEmpty() && now - recent.first() > 60_000) recent.removeFirst()
            if (recent.size >= 60) throw ApiException.RateLimited("too many call messages", 5)
            recent.addLast(now)
        }

        /**
         * The `server_ts` of a new message, and its expiry from the conversation's timer. A call is not a message: it ignores the
         * timer and carries the real server time (the client drops stale ones).
         */
        private fun stampMessage(c: ConvRec, r: SendMessageRequest): Long {
            if (r.kind == MessageKind.CALL) return serverTime()
            expiresAt[r.id] = c.disappearingSeconds?.let { 1000 + it * 1000 }
            return 1000
        }

        private fun doSend(r: SendMessageRequest): SendMessageResponse {
            log += "send:${r.id}"
            val c = conversations[r.conversationId]?.takeIf { me.userId in it.members } ?: throw ApiException.NotFound("not_found", "x")
            messages[r.id]?.let {
                if (it.msg.senderUserId != me.userId) throw ApiException.Conflict("id_conflict", "x")
                return SendMessageResponse(it.msg.copy(envelope = null), true)
            }
            if (r.kind == MessageKind.CALL) checkCall(c, r)
            if (alwaysMismatch) throw ApiException.MismatchedDevices("forced", emptyList(), emptyList())
            forcedMismatches.poll()?.let { throw it }
            val expected = c.members.keys.flatMap { activeDevices(it) }.filter { it.keys != null && it.id != me.id }.map { it.id }.toSet()
            val given = r.envelopes.map { it.deviceId }.toSet()
            val want = expected // deletes carry ciphertext for the full device set too
            if (given != want) {
                fun ref(id: String) = DeviceRef(devices.getValue(id).userId, id)
                throw ApiException.MismatchedDevices("mismatch", (want - given).map(::ref), (given - want).map(::ref))
            }
            if (r.kind == MessageKind.REACTION) {
                val t = messages[r.targetMessageId]
                if (t == null || t.deleted || t.kind != MessageKind.MESSAGE || t.msg.conversationId != r.conversationId) {
                    throw ApiException.NotFound("not_found", "target")
                }
                if (r.attachmentIds.isNotEmpty()) throw ApiException.Invalid("invalid_request", "no attachments")
            } else if (r.kind in setOf(MessageKind.EDIT, MessageKind.DELETE)) { // (a call has no target)
                val t = messages[r.targetMessageId] ?: throw ApiException.NotFound("not_found", "target")
                if (t.msg.senderUserId != me.userId || t.deleted) throw ApiException.NotFound("not_found", "target")
            }
            for (id in r.attachmentIds) if (id !in attachmentBlobs) throw ApiException.Invalid("invalid_request", "unknown attachment $id")
            val serverTs = stampMessage(c, r)
            val msg = Message(
                r.id, r.conversationId, me.userId, me.id, r.kind, r.targetMessageId, r.clientTs, serverTs, messageSeq.incrementAndGet(),
                r.attachmentIds, false, null,
            )
            val envMap = r.envelopes.associate { it.deviceId to MessageEnvelope(it.type, it.body) }
            messages[r.id] = MsgRec(msg, r.kind, envMap)
            if (r.kind == MessageKind.DELETE) messages.getValue(r.targetMessageId!!).deleted = true
            for ((devId, env) in envMap) enqueue(devId, EventType.MESSAGE, c.id, SwahnChatJson.encodeToJsonElement(msg.copy(envelope = env)))
            return SendMessageResponse(msg, false)
        }

        override suspend fun sync(after: Long, limit: Int): SyncResponse = synchronized(lock) {
            log += "sync:$after"
            val all = queues.getValue(deviceId).filter { it.seq > after }
            val page = all.take(limit)
            SyncResponse(page, page.lastOrNull()?.seq ?: after, all.size > page.size)
        }

        override suspend fun ack(request: AckRequest) {
            if (failAcks > 0) { failAcks--; throw IOException("simulated ack failure") }
            onAck?.invoke(deviceId, request.cursor)
            synchronized(lock) {
                log += "ack:${request.cursor}"
                val d = me
                val newlyAcked = queues.getValue(deviceId).filter { it.seq > d.acked && it.seq <= request.cursor }
                d.acked = maxOf(d.acked, request.cursor)
                for (e in newlyAcked) {
                    if (e.type != EventType.MESSAGE) continue
                    val m = messages[(e.data as kotlinx.serialization.json.JsonObject)["id"]!!.toString().trim('"')] ?: continue
                    if (m.kind != MessageKind.MESSAGE) continue
                    val key = "${m.msg.id}/${d.userId}"
                    if (!deliveredPairs.add(key)) continue
                    val data = SwahnChatJson.encodeToJsonElement(
                        ReceiptData(m.msg.conversationId, d.userId, ReceiptData.DELIVERED, listOf(m.msg.id)),
                    )
                    for (sd in activeDevices(m.msg.senderUserId)) enqueue(sd.id, EventType.RECEIPT, m.msg.conversationId, data)
                }
            }
        }

        override suspend fun uploadAttachment(body: RequestBody, purpose: String, sha256Hex: String?): UploadedAttachment {
            val buf = Buffer(); body.writeTo(buf)
            val bytes = buf.readByteArray()
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            if (sha256Hex != null && sha256Hex != sha) throw ApiException.Invalid("checksum_mismatch", "x")
            val id = UUID.randomUUID().toString()
            synchronized(lock) { attachmentBlobs[id] = bytes; log += "upload:$id" }
            return UploadedAttachment(id, purpose, "application/octet-stream", bytes.size.toLong(), sha, 0)
        }

        override suspend fun startUpload(request: StartUploadRequest): UploadSession = synchronized(lock) {
            log += "upload-start:${request.size}"
            val id = UUID.randomUUID().toString()
            uploads[id] = UploadRec(id, request.size, request.sha256)
            UploadSession(id, 0, request.size, 0, maxChunkBytes)
        }

        override suspend fun uploadStatus(uploadId: String): UploadSession = synchronized(lock) {
            val u = uploads[uploadId] ?: throw ApiException.NotFound("not_found", "upload")
            UploadSession(u.id, u.bytes.size().toLong(), u.size, 0, maxChunkBytes)
        }

        override suspend fun uploadChunk(uploadId: String, offset: Long, bytes: ByteArray): UploadProgress {
            if (failChunks > 0) { failChunks--; throw IOException("simulated chunk failure") }
            val result = synchronized(lock) {
                log += "upload-chunk:$offset:${bytes.size}"
                val u = uploads[uploadId] ?: throw ApiException.NotFound("not_found", "upload")
                if (bytes.size > maxChunkBytes) throw ApiException.PayloadTooLarge("payload_too_large", "chunk")
                if (offset != u.bytes.size().toLong()) throw ApiException.OffsetMismatch("offset", u.bytes.size().toLong())
                if (offset + bytes.size > u.size) throw ApiException.PayloadTooLarge("payload_too_large", "beyond size")
                u.bytes.write(bytes)
                if (u.bytes.size().toLong() < u.size) {
                    UploadProgress.Partial(u.bytes.size().toLong())
                } else {
                    val all = u.bytes.toByteArray()
                    val sha = MessageDigest.getInstance("SHA-256").digest(all).joinToString("") { "%02x".format(it) }
                    if (u.sha256 != null && u.sha256 != sha) throw ApiException.Invalid("checksum_mismatch", "x")
                    attachmentBlobs[u.id] = all
                    uploads.remove(u.id)
                    log += "upload:${u.id}"
                    UploadProgress.Finished(UploadedAttachment(u.id, "message", "application/octet-stream", all.size.toLong(), sha, 0))
                }
            }
            if (loseChunkResponses > 0) { loseChunkResponses--; throw IOException("simulated lost chunk response") }
            return result
        }

        override suspend fun downloadAttachment(id: String, target: File, onProgress: ((Long) -> Unit)?): Long {
            val bytes = synchronized(lock) { attachmentBlobs[id] } ?: throw ApiException.NotFound("not_found", "x")
            val out = if (corruptAttachments) bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() } else bytes
            target.writeBytes(out)
            return out.size.toLong()
        }
    }

    /** Tamper with a stored attachment blob. */
    fun attachmentBlob(id: String): ByteArray? = synchronized(lock) { attachmentBlobs[id] }

    fun forgetAttachmentBlob(id: String) { synchronized(lock) { attachmentBlobs.remove(id) } }

    fun messageCountInConversation(conversationId: String) =
        messages.values.count { it.msg.conversationId == conversationId && it.kind == MessageKind.MESSAGE }

    // --- realtime -----------------------------------------------------------------------------------------------------

    inner class FakeRealtime(val deviceId: String, private val scope: CoroutineScope) : RealtimeConnection {
        private val _frames = MutableSharedFlow<ServerFrame>(extraBufferCapacity = 256)
        override val frames: SharedFlow<ServerFrame> = _frames
        private val _state = MutableStateFlow(RealtimeState.DISCONNECTED)
        override val state: StateFlow<RealtimeState> = _state

        fun pushSync(cursor: Long) { _frames.tryEmit(ServerFrame.Sync(cursor)) }

        override fun start() {
            realtimes += this
            _state.value = RealtimeState.CONNECTED
            _frames.tryEmit(ServerFrame.Hello(queues[deviceId]?.lastOrNull()?.seq ?: 0))
        }

        override fun stop() {
            realtimes -= this
            _state.value = RealtimeState.STOPPED
        }

        override fun sendTyping(conversationId: String, state: TypingState): Boolean {
            val c = conversations[conversationId] ?: return false
            val me = devices.getValue(deviceId).userId
            for (u in c.members.keys) if (u != me) for (d in activeDevices(u)) {
                realtimes.filter { it.deviceId == d.id }.forEach { it._frames.tryEmit(ServerFrame.Typing(conversationId, me, state)) }
            }
            return true
        }

        override fun sendPing() = true
    }
}

class FakeConnection(private val server: FakeServer, deviceId: String) : ServerConnection {
    private val fakeApi = server.Api(deviceId)
    override val api: SwahnChatApi = fakeApi
    val deviceId: String get() = fakeApi.deviceId
    val sessionFlow = MutableSharedFlow<LogoutReason>(extraBufferCapacity = 4)
    override val sessionEvents: SharedFlow<LogoutReason> = sessionFlow

    /** Learns the server's clock (see [FakeServer.serverTime]) once, at connect, as the real client does from `Date` headers. */
    override val serverClock = ServerClock()
    override fun realtime(scope: CoroutineScope): RealtimeConnection = server.FakeRealtime(fakeApi.deviceId, scope)
}
