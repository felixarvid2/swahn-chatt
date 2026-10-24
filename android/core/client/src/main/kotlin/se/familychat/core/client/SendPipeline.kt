package se.familychat.core.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import se.familychat.core.crypto.AttachmentCipher
import se.familychat.core.crypto.NoSessionException
import se.familychat.core.crypto.Recipient
import se.familychat.core.model.AttachmentPurpose
import se.familychat.core.model.AttachmentRef
import se.familychat.core.model.Content
import se.familychat.core.model.ContentCodec
import se.familychat.core.model.ContentType
import se.familychat.core.model.Envelope
import se.familychat.core.model.MessageKind
import se.familychat.core.model.SendMessageRequest
import se.familychat.core.network.ApiException
import se.familychat.core.network.StreamingRequestBody
import java.io.File
import java.io.IOException
import java.util.Base64

/** Outcome of one pass over the outbox. */
data class OutboxResult(
    val sent: Int,
    /** Messages that became permanently FAILED in this pass. */
    val failed: Int,
    /** Entries still waiting (network down, rate limited, mismatch not resolved). */
    val pending: Int,
    /** True if the pass stopped early because the server was unreachable. */
    val offline: Boolean,
)

private sealed interface SendOutcome {
    data object Sent : SendOutcome
    data class Failed(val reason: FailureReason) : SendOutcome

    /** Try again later; [offline] stops the whole pass (to keep ordering and not hammer a dead network). */
    data class Retry(val offline: Boolean, val error: String) : SendOutcome
}

/**
 * The send path: every message is first written locally as PENDING with its final id and an outbox entry, then
 * encrypted for the current device list and posted. Retries reuse the same id (server-side idempotency) and the
 * ciphertexts already produced (so a retry does not advance the ratchet again).
 */
internal class SendPipeline(private val ctx: EngineContext, private val directory: DeviceDirectory) {
    private val mutex = Mutex()
    private val stampLock = Any()
    private var lastStamp = 0L

    /** Strictly increasing creation stamp so the outbox keeps the order messages were written in. */
    private fun stamp(): Long = synchronized(stampLock) {
        val s = maxOf(ctx.now(), lastStamp + 1)
        lastStamp = s
        s
    }

    // --- enqueue ------------------------------------------------------------------------------------------------------

    suspend fun enqueueText(conversationId: String, text: String, replyTo: String?): String {
        require(text.isNotBlank()) { "Message text is empty" }
        require(text.toByteArray().size <= MAX_TEXT_BYTES) { "Message text is too long" }
        return enqueueMessage(conversationId, text, replyTo, emptyList())
    }

    suspend fun enqueueAttachments(
        conversationId: String,
        attachments: List<OutboxAttachment>,
        caption: String?,
        replyTo: String?,
    ): String {
        require(attachments.isNotEmpty() && attachments.size <= MAX_ATTACHMENTS) { "1..$MAX_ATTACHMENTS attachments" }
        for (a in attachments) {
            val f = File(a.localPath)
            require(f.isFile) { "Attachment file does not exist: ${a.localPath}" }
            require(f.length() <= ctx.config.maxAttachmentBytes) { "Attachment is too large" }
        }
        return enqueueMessage(conversationId, caption?.takeIf { it.isNotBlank() }, replyTo, attachments)
    }

    private suspend fun enqueueMessage(
        conversationId: String,
        text: String?,
        replyTo: String?,
        attachments: List<OutboxAttachment>,
    ): String {
        val account = ctx.requireAccount()
        ctx.store.getConversation(conversationId) ?: throw IllegalArgumentException("Unknown conversation")
        val id = ctx.config.newId()
        val ts = ctx.now()
        val message = StoredMessage(
            id = id, conversationId = conversationId, senderUserId = account.userId, senderDeviceId = account.deviceId,
            outgoing = true, status = MessageStatus.PENDING, seq = null, clientTs = ts, serverTs = null,
            text = text, replyTo = replyTo,
            attachments = attachments.mapIndexed { i, a ->
                LocalAttachment(
                    id = "local-$id-$i", key = null, sha256 = null, size = File(a.localPath).length(), mime = a.mime,
                    name = a.name, kind = a.kind, width = a.width, height = a.height, durationMs = a.durationMs,
                    thumbnail = a.thumbnail, localPath = a.localPath, downloadState = DownloadState.DONE,
                )
            },
        )
        val entry = OutboxEntry(
            messageId = id, conversationId = conversationId, kind = MessageKind.MESSAGE, targetMessageId = null,
            text = text, replyTo = replyTo, attachments = attachments, clientTs = ts, createdAt = stamp(),
        )
        ctx.store.transaction {
            ctx.store.upsertMessage(message)
            ctx.store.putOutbox(entry)
        }
        ctx.emit(EngineEvent.MessageReceived(conversationId, id, fromSelfOtherDevice = false))
        return id
    }

    suspend fun enqueueEdit(messageId: String, newText: String) {
        require(newText.isNotBlank()) { "Message text is empty" }
        require(newText.toByteArray().size <= MAX_TEXT_BYTES) { "Message text is too long" }
        val account = ctx.requireAccount()
        val target = ctx.store.getMessage(messageId) ?: throw IllegalArgumentException("Unknown message")
        require(target.outgoing && target.senderUserId == account.userId && !target.deleted && !target.isSystem) {
            "Only your own messages can be edited"
        }
        val now = ctx.now()
        val pending = ctx.store.getOutboxEntry(messageId)
        ctx.store.transaction {
            if (pending != null && pending.kind == MessageKind.MESSAGE) {
                // Not sent yet: just change the text that will be sent; no edit message needed.
                ctx.store.putOutbox(pending.copy(text = newText, envelopes = null))
                ctx.store.updateMessage(messageId) { it.copy(text = newText) }
            } else {
                ctx.store.updateMessage(messageId) { it.copy(text = newText, edited = true, editedAt = now) }
                ctx.store.putOutbox(
                    OutboxEntry(
                        messageId = ctx.config.newId(), conversationId = target.conversationId, kind = MessageKind.EDIT,
                        targetMessageId = messageId, text = newText, replyTo = null, attachments = emptyList(),
                        clientTs = now, createdAt = stamp(),
                    ),
                )
            }
        }
        ctx.emit(EngineEvent.MessageChanged(target.conversationId, messageId))
    }

    suspend fun enqueueDelete(messageId: String) {
        val account = ctx.requireAccount()
        val target = ctx.store.getMessage(messageId) ?: throw IllegalArgumentException("Unknown message")
        require(target.outgoing && target.senderUserId == account.userId && !target.isSystem) {
            "Only your own messages can be deleted"
        }
        val pending = ctx.store.getOutboxEntry(messageId)
        ctx.store.transaction {
            ctx.store.updateMessage(messageId) { wipe(it) }
            if (pending != null) {
                // Never reached the server: nothing to tell anybody.
                ctx.store.removeOutbox(messageId)
            } else if (target.status != MessageStatus.FAILED) {
                ctx.store.putOutbox(
                    OutboxEntry(
                        messageId = ctx.config.newId(), conversationId = target.conversationId, kind = MessageKind.DELETE,
                        targetMessageId = messageId, text = null, replyTo = null, attachments = emptyList(),
                        clientTs = ctx.now(), createdAt = stamp(),
                    ),
                )
            }
        }
        ctx.emit(EngineEvent.MessageChanged(target.conversationId, messageId))
    }

    /** Re-queues a FAILED outgoing message. */
    suspend fun retry(messageId: String) {
        val m = ctx.store.getMessage(messageId) ?: return
        if (!m.outgoing || m.status != MessageStatus.FAILED || m.deleted) return
        val entry = OutboxEntry(
            messageId = m.id, conversationId = m.conversationId, kind = MessageKind.MESSAGE, targetMessageId = null,
            text = m.text, replyTo = m.replyTo,
            attachments = m.attachments.mapNotNull { a ->
                a.localPath?.let {
                    OutboxAttachment(it, a.mime, a.name, a.kind, a.width, a.height, a.durationMs, a.thumbnail)
                }
            },
            clientTs = m.clientTs, createdAt = stamp(),
        )
        ctx.store.transaction {
            ctx.store.updateMessage(m.id) { it.copy(status = MessageStatus.PENDING, failureReason = null) }
            ctx.store.putOutbox(entry)
        }
        ctx.emit(EngineEvent.MessageChanged(m.conversationId, m.id))
    }

    suspend fun retryFailed(reason: FailureReason) {
        val conversations = ctx.store.getConversations()
        for (c in conversations) {
            for (m in ctx.store.getMessages(c.id, Int.MAX_VALUE)) {
                if (m.outgoing && m.status == MessageStatus.FAILED && m.failureReason == reason) retry(m.id)
            }
        }
    }

    private fun wipe(m: StoredMessage) = m.copy(
        text = null, attachments = emptyList(), deleted = true, replyTo = null,
        pendingEnvelopeType = null, pendingEnvelopeBody = null, decryptError = null,
    )

    // --- processing ---------------------------------------------------------------------------------------------------

    /** One pass over the outbox, oldest first. Safe to call concurrently (single flight). */
    suspend fun process(): OutboxResult = mutex.withLock {
        var sent = 0
        var failed = 0
        var pending = 0
        var offline = false
        val entries = ctx.store.getOutbox()
        for ((index, entry) in entries.withIndex()) {
            when (val r = sendEntry(entry)) {
                SendOutcome.Sent -> sent++
                is SendOutcome.Failed -> failed++
                is SendOutcome.Retry -> {
                    pending++
                    ctx.store.putOutbox((ctx.store.getOutboxEntry(entry.messageId) ?: entry).let {
                        it.copy(attempts = it.attempts + 1, lastError = r.error)
                    })
                    if (r.offline) {
                        offline = true
                        pending += entries.size - index - 1
                        break
                    }
                }
            }
        }
        OutboxResult(sent, failed, pending, offline)
    }

    private suspend fun sendEntry(initial: OutboxEntry): SendOutcome {
        var entry = initial
        try {
            if (entry.kind == MessageKind.MESSAGE && entry.attachments.any { it.uploadedId == null }) {
                entry = uploadAttachments(entry)
            }
            var lastError = "mismatched_devices"
            repeat(ctx.config.maxMismatchRetries + 1) {
                val conversation = ctx.store.getConversation(entry.conversationId)
                    ?: return fail(entry, FailureReason.NOT_A_MEMBER)
                // Messages, edits and deletes all carry ciphertext for the full device set (the server cannot forge a delete).
                val recipients = directory.recipientsFor(conversation)
                val prepared = directory.ensureSessions(recipients)
                val content = buildContent(entry) ?: return fail(entry, FailureReason.TOO_LARGE)
                val envelopes = encryptMissing(entry, prepared, content)
                if (envelopes != entry.envelopes) {
                    entry = entry.copy(envelopes = envelopes)
                    ctx.store.putOutbox(entry)
                }
                val request = SendMessageRequest(
                    id = entry.messageId, conversationId = entry.conversationId, kind = entry.kind,
                    targetMessageId = entry.targetMessageId, clientTs = entry.clientTs,
                    attachmentIds = entry.attachments.mapNotNull { it.uploadedId }, envelopes = envelopes,
                )
                try {
                    val response = ctx.api.sendMessage(request)
                    markSent(entry, response.message.seq, response.message.serverTs)
                    return SendOutcome.Sent
                } catch (e: ApiException.MismatchedDevices) {
                    lastError = "mismatched_devices"
                    entry = resolveMismatch(entry, e) ?: return fail(entry, FailureReason.NOT_A_MEMBER)
                }
            }
            return SendOutcome.Retry(offline = false, error = lastError)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AttachmentSourceMissing) {
            return fail(entry, FailureReason.ATTACHMENT_MISSING)
        } catch (e: IdentityChangedException) {
            // Same path as a refused receive: pending change, event and a notice in the conversations with the user.
            if (!e.unapproved) directory.noteIdentityChange(e.userId, e.deviceId, e.newKey)
            else ctx.emit(EngineEvent.IdentityChanged(e.userId, e.deviceId, e.newKey))
            // A message can be re-queued after the user decided; an edit or delete must not be lost, so it just waits.
            return if (entry.kind == MessageKind.MESSAGE) fail(entry, FailureReason.IDENTITY_CHANGED)
            else SendOutcome.Retry(offline = false, error = "identity_changed")
        } catch (e: IOException) {
            return SendOutcome.Retry(offline = true, error = e.message ?: "network")
        } catch (e: ApiException) {
            return when (e) {
                is ApiException.SessionEnded, is ApiException.TokenExpired -> throw e
                is ApiException.RateLimited -> SendOutcome.Retry(offline = true, error = "rate_limited")
                is ApiException.Server -> SendOutcome.Retry(offline = true, error = "server_error ${e.httpStatus}")
                is ApiException.PayloadTooLarge -> fail(entry, FailureReason.TOO_LARGE)
                is ApiException.NotFound, is ApiException.Forbidden -> fail(entry, FailureReason.NOT_A_MEMBER)
                else -> rejected(entry, e)
            }
        } catch (e: NoSessionException) {
            // Should not happen after ensureSessions; treat as transient.
            return SendOutcome.Retry(offline = false, error = "no_session")
        }
    }

    /** Server said no for a reason other than membership/size: a few tries (attachments may have been purged), then FAILED. */
    private suspend fun rejected(entry: OutboxEntry, e: ApiException): SendOutcome {
        val attempts = entry.attempts + 1
        if (attempts >= ctx.config.maxRejectedAttempts) {
            val reason = if (entry.attachments.isNotEmpty()) FailureReason.ATTACHMENT_MISSING else FailureReason.REJECTED
            return fail(entry, reason)
        }
        // Re-upload attachments on the next pass in case the server dropped the unreferenced uploads.
        val reset = entry.copy(
            attachments = entry.attachments.map {
                it.copy(uploadedId = null, uploadedKey = null, uploadedSha256 = null, plaintextSize = null)
            },
            envelopes = null,
        )
        ctx.store.putOutbox(reset)
        return SendOutcome.Retry(offline = false, error = "${e.code}: ${e.message}")
    }

    private suspend fun fail(entry: OutboxEntry, reason: FailureReason): SendOutcome.Failed {
        ctx.store.transaction {
            ctx.store.removeOutbox(entry.messageId)
            if (entry.kind == MessageKind.MESSAGE) {
                ctx.store.updateMessage(entry.messageId) { it.copy(status = MessageStatus.FAILED, failureReason = reason) }
            }
        }
        ctx.emit(EngineEvent.MessageChanged(entry.conversationId, entry.targetMessageId ?: entry.messageId))
        return SendOutcome.Failed(reason)
    }

    private suspend fun markSent(entry: OutboxEntry, seq: Long, serverTs: Long) {
        ctx.store.transaction {
            if (entry.kind == MessageKind.MESSAGE) {
                ctx.store.updateMessage(entry.messageId) {
                    // Receipts may already have advanced the status; never move it backwards.
                    val status = if (it.status == MessageStatus.DELIVERED || it.status == MessageStatus.READ) it.status else MessageStatus.SENT
                    it.copy(status = status, seq = seq, serverTs = serverTs, failureReason = null)
                }
                ctx.store.getConversation(entry.conversationId)?.let { c ->
                    if (seq > (c.lastMessageSeq ?: 0)) {
                        ctx.store.upsertConversation(c.copy(lastMessageSeq = seq, lastMessageAt = serverTs))
                    }
                }
            }
            ctx.store.removeOutbox(entry.messageId)
        }
        ctx.emit(EngineEvent.MessageChanged(entry.conversationId, entry.targetMessageId ?: entry.messageId))
    }

    // --- content and envelopes ----------------------------------------------------------------------------------------

    private fun buildContent(entry: OutboxEntry): Content? {
        val refs = entry.attachments.map { a ->
            AttachmentRef(
                id = a.uploadedId!!, key = a.uploadedKey!!, sha256 = a.uploadedSha256!!, size = a.plaintextSize ?: 0,
                mime = a.mime, name = a.name, kind = a.kind, width = a.width, height = a.height,
                durationMs = a.durationMs, thumbnail = a.thumbnail,
            )
        }
        // The content repeats the ids the server sees, so a receiver can tell if the server moved or relabelled it.
        var content = when (entry.kind) {
            MessageKind.EDIT -> Content(
                type = ContentType.EDIT, text = entry.text, conversationId = entry.conversationId,
                messageId = entry.messageId, targetMessageId = entry.targetMessageId,
            )
            MessageKind.DELETE -> Content(
                type = ContentType.DELETE, conversationId = entry.conversationId,
                messageId = entry.messageId, targetMessageId = entry.targetMessageId,
            )
            else -> Content(
                type = ContentType.TEXT, text = entry.text, attachments = refs, replyTo = entry.replyTo,
                conversationId = entry.conversationId, messageId = entry.messageId,
            )
        }
        if (ContentCodec.encode(content).size > MAX_CONTENT_BYTES) {
            // Thumbnails are optional; drop them before giving up.
            content = content.copy(attachments = content.attachments.map { it.copy(thumbnail = null) })
            if (ContentCodec.encode(content).size > MAX_CONTENT_BYTES) return null
        }
        return content
    }

    /** Reuses already produced ciphertexts for devices still in the target set and encrypts for the others. */
    private suspend fun encryptMissing(entry: OutboxEntry, prepared: List<Recipient>, content: Content): List<Envelope> {
        val existing = entry.envelopes.orEmpty().associateBy { it.deviceId }
        val missing = prepared.filter { it.deviceId !in existing }
        val fresh = if (missing.isEmpty()) emptyList()
        else withContext(ctx.config.ioDispatcher) { ctx.crypto.encryptContent(content, missing) }
        val freshById = fresh.associateBy { it.deviceId }
        return prepared.map { existing[it.deviceId] ?: freshById.getValue(it.deviceId) }
    }

    /**
     * 409 handling: refresh the conversation, re-read the device lists of the members involved (this also drops the
     * sessions of devices that vanished, and is the only way new devices enter: pinned, announced and, next to verified
     * devices, blocked until approved) and forget ciphertexts for extra devices. Returns the entry to retry with, or null
     * if we are no longer a member.
     *
     * The device ids in the 409 body are the server's claim; they only decide whose list to re-read, and only members of
     * the conversation (and this account) are ever read.
     */
    private suspend fun resolveMismatch(entry: OutboxEntry, e: ApiException.MismatchedDevices): OutboxEntry? {
        val account = ctx.requireAccount()
        val fresh = try {
            ctx.api.getConversation(entry.conversationId)
        } catch (nf: ApiException.NotFound) {
            return null
        }
        directory.applyConversation(fresh)
        val members = fresh.members.mapTo(LinkedHashSet()) { it.userId } + account.userId
        val users = (e.missingDevices + e.extraDevices).map { it.userId }.distinct().filter { it in members }
        for (u in users) directory.refreshDevices(u)
        for (m in fresh.members) if (ctx.store.getDevices(m.userId).isEmpty()) directory.refreshDevices(m.userId)
        for (d in e.extraDevices) directory.dropSession(d.userId, d.deviceId)
        val extraIds = e.extraDevices.mapTo(HashSet()) { it.deviceId }
        return entry.copy(envelopes = entry.envelopes?.filter { it.deviceId !in extraIds }).also {
            if (it != entry) ctx.store.putOutbox(it)
        }
    }

    // --- attachments --------------------------------------------------------------------------------------------------

    private suspend fun uploadAttachments(initial: OutboxEntry): OutboxEntry {
        var entry = initial
        val updated = entry.attachments.toMutableList()
        for ((i, a) in entry.attachments.withIndex()) {
            if (a.uploadedId != null) continue
            val src = File(a.localPath)
            if (!src.isFile) throw AttachmentSourceMissing(a.localPath)
            if (src.length() > ctx.config.maxAttachmentBytes) throw ApiException.PayloadTooLarge("payload_too_large", "attachment too large")
            ctx.config.tempDir.mkdirs()
            val tmp = File.createTempFile("up-", ".enc", ctx.config.tempDir)
            try {
                val enc = withContext(ctx.config.ioDispatcher) { AttachmentCipher.encryptFile(src, tmp) }
                val up = ctx.api.uploadAttachment(StreamingRequestBody.of(tmp), AttachmentPurpose.MESSAGE, enc.sha256Hex)
                if (up.sha256.isNotEmpty() && !up.sha256.equals(enc.sha256Hex, ignoreCase = true)) {
                    throw IOException("Server stored different bytes than uploaded")
                }
                updated[i] = a.copy(
                    uploadedId = up.id, uploadedKey = Base64.getEncoder().encodeToString(enc.key),
                    uploadedSha256 = enc.sha256Hex, plaintextSize = enc.plaintextSize,
                )
            } finally {
                tmp.delete()
            }
            entry = entry.copy(attachments = updated.toList())
            ctx.store.putOutbox(entry)
            val up = updated[i]
            ctx.store.updateMessage(entry.messageId) { m ->
                m.copy(attachments = m.attachments.mapIndexed { idx, la ->
                    if (idx == i) la.copy(id = up.uploadedId!!, key = up.uploadedKey, sha256 = up.uploadedSha256, size = up.plaintextSize ?: la.size) else la
                })
            }
        }
        return entry
    }

    private class AttachmentSourceMissing(path: String) : Exception("Attachment source missing: $path")

    companion object {
        const val MAX_ATTACHMENTS = 10
        const val MAX_TEXT_BYTES = 40_000

        /** Padded plaintext limit; an envelope body may be 64 KiB and libsignal adds ~100 bytes of overhead. */
        const val MAX_CONTENT_BYTES = 60_000
    }
}
