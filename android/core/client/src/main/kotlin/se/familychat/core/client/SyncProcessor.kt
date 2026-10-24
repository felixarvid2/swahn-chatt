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
import java.io.File
import java.io.IOException
import java.util.Base64

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
            is ParsedEvent.Receipt -> applyReceipt(e.receipt)
            is ParsedEvent.ConversationUpdated -> {
                ctx.store.upsertConversation(e.conversation)
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
            is ParsedEvent.UserRemoved -> {
                ctx.store.replaceDevices(e.userId, emptyList())
                ctx.crypto.deleteAllSessions(e.userId)
                ctx.emit(EngineEvent.DirectoryChanged(e.userId))
            }
            is ParsedEvent.Unknown -> Unit // newer server or malformed: skip, never block the queue
        }
    }

    private suspend fun applyMessage(m: Message) {
        val account = ctx.requireAccount()
        when (m.kind) {
            MessageKind.MESSAGE -> {
                if (ctx.store.getMessage(m.id) != null) return // replay
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
            // deleted before we synced: keep a tombstone so ordering and counts stay right
            write(base.copy(deleted = true))
        } else {
            decryptAndWrite(m, envelope.type, envelope.body) { r ->
                write(
                    when (r) {
                        is Decrypted.Ok -> base.copy(
                            text = r.content.text, replyTo = r.content.replyTo,
                            attachments = r.content.attachments.map { a ->
                                LocalAttachment(
                                    id = a.id, key = a.key, sha256 = a.sha256, size = a.size, mime = a.mime, name = a.name, kind = a.kind,
                                    width = a.width, height = a.height, durationMs = a.durationMs, thumbnail = a.thumbnail,
                                    localPath = null, downloadState = DownloadState.NONE,
                                )
                            },
                        )
                        is Decrypted.Failed -> base.copy(
                            decryptError = r.error,
                            pendingEnvelopeType = if (r.error == DecryptError.UNTRUSTED_IDENTITY) envelope.type else null,
                            pendingEnvelopeBody = if (r.error == DecryptError.UNTRUSTED_IDENTITY) envelope.body else null,
                        )
                    },
                )
            }
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
        data class Failed(val error: DecryptError) : Decrypted
    }

    private suspend fun senderDeviceNumber(m: Message): Int? {
        ctx.store.findDevice(m.senderDeviceId)?.let { return it.number }
        directory.refreshDevices(m.senderUserId)
        return ctx.store.findDevice(m.senderDeviceId)?.number
    }

    /** Decrypts one envelope; never throws for a bad message. Blocking: call with the crypto lock held, off the main thread. */
    private fun decryptNow(senderUserId: String, number: Int, type: String, body: String): Attempt = try {
        Attempt(Decrypted.Ok(ctx.crypto.decryptContent(senderUserId, number, type, body)))
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
     * Decrypts the envelope of [m] and runs [write] with the outcome, both inside one `blockingTransaction` (see the class
     * comment) on an IO thread. The sender's device number is looked up first, because that may need the network, which
     * must never happen inside a database transaction. [write] runs with `runBlocking`, on the transaction's thread, and
     * must only call the store.
     */
    private suspend fun <R> decryptAndWrite(m: Message, type: String, body: String, write: suspend (Decrypted) -> R): R? =
        decryptAndWrite(m.senderUserId, senderDeviceNumber(m), type, body, write)

    private suspend fun <R> decryptAndWrite(
        senderUserId: String,
        number: Int?,
        type: String,
        body: String,
        write: suspend (Decrypted) -> R,
    ): R? {
        if (number == null) {
            write(Decrypted.Failed(DecryptError.UNKNOWN_SENDER_DEVICE))
            return null
        }
        val (attempt, result) = withContext(ctx.config.ioDispatcher) {
            // Lock order: crypto lock first, then the transaction (see MessageCrypto.exclusive).
            ctx.crypto.exclusive {
                ctx.store.blockingTransaction {
                    val a = decryptNow(senderUserId, number, type, body)
                    a to runBlocking { write(a.result) }
                }
            }
        }
        if (attempt.untrusted) noteIdentityChange(senderUserId, number, attempt.untrustedKey)
        return result
    }

    /** Remembers the offending key so the UI can offer to accept it, and tells the user once. */
    suspend fun noteIdentityChange(userId: String, deviceNumber: Int, key: ByteArray?) {
        val metaKey = "$IDENTITY_META_PREFIX$userId:$deviceNumber"
        val encoded = key?.let { Base64.getEncoder().encodeToString(it) } ?: ""
        val previous = ctx.store.getMeta(metaKey)
        ctx.store.setMeta(metaKey, encoded)
        if (previous == encoded) return
        ctx.emit(EngineEvent.IdentityChanged(userId, deviceNumber, key))
        for (c in ctx.store.getConversations()) {
            if (c.members.none { it.userId == userId }) continue
            val id = "sys-idchg-${c.id}-$userId-$deviceNumber-${encoded.hashCode()}"
            if (ctx.store.getMessage(id) != null) continue
            ctx.store.upsertMessage(systemNotice(id, c, SystemEvent.IdentityChanged(userId, deviceNumber)))
            ctx.emit(EngineEvent.MessageReceived(c.id, id, fromSelfOtherDevice = false))
        }
    }

    private fun systemNotice(id: String, c: Conversation, event: SystemEvent): StoredMessage = StoredMessage(
        id = id, conversationId = c.id, senderUserId = "", senderDeviceId = null, outgoing = false,
        status = MessageStatus.RECEIVED, seq = c.lastMessageSeq ?: 0, clientTs = ctx.now(), serverTs = null,
        text = null, replyTo = null, systemEvent = event,
    )

    private suspend fun applyEdit(m: Message) {
        val targetId = m.targetMessageId ?: return
        val target = ctx.store.getMessage(targetId) ?: return
        if (target.senderUserId != m.senderUserId || target.conversationId != m.conversationId) return // only the author may edit
        val envelope = m.envelope ?: return
        val applied = decryptAndWrite(m, envelope.type, envelope.body) { r ->
            // Failed: a replay of an applied edit (duplicate) or undecryptable; keep what we have.
            val content = (r as? Decrypted.Ok)?.content?.takeIf { it.type == ContentType.EDIT } ?: return@decryptAndWrite false
            ctx.store.updateMessage(targetId) { cur ->
                if (cur.deleted) cur
                else cur.copy(text = content.text, edited = true, editedAt = m.serverTs, decryptError = null)
            }
            true
        }
        if (applied == true) ctx.emit(EngineEvent.MessageChanged(m.conversationId, targetId))
    }

    private suspend fun applyDelete(m: Message) {
        val targetId = m.targetMessageId ?: return
        val target = ctx.store.getMessage(targetId) ?: return
        if (target.senderUserId != m.senderUserId || target.conversationId != m.conversationId) return
        ctx.store.updateMessage(targetId) {
            it.copy(
                text = null, replyTo = null, attachments = emptyList(), deleted = true, decryptError = null,
                pendingEnvelopeType = null, pendingEnvelopeBody = null,
            )
        }
        // Downloaded copies of the deleted message's attachments go too.
        for (a in target.attachments) deleteDownloaded(a)
        ctx.emit(EngineEvent.MessageChanged(m.conversationId, targetId))
    }

    private fun deleteDownloaded(a: LocalAttachment) {
        val p = a.localPath ?: return
        val f = File(p)
        // Only files the engine itself decrypted into its attachment dir; never an app-provided source file.
        if (f.isFile && f.canonicalPath.startsWith(ctx.config.attachmentDir.canonicalPath)) f.delete()
    }

    private suspend fun applyReceipt(r: ReceiptData) {
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
                if (updated != null) ctx.emit(EngineEvent.MessageChanged(r.conversationId, id))
            }
            ReceiptData.READ -> {
                val upTo = r.upToSeq ?: return
                for (m in ctx.store.outgoingNotReadBy(r.conversationId, r.userId, upTo)) {
                    ctx.store.updateMessage(m.id) {
                        refreshStatus(it.copy(deliveredTo = it.deliveredTo + r.userId, readBy = it.readBy + r.userId), others)
                    }
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
        val refresh = directory.refreshDevices(userId)
        val account = ctx.requireAccount()
        if (userId != account.userId && refresh.hadPrevious) {
            for (device in refresh.added) {
                for (c in ctx.store.getConversations()) {
                    if (c.members.none { it.userId == userId }) continue
                    val id = "sys-newdev-${c.id}-${device.id}"
                    if (ctx.store.getMessage(id) != null) continue
                    ctx.store.upsertMessage(
                        systemNotice(id, c, SystemEvent.NewDevice(userId, device.number, device.name)),
                    )
                    ctx.emit(EngineEvent.MessageReceived(c.id, id, fromSelfOtherDevice = false))
                }
            }
        }
        ctx.emit(EngineEvent.DirectoryChanged(userId))
    }

    private suspend fun ensureConversation(id: String) {
        if (ctx.store.getConversation(id) != null) return
        try {
            ctx.store.upsertConversation(ctx.api.getConversation(id))
            ctx.emit(EngineEvent.ConversationChanged(id))
        } catch (e: ApiException.NotFound) {
            // Not (or no longer) a member; the message is stored anyway and removed with the conversation event.
        }
    }

    /** Re-decrypts messages that failed with UNTRUSTED_IDENTITY after the user accepted the new key. */
    suspend fun retryUndecrypted(userId: String, deviceNumber: Int) {
        val device = ctx.store.getDevices(userId).firstOrNull { it.number == deviceNumber } ?: return
        for (m in ctx.store.undecryptedFrom(userId, device.id)) {
            val type = m.pendingEnvelopeType ?: continue
            val body = m.pendingEnvelopeBody ?: continue
            val done = decryptAndWrite(userId, deviceNumber, type, body) { r ->
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
                    }
                }
                true
            }
            if (done == true) ctx.emit(EngineEvent.MessageChanged(m.conversationId, m.id))
        }
    }

    companion object {
        const val PAGE_SIZE = 200
        const val IDENTITY_META_PREFIX = "identity_conflict:"
    }
}
