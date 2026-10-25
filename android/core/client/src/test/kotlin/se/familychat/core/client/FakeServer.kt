package se.familychat.core.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import okhttp3.RequestBody
import okio.Buffer
import se.familychat.core.model.*
import se.familychat.core.network.*
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
        val id: String, val userId: String, val number: Int, val name: String,
        var keys: UploadKeysRequest? = null,
        val ec: ArrayDeque<OneTimePreKeyDto> = ArrayDeque(), val pq: ArrayDeque<PqPreKeyDto> = ArrayDeque(),
        var revoked: Boolean = false, var acked: Long = 0,
    )

    class UserRec(val id: String, val username: String, var displayName: String, val password: String, val isAdmin: Boolean)

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
    val log: MutableList<String> = CopyOnWriteArrayList()

    // --- fault injection ----------------------------------------------------------------------------------------------
    @Volatile var failSends = 0 // next N sendMessage calls throw IOException before reaching the server
    @Volatile var loseSendResponses = 0 // next N sends are stored, then the response is "lost"
    @Volatile var failAcks = 0
    @Volatile var onAck: ((deviceId: String, cursor: Long) -> Unit)? = null
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
    fun connect(deviceId: String = ""): FakeConnection = FakeConnection(this, deviceId)

    /** Log in over the fake API without an engine (used by helper code). */
    private fun auth(u: UserRec, d: DeviceRec) = AuthResponse(
        "access-${d.id}", 0, "refresh-${d.id}", 0, userDto(u), deviceDto(d),
    )

    private fun userDto(u: UserRec) = User(u.id, u.username, u.displayName, null, u.isAdmin, 0)
    private fun deviceDto(d: DeviceRec) = Device(d.id, d.userId, d.number, d.name, 0, 0, d.keys?.identityKey)

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
            enqueue(d.id, EventType.CONVERSATION_UPDATED, c.id, FamilyChatJson.encodeToJsonElement(convDto(c, m)))
        }
    }

    private fun notifyDevicesChanged(userId: String) {
        val peers = HashSet<String>()
        for (c in conversations.values) if (userId in c.members) peers += c.members.keys
        peers += userId
        for (p in peers) for (d in activeDevices(p)) {
            enqueue(d.id, EventType.DEVICES_CHANGED, null, FamilyChatJson.encodeToJsonElement(UserIdData(userId)))
        }
    }

    // --- the API as one authenticated device sees it -------------------------------------------------------------------

    inner class Api(var deviceId: String) : FamilyChatApi {
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
            val u = users.values.firstOrNull { it.username == request.username && it.password == request.password }
                ?: throw ApiException.InvalidCredentials("bad credentials")
            val d = newDevice(u.id, request.deviceName)
            deviceId = d.id
            auth(u, d)
        }

        override suspend fun refresh(request: RefreshRequest): AuthResponse = throw UnsupportedOperationException()
        override suspend fun logout() { synchronized(lock) { revokeDevice(deviceId) } }
        override suspend fun createInvitation(request: CreateInvitationRequest) = CreatedInvitation(UUID.randomUUID().toString(), "CODE", null)
        override suspend fun listInvitations() = emptyList<Invitation>()
        override suspend fun deleteInvitation(id: String) = Unit
        override suspend fun deleteUser(userId: String) = Unit

        override suspend fun listUsers(): List<User> = synchronized(lock) { users.values.map { userDto(it) } }
        override suspend fun me(): User = synchronized(lock) { userDto(users.getValue(me.userId)) }
        override suspend fun updateMe(displayName: String?, avatarAttachmentId: String?, clearAvatar: Boolean): User = me()
        override suspend fun changePassword(request: ChangePasswordRequest) = Unit
        override suspend fun listDevices(userId: String): List<Device> = synchronized(lock) {
            log += "devices:$userId"
            val honest = activeDevices(userId).map { deviceDto(it) }
            listDevicesHook?.invoke(userId, honest) ?: honest
        }

        override suspend fun myDevice(): Device = synchronized(lock) { deviceDto(me) }
        override suspend fun setPush(request: PushRegistration) = Unit
        override suspend fun clearPush() = Unit
        override suspend fun deleteDevice(deviceId: String) { revokeDevice(deviceId) }

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
                for (d in activeDevices(userId)) enqueue(d.id, EventType.CONVERSATION_REMOVED, id, FamilyChatJson.encodeToJsonElement(ConversationRemovedData(id)))
                pushConversation(c)
            }
        }

        override suspend fun listMessages(conversationId: String, beforeSeq: Long?, limit: Int) = emptyList<Message>()

        override suspend fun markRead(conversationId: String, request: MarkReadRequest) {
            synchronized(lock) {
                log += "read:$conversationId:${request.upToSeq}"
                val c = conversations[conversationId]?.takeIf { me.userId in it.members } ?: throw ApiException.NotFound("not_found", "x")
                val mem = c.members.getValue(me.userId)
                if (request.upToSeq <= mem.readSeq) return
                mem.readSeq = request.upToSeq
                val data = FamilyChatJson.encodeToJsonElement(
                    ReceiptData(conversationId, me.userId, ReceiptData.READ, emptyList(), request.upToSeq),
                )
                for (u in c.members.keys) for (d in activeDevices(u)) if (d.id != me.id) enqueue(d.id, EventType.RECEIPT, conversationId, data)
            }
        }

        override suspend fun sendMessage(request: SendMessageRequest): SendMessageResponse {
            if (failSends > 0) { failSends--; throw IOException("simulated network failure") }
            val response = synchronized(lock) { doSend(request) }
            if (loseSendResponses > 0) { loseSendResponses--; throw IOException("simulated lost response") }
            return response
        }

        private fun doSend(r: SendMessageRequest): SendMessageResponse {
            log += "send:${r.id}"
            val c = conversations[r.conversationId]?.takeIf { me.userId in it.members } ?: throw ApiException.NotFound("not_found", "x")
            messages[r.id]?.let {
                if (it.msg.senderUserId != me.userId) throw ApiException.Conflict("id_conflict", "x")
                return SendMessageResponse(it.msg.copy(envelope = null), true)
            }
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
            } else if (r.kind != MessageKind.MESSAGE) {
                val t = messages[r.targetMessageId] ?: throw ApiException.NotFound("not_found", "target")
                if (t.msg.senderUserId != me.userId || t.deleted) throw ApiException.NotFound("not_found", "target")
            }
            for (id in r.attachmentIds) if (id !in attachmentBlobs) throw ApiException.Invalid("invalid_request", "unknown attachment $id")
            expiresAt[r.id] = c.disappearingSeconds?.let { 1000 + it * 1000 }
            val msg = Message(
                r.id, r.conversationId, me.userId, me.id, r.kind, r.targetMessageId, r.clientTs, 1000, messageSeq.incrementAndGet(),
                r.attachmentIds, false, null,
            )
            val envMap = r.envelopes.associate { it.deviceId to MessageEnvelope(it.type, it.body) }
            messages[r.id] = MsgRec(msg, r.kind, envMap)
            if (r.kind == MessageKind.DELETE) messages.getValue(r.targetMessageId!!).deleted = true
            for ((devId, env) in envMap) enqueue(devId, EventType.MESSAGE, c.id, FamilyChatJson.encodeToJsonElement(msg.copy(envelope = env)))
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
                    val data = FamilyChatJson.encodeToJsonElement(
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
    override val api: FamilyChatApi = fakeApi
    val deviceId: String get() = fakeApi.deviceId
    val sessionFlow = MutableSharedFlow<LogoutReason>(extraBufferCapacity = 4)
    override val sessionEvents: SharedFlow<LogoutReason> = sessionFlow
    override fun realtime(scope: CoroutineScope): RealtimeConnection = server.FakeRealtime(fakeApi.deviceId, scope)
}
