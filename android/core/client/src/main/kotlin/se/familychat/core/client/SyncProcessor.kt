package se.familychat.core.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import se.familychat.core.crypto.DuplicateMessageException
import se.familychat.core.crypto.InvalidCiphertextException
import se.familychat.core.crypto.NoSessionException
import se.familychat.core.crypto.UntrustedIdentityException
import se.familychat.core.model.AckRequest
import se.familychat.core.model.Content
import se.familychat.core.model.ContentType
import se.familychat.core.model.Conversation
import se.familychat.core.model.Message
import se.familychat.core.model.MessageKind
import se.familychat.core.model.ParsedEvent
import se.familychat.core.model.ReceiptData
import se.familychat.core.model.parseEvent
import se.familychat.core.network.ApiException
import java.io.IOException

/**
 * Pulls `GET /sync` pages, applies every event idempotently, commits the cursor, and only then acknowledges.
 *
 * Idempotency rules: a message id already in the store is skipped *before* decryption (decrypting twice would raise a
 * duplicate error); receipts, conversation updates and directory events are plain upserts. Because the cursor only moves
 * after a whole page was applied, a crash or network error mid-page replays that page safely.
 *
 * Crash window: libsignal persists its ratchet state at decryption time. [decryptAndWrite] therefore runs the
 * decryption and the write of the plaintext inside one [LocalStore.blockingTransaction]. With a store that implements it
 * on the same database as the `ProtocolStorage` (the app's Room store) both commit together or not at all, so a crash
 * or a storage error replays the event and decrypts it again. With a store that does not (the in-memory one) a crash in
 * between still loses that one plaintext (the replay yields a [DecryptError.DUPLICATE] placeholder).
 */
internal class SyncProcessor(private val ctx: EngineContext, private val directory: DeviceDirectory) {
    private val mutex = Mutex()

    /** Syncs until the server has nothing more. Concurrent calls queue up. Returns the number of events applied. */
    suspend fun sync(): Int = mutex.withLock {
        var applied = 0
        while (true) {
            val cursor = ctx.store.getSyncCursor()
            val page = ctx.api.sync(cursor, PAGE_SIZE)
            for (event in page.events) {
                applyEvent(parseEvent(event))
                applied++
            }
            if (page.nextCursor > cursor) {
                // 1) commit locally ...
                ctx.store.setSyncCursor(page.nextCursor)
                // 2) ... and only then tell the server it may count the events as delivered.
                try {
                    ctx.api.ack(AckRequest(page.nextCursor))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    // The next sync acknowledges again; the committed state is already safe.
                } catch (e: ApiException) {
                    if (e.isLoggedOut) throw e
                }
            }
            if (!page.hasMore) break
            if (page.nextCursor <= cursor && page.events.isEmpty()) break // defensive: no progress
        }
        if (applied > 0) ctx.emit(EngineEvent.SyncCompleted(applied))
        applied
    }

    // --- events -------------------------------------------------------------------------------------------------------

    private suspend fun applyEvent(e: ParsedEvent) {
        when (e) {
            is ParsedEvent.MessageReceived -> applyMessage(e.message)
            is ParsedEvent.Receipt -> applyReceipt(e.receipt, e.event.createdAt.takeIf { it > 0 })
            is ParsedEvent.ConversationUpdated -> {
                directory.applyConversation(e.conversation)
                ctx.emit(EngineEvent.ConversationChanged(e.conversation.id))
            }
            is ParsedEvent.ConversationRemoved -> {
                ctx.store.removeConversation(e.conversationId)
                ctx.emit(EngineEvent.ConversationRemoved(e.conversationId))
            }
            is ParsedEvent.DevicesChanged -> applyDevicesChanged(e.userId)
            is ParsedEvent.UserUpdated -> {
                ctx.store.upsertUsers(listOf(e.user))
                ctx.emit(EngineEvent.DirectoryChanged(e.user.id))
            }
            is ParsedEvent.UserRemoved -> directory.forgetUser(e.userId)
            is ParsedEvent.Unknown -> Unit // newer server or malformed: skip, never block the queue
        }
    }

    private suspend fun applyMessage(m: Message) {
        val account = ctx.requireAccount()
        when (m.kind) {
            MessageKind.MESSAGE -> {
                if (ctx.store.getMessage(m.id) != null) return // replay
                // "Delete for me" is remembered: a replayed or re-sent event must not bring the message back. (It was decrypted
                // before it was deleted, so skipping it here cannot cost a ratchet step.)
                if (ctx.store.isLocallyDeleted(m.id)) return
                if (m.senderDeviceId == account.deviceId) return // our own echo; already stored as sent
                ensureConversation(m.conversationId)
                storeIncoming(m, account)
            }
            MessageKind.EDIT -> applyEdit(m)
            MessageKind.DELETE -> applyDelete(m)
            else -> Unit
        }
    }

    private suspend fun storeIncoming(m: Message, account: Account) {
        val mine = m.senderUserId == account.userId
        val base = StoredMessage(
            id = m.id, conversationId = m.conversationId, senderUserId = m.senderUserId, senderDeviceId = m.senderDeviceId,
            outgoing = mine, status = if (mine) MessageStatus.SENT else MessageStatus.RECEIVED,
            seq = m.seq, clientTs = m.clientTs, serverTs = m.serverTs, text = null, replyTo = null,
        )
        val envelope = m.envelope
        suspend fun write(message: StoredMessage) {
            retryStore { ctx.store.upsertMessage(message) }
            ctx.store.getConversation(m.conversationId)?.let { c ->
                if (m.seq > (c.lastMessageSeq ?: 0)) ctx.store.upsertConversation(c.copy(lastMessageSeq = m.seq, lastMessageAt = m.serverTs))
            }
        }
        if (m.deleted || envelope == null) {
            // deleted before we synced (the server dropped the ciphertext): keep a tombstone so ordering and counts stay
            // right. No content is involved, so there is nothing to authenticate.
            write(base.copy(deleted = true))
        } else {
            // Network work (sender lookup, membership) happens before the transaction opens.
            val deviceKnown = resolveSender(m.senderUserId, m.senderDeviceId)
            val member = deviceKnown && senderIsMember(m.conversationId, m.senderUserId)
            var rejected = false
            decryptAndWrite(
                m.senderUserId, m.senderDeviceId.takeIf { deviceKnown }, envelope.type, envelope.body,
                check = { c -> member && isBoundTo(c, m, ContentType.TEXT) },
            ) { r ->
                when (r) {
                    is Decrypted.Ok -> write(
                        base.copy(
                            text = r.content.text, replyTo = r.content.replyTo,
                            attachments = r.content.attachments.map { a ->
                                LocalAttachment(
                                    id = a.id, key = a.key, sha256 = a.sha256, size = a.size, mime = a.mime, name = a.name, kind = a.kind,
                                    width = a.width, height = a.height, durationMs = a.durationMs, thumbnail = a.thumbnail,
                                    localPath = null, downloadState = DownloadState.NONE,
                                )
                            },
                        ),
                    )
                    // Authentic ciphertext that does not belong here (wrong conversation or id, or the sender is not a
                    // member): consumed and dropped. Nothing is stored under the conversation the server claimed.
                    is Decrypted.Rejected -> rejected = true
                    is Decrypted.Failed -> write(
                        base.copy(
                            decryptError = r.error,
                            pendingEnvelopeType = if (r.error == DecryptError.UNTRUSTED_IDENTITY) envelope.type else null,
                            pendingEnvelopeBody = if (r.error == DecryptError.UNTRUSTED_IDENTITY) envelope.body else null,
                        ),
                    )
                }
            }
            if (rejected) return
        }
        ctx.emit(EngineEvent.MessageReceived(m.conversationId, m.id, fromSelfOtherDevice = mine))
    }

    private suspend fun retryStore(block: suspend () -> Unit) {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (++attempt >= 3) throw e
                kotlinx.coroutines.delay(50L * attempt)
            }
        }
    }

    private sealed interface Decrypted {
        data class Ok(val content: Content) : Decrypted

        /** Decrypted, but the content is not bound to this event or its sender is not allowed to send it. */
        data object Rejected : Decrypted
        data class Failed(val error: DecryptError) : Decrypted
    }

    /** The content must be the one the sender wrote for exactly this event: type, conversation, message id, target. */
    private fun isBoundTo(c: Content, m: Message, type: String): Boolean =
        c.type == type && c.conversationId == m.conversationId && c.messageId == m.id && c.targetMessageId == m.targetMessageId

    /**
     * True if device [deviceId] is listed for [userId] with a key. Looks at the stored list first and re-reads the user's
     * devices when it is not there. The server's claim about who sent a message is only a hint: the device must belong to
     * that user according to the directory, and the pin (checked by the crypto layer) must agree.
     */
    private suspend fun resolveSender(userId: String, deviceId: String): Boolean {
        suspend fun known() = ctx.store.getDevices(userId).any { it.id == deviceId && it.identityKey != null }
        if (known()) return true
        directory.refreshDevices(userId)
        return known()
    }

    /** True if [userId] is a member of the conversation; asks the server once when the local view says no. */
    private suspend fun senderIsMember(conversationId: String, userId: String): Boolean {
        fun isMember(c: Conversation?) = c != null && c.members.any { it.userId == userId }
        if (isMember(ctx.store.getConversation(conversationId))) return true
        return try {
            val fresh = ctx.api.getConversation(conversationId)
            directory.applyConversation(fresh)
            ctx.emit(EngineEvent.ConversationChanged(conversationId))
            isMember(fresh)
        } catch (e: ApiException.NotFound) {
            false
        }
    }

    /** Decrypts one envelope; never throws for a bad message. Blocking: call with the crypto lock held, off the main thread. */
    private fun decryptNow(senderUserId: String, deviceId: String, type: String, body: String, check: (Content) -> Boolean): Attempt = try {
        val content = ctx.crypto.decryptContent(senderUserId, deviceId, type, body)
        Attempt(if (check(content)) Decrypted.Ok(content) else Decrypted.Rejected)
    } catch (e: UntrustedIdentityException) {
        Attempt(Decrypted.Failed(DecryptError.UNTRUSTED_IDENTITY), untrustedKey = e.newIdentityKey, untrusted = true)
    } catch (e: DuplicateMessageException) {
        Attempt(Decrypted.Failed(DecryptError.DUPLICATE))
    } catch (e: NoSessionException) {
        Attempt(Decrypted.Failed(DecryptError.NO_SESSION))
    } catch (e: InvalidCiphertextException) {
        Attempt(Decrypted.Failed(DecryptError.CORRUPT))
    }

    /** [result] plus, for [DecryptError.UNTRUSTED_IDENTITY], the key the sender presented. */
    private class Attempt(val result: Decrypted, val untrustedKey: ByteArray? = null, val untrusted: Boolean = false)

    /**
     * Decrypts the envelope from device [deviceId] of [senderUserId] and runs [write] with the outcome, both inside one
     * `blockingTransaction` (see the class comment) on an IO thread. [deviceId] is null when the sender's device is not
     * listed for that user ([DecryptError.UNKNOWN_SENDER_DEVICE]); everything that may need the network happens before.
     * [check] judges the decrypted content (binding to the event, sender allowed) and must not block. [write] runs with
     * `runBlocking`, on the transaction's thread, and must only call the store.
     */
    private suspend fun <R> decryptAndWrite(
        senderUserId: String,
        deviceId: String?,
        type: String,
        body: String,
        check: (Content) -> Boolean,
        write: suspend (Decrypted) -> R,
    ): R? {
        if (deviceId == null) {
            write(Decrypted.Failed(DecryptError.UNKNOWN_SENDER_DEVICE))
            return null
        }
        val (attempt, result) = withContext(ctx.config.ioDispatcher) {
            // Lock order: crypto lock first, then the transaction (see MessageCrypto.exclusive).
            ctx.crypto.exclusive {
                ctx.store.blockingTransaction {
                    val a = decryptNow(senderUserId, deviceId, type, body, check)
                    a to runBlocking { write(a.result) }
                }
            }
        }
        if (attempt.untrusted) directory.noteIdentityChange(senderUserId, deviceId, attempt.untrustedKey)
        return result
    }

    /**
     * Edits and deletes act on a message of the sender only, in the conversation the sender wrote them for. The server
     * names the target in the clear but the *decrypted* content must name the same target, conversation and message id,
     * and the sender must be a member and the author of the target. Events without ciphertext are ignored: the server
     * cannot edit or delete anything on its own.
     */
    private suspend fun applyChange(m: Message, type: String, apply: suspend (target: StoredMessage, content: Content) -> Boolean) {
        val targetId = m.targetMessageId ?: return
        val envelope = m.envelope ?: return
        val target = ctx.store.getMessage(targetId) ?: return
        if (target.senderUserId != m.senderUserId || target.conversationId != m.conversationId) return // only the author
        val deviceKnown = resolveSender(m.senderUserId, m.senderDeviceId)
        val member = deviceKnown && senderIsMember(m.conversationId, m.senderUserId)
        val applied = decryptAndWrite(
            m.senderUserId, m.senderDeviceId.takeIf { deviceKnown }, envelope.type, envelope.body,
            check = { c -> member && isBoundTo(c, m, type) },
        ) { r ->
            // Failed: a replay of an applied change (duplicate), untrusted or undecryptable; keep what we have.
            val content = (r as? Decrypted.Ok)?.content ?: return@decryptAndWrite false
            apply(target, content)
        }
        if (applied == true) ctx.emit(EngineEvent.MessageChanged(m.conversationId, targetId))
    }

    private suspend fun applyEdit(m: Message) = applyChange(m, ContentType.EDIT) { target, content ->
        ctx.store.updateMessage(target.id) { cur ->
            if (cur.deleted) cur
            else cur.copy(text = content.text, edited = true, editedAt = m.serverTs, decryptError = null)
        }
        true
    }

    private suspend fun applyDelete(m: Message) {
        var wiped: StoredMessage? = null
        applyChange(m, ContentType.DELETE) { target, _ ->
            ctx.store.updateMessage(target.id) {
                it.copy(
                    text = null, replyTo = null, attachments = emptyList(), deleted = true, decryptError = null,
                    pendingEnvelopeType = null, pendingEnvelopeBody = null,
                )
            }
            wiped = target
            true
        }
        // Downloaded copies of the deleted message's attachments go too.
        wiped?.attachments?.forEach { ctx.deleteDownloadedFile(it.localPath) }
    }

    private suspend fun applyReceipt(r: ReceiptData, at: Long?) {
        val account = ctx.requireAccount()
        val conversation = ctx.store.getConversation(r.conversationId)
        if (r.userId == account.userId) {
            // Another device of ours read the conversation: our unread counter drops here too.
            if (r.type == ReceiptData.READ && r.upToSeq != null) {
                ctx.store.setMyReadSeq(r.conversationId, r.upToSeq!!)
                ctx.emit(EngineEvent.ConversationChanged(r.conversationId))
            }
            return
        }
        val others = conversation?.members?.map { it.userId }?.filter { it != account.userId }.orEmpty()
        when (r.type) {
            ReceiptData.DELIVERED -> for (id in r.messageIds) {
                val updated = ctx.store.updateMessage(id) { m ->
                    if (!m.outgoing) m else refreshStatus(m.copy(deliveredTo = m.deliveredTo + r.userId), others)
                }
                if (updated != null) {
                    if (updated.outgoing) ctx.store.recordReceipt(r.conversationId, id, r.userId, deliveredAt = at, readAt = null)
                    ctx.emit(EngineEvent.MessageChanged(r.conversationId, id))
                }
            }
            ReceiptData.READ -> {
                val upTo = r.upToSeq ?: return
                for (m in ctx.store.outgoingNotReadBy(r.conversationId, r.userId, upTo)) {
                    ctx.store.updateMessage(m.id) {
                        refreshStatus(it.copy(deliveredTo = it.deliveredTo + r.userId, readBy = it.readBy + r.userId), others)
                    }
                    ctx.store.recordReceipt(r.conversationId, m.id, r.userId, deliveredAt = null, readAt = at)
                    ctx.emit(EngineEvent.MessageChanged(r.conversationId, m.id))
                }
            }
        }
    }

    private fun refreshStatus(m: StoredMessage, others: List<String>): StoredMessage {
        if (m.status == MessageStatus.PENDING || m.status == MessageStatus.FAILED || m.status == MessageStatus.RECEIVED) return m
        val status = when {
            others.isNotEmpty() && others.all { it in m.readBy } -> MessageStatus.READ
            others.isNotEmpty() && others.all { it in m.deliveredTo || it in m.readBy } -> MessageStatus.DELIVERED
            else -> MessageStatus.SENT
        }
        // Never go backwards (a member who joined later must not demote an already-READ message).
        return if (status.ordinal > m.status.ordinal) m.copy(status = status) else m
    }

    private suspend fun applyDevicesChanged(userId: String) {
        directory.refreshDevices(userId) // pins, notices and conflicts are all handled there
        ctx.emit(EngineEvent.DirectoryChanged(userId))
    }

    private suspend fun ensureConversation(id: String) {
        if (ctx.store.getConversation(id) != null) return
        try {
            directory.applyConversation(ctx.api.getConversation(id))
            ctx.emit(EngineEvent.ConversationChanged(id))
        } catch (e: ApiException.NotFound) {
            // Not (or no longer) a member; the tombstone is removed with the conversation event.
        }
    }

    /** Re-decrypts messages that failed with UNTRUSTED_IDENTITY after the user accepted the device. */
    suspend fun retryUndecrypted(userId: String, deviceId: String) {
        for (m in ctx.store.undecryptedFrom(userId, deviceId)) {
            val type = m.pendingEnvelopeType ?: continue
            val body = m.pendingEnvelopeBody ?: continue
            val deviceKnown = resolveSender(userId, deviceId)
            val member = deviceKnown && senderIsMember(m.conversationId, userId)
            val done = decryptAndWrite(
                userId, deviceId.takeIf { deviceKnown }, type, body,
                check = { c ->
                    member && c.type == ContentType.TEXT && c.conversationId == m.conversationId &&
                        c.messageId == m.id && c.targetMessageId == null
                },
            ) { r ->
                ctx.store.updateMessage(m.id) {
                    when (r) {
                        is Decrypted.Ok -> it.copy(
                            text = r.content.text, replyTo = r.content.replyTo, decryptError = null,
                            pendingEnvelopeType = null, pendingEnvelopeBody = null,
                            attachments = r.content.attachments.map { a ->
                                LocalAttachment(
                                    a.id, a.key, a.sha256, a.size, a.mime, a.name, a.kind, a.width, a.height, a.durationMs,
                                    a.thumbnail, null, DownloadState.NONE,
                                )
                            },
                        )
                        // Still untrusted: keep the envelope so a later accept can retry; anything else is final.
                        is Decrypted.Failed -> if (r.error == DecryptError.UNTRUSTED_IDENTITY) it else it.copy(
                            decryptError = DecryptError.CORRUPT, pendingEnvelopeType = null, pendingEnvelopeBody = null,
                        )
                        Decrypted.Rejected -> it.copy(
                            decryptError = DecryptError.CORRUPT, pendingEnvelopeType = null, pendingEnvelopeBody = null,
                        )
                    }
                }
                true
            }
            if (done == true) ctx.emit(EngineEvent.MessageChanged(m.conversationId, m.id))
        }
    }

    companion object {
        const val PAGE_SIZE = 200
    }
}
