package se.swahnchat.core.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import se.swahnchat.core.crypto.AttachmentCipher
import se.swahnchat.core.crypto.NoSessionException
import se.swahnchat.core.crypto.Recipient
import se.swahnchat.core.model.AttachmentPurpose
import se.swahnchat.core.model.AttachmentRef
import se.swahnchat.core.model.CallSignal
import se.swahnchat.core.model.Content
import se.swahnchat.core.model.ContentCodec
import se.swahnchat.core.model.ContentType
import se.swahnchat.core.model.ConversationType
import se.swahnchat.core.model.LocationSpec
import se.swahnchat.core.model.MemberRole
import se.swahnchat.core.model.Emoji
import se.swahnchat.core.model.Envelope
import se.swahnchat.core.model.MessageKind
import se.swahnchat.core.model.PollOption
import se.swahnchat.core.model.PollSpec
import se.swahnchat.core.model.SendMessageRequest
import se.swahnchat.core.model.StartUploadRequest
import se.swahnchat.core.model.UploadProgress
import se.swahnchat.core.model.UploadedAttachment
import se.swahnchat.core.network.ApiException
import se.swahnchat.core.network.StreamingRequestBody
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
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

    suspend fun enqueueText(
        conversationId: String,
        text: String,
        replyTo: String?,
        mentions: List<String> = emptyList(),
        sendAt: Long? = null,
    ): String {
        require(text.isNotBlank()) { "Message text is empty" }
        require(text.toByteArray().size <= MAX_TEXT_BYTES) { "Message text is too long" }
        return enqueueMessage(conversationId, text, replyTo, emptyList(), mentions = checkedMentions(conversationId, mentions), sendAt = sendAt)
    }

    /**
     * The users named with `@` in a group message: at most [Content.MAX_MENTIONS], each a member of the conversation right now
     * (and not ourselves), no duplicates. A direct conversation has no mentions. Anything else is refused before it is queued.
     */
    private suspend fun checkedMentions(conversationId: String, mentions: List<String>): List<String> {
        if (mentions.isEmpty()) return emptyList()
        val account = ctx.requireAccount()
        val conversation = ctx.store.getConversation(conversationId) ?: throw IllegalArgumentException("Unknown conversation")
        require(conversation.type == ConversationType.GROUP) { "Only groups have mentions" }
        val distinct = mentions.distinct().filter { it != account.userId }
        require(distinct.size <= Content.MAX_MENTIONS) { "Too many mentions" }
        val members = conversation.members.mapTo(HashSet()) { it.userId }
        require(distinct.all { it in members }) { "A mention names someone who is not a member" }
        return distinct
    }

    /** A schedule must lie between a minute and [MAX_SCHEDULE_MS] ahead (by this phone's clock). */
    private fun requireSchedule(sendAt: Long) {
        val now = ctx.now()
        require(sendAt >= now + MIN_SCHEDULE_MS) { "A scheduled message goes out at least a minute from now" }
        require(sendAt <= now + MAX_SCHEDULE_MS) { "A scheduled message goes out within 30 days" }
    }

    suspend fun enqueueAttachments(
        conversationId: String,
        attachments: List<OutboxAttachment>,
        caption: String?,
        replyTo: String?,
        sendAt: Long? = null,
    ): String {
        require(attachments.isNotEmpty() && attachments.size <= MAX_ATTACHMENTS) { "1..$MAX_ATTACHMENTS attachments" }
        for (a in attachments) {
            val f = File(a.localPath)
            require(f.isFile) { "Attachment file does not exist: ${a.localPath}" }
            require(f.length() <= ctx.config.maxAttachmentBytes) { "Attachment is too large" }
        }
        return enqueueMessage(conversationId, caption?.takeIf { it.isNotBlank() }, replyTo, attachments, sendAt = sendAt)
    }

    /** A poll: [question] (up to 200 characters) and 2 to 10 options (up to 100 each); options get the ids 0.. in order. */
    suspend fun enqueuePoll(conversationId: String, question: String, options: List<String>, multiple: Boolean): String {
        val q = question.trim()
        val texts = options.map { it.trim() }
        require(q.isNotEmpty() && q.length <= Content.MAX_POLL_QUESTION) { "A poll question is 1..${Content.MAX_POLL_QUESTION} characters" }
        require(texts.size in Content.MIN_POLL_OPTIONS..Content.MAX_POLL_OPTIONS) { "A poll has 2..10 options" }
        require(texts.all { it.isNotEmpty() && it.length <= Content.MAX_POLL_OPTION }) { "A poll option is 1..${Content.MAX_POLL_OPTION} characters" }
        val spec = PollSpec(texts.mapIndexed { i, t -> PollOption(i, t) }, multiple)
        return enqueueMessage(conversationId, q, null, emptyList(), poll = spec)
    }

    /** A position shared once: the sender has seen [location] (and its accuracy) and confirmed it. */
    suspend fun enqueueLocation(conversationId: String, location: LocationSpec): String {
        require(location.isValid) { "That position is not valid" }
        return enqueueMessage(conversationId, null, null, emptyList(), location = location)
    }

    /** A copy of a message of another conversation; [attachments] are copies of the files, uploaded afresh (new keys). */
    suspend fun enqueueForwarded(conversationId: String, text: String?, attachments: List<OutboxAttachment>): String {
        require(!text.isNullOrBlank() || attachments.isNotEmpty()) { "Nothing to forward" }
        require(attachments.size <= MAX_ATTACHMENTS) { "1..$MAX_ATTACHMENTS attachments" }
        if (!text.isNullOrEmpty()) require(text.toByteArray().size <= MAX_TEXT_BYTES) { "Message text is too long" }
        return enqueueMessage(conversationId, text?.takeIf { it.isNotBlank() }, null, attachments, forwarded = true)
    }

    private suspend fun enqueueMessage(
        conversationId: String,
        text: String?,
        replyTo: String?,
        attachments: List<OutboxAttachment>,
        forwarded: Boolean = false,
        mentions: List<String> = emptyList(),
        poll: PollSpec? = null,
        sendAt: Long? = null,
        location: LocationSpec? = null,
    ): String {
        val account = ctx.requireAccount()
        ctx.store.getConversation(conversationId) ?: throw IllegalArgumentException("Unknown conversation")
        if (sendAt != null) requireSchedule(sendAt)
        val id = ctx.config.newId()
        val ts = ctx.now()
        // The timer of the conversation as of now goes into the content, so every phone treats this message alike. A scheduled
        // message takes the timer that stands when it is actually sent (see [releaseScheduled]).
        val expiresIn = if (sendAt != null) null else ctx.store.getTimer(conversationId)?.seconds?.takeIf { it > 0 }
        val message = StoredMessage(
            id = id, conversationId = conversationId, senderUserId = account.userId, senderDeviceId = account.deviceId,
            outgoing = true, status = MessageStatus.PENDING, seq = null, clientTs = sendAt ?: ts, serverTs = null,
            text = text, replyTo = replyTo, forwarded = forwarded, expiresIn = expiresIn,
            mentions = mentions, poll = poll?.let(PollInfo::of), scheduledAt = sendAt, location = location,
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
            forwarded = forwarded, expiresIn = expiresIn, mentions = mentions, poll = poll, sendAt = sendAt, location = location,
        )
        ctx.store.transaction {
            ctx.store.upsertMessage(message)
            ctx.store.putOutbox(entry)
        }
        ctx.emit(EngineEvent.MessageReceived(conversationId, id, fromSelfOtherDevice = false))
        return id
    }

    // --- scheduled messages ---------------------------------------------------------------------------------------------

    /** Moves a scheduled message to [sendAt] (same limits as when it was scheduled). */
    suspend fun reschedule(messageId: String, sendAt: Long) {
        requireSchedule(sendAt)
        val entry = ctx.store.getOutboxEntry(messageId)?.takeIf { it.sendAt != null } ?: throw IllegalArgumentException("Not a scheduled message")
        ctx.store.transaction {
            ctx.store.putOutbox(entry.copy(sendAt = sendAt))
            ctx.store.updateMessage(messageId) { it.copy(scheduledAt = sendAt, clientTs = sendAt) }
        }
        ctx.emit(EngineEvent.MessageChanged(entry.conversationId, messageId))
    }

    /** "Send now": the scheduled message joins the outbox queue straight away (the caller asks for a pass). */
    suspend fun sendScheduledNow(messageId: String) {
        val entry = ctx.store.getOutboxEntry(messageId)?.takeIf { it.sendAt != null } ?: throw IllegalArgumentException("Not a scheduled message")
        releaseScheduled(entry, ctx.now())
        ctx.emit(EngineEvent.MessageChanged(entry.conversationId, messageId))
    }

    /** Throws a scheduled message away before it was sent: nothing ever reached the server. Returns the removed message. */
    suspend fun cancelScheduled(messageId: String): StoredMessage? {
        val entry = ctx.store.getOutboxEntry(messageId)?.takeIf { it.sendAt != null } ?: throw IllegalArgumentException("Not a scheduled message")
        val removed = ctx.store.transaction {
            ctx.store.removeOutbox(messageId)
            ctx.store.deleteMessageLocally(messageId, entry.conversationId, ctx.now())
        }
        ctx.emit(EngineEvent.MessageChanged(entry.conversationId, messageId))
        return removed
    }

    /** The earliest time any scheduled message is due (epoch ms), or null when none is waiting. */
    suspend fun nextScheduledAt(): Long? = ctx.store.getOutbox().mapNotNull { it.sendAt }.minOrNull()

    /**
     * The time has come (or "send now" was pressed): the entry becomes an ordinary one. Its time of writing and its
     * disappearing-messages timer are those of the moment it is really sent, and it queues behind what was written before.
     */
    private suspend fun releaseScheduled(entry: OutboxEntry, now: Long): OutboxEntry {
        val expiresIn = ctx.store.getTimer(entry.conversationId)?.seconds?.takeIf { it > 0 }
        val released = entry.copy(sendAt = null, clientTs = now, createdAt = stamp(), expiresIn = expiresIn, envelopes = null)
        ctx.store.transaction {
            ctx.store.putOutbox(released)
            ctx.store.updateMessage(entry.messageId) { it.copy(scheduledAt = null, clientTs = now, expiresIn = expiresIn) }
        }
        return released
    }

    // --- polls -----------------------------------------------------------------------------------------------------------

    /**
     * Votes on the poll [pollId]: [optionIds] replaces our earlier vote (empty: withdraws it). Shown at once; sent as an
     * encrypted `poll_vote` in a message of kind `reaction`. Returns the id of that message.
     */
    suspend fun enqueueVote(pollId: String, optionIds: List<Int>): String {
        val account = ctx.requireAccount()
        val target = ctx.store.getMessage(pollId) ?: throw IllegalArgumentException("Unknown message")
        val poll = target.poll ?: throw IllegalArgumentException("Not a poll")
        require(!target.deleted && target.decryptError == null) { "That poll cannot be voted on" }
        require(target.status != MessageStatus.PENDING && target.status != MessageStatus.FAILED) { "The poll has not been sent yet" }
        require(!poll.closed) { "The poll is closed" }
        val ids = optionIds.distinct()
        require(poll.options.map { it.id }.containsAll(ids)) { "Not an option of the poll" }
        require(poll.multiple || ids.size <= 1) { "Only one choice in this poll" }
        val id = ctx.config.newId()
        val ts = ctx.now()
        ctx.store.transaction {
            ctx.store.putPollVote(PollVote(pollId, target.conversationId, account.userId, ids, null, ts))
            ctx.store.putOutbox(
                OutboxEntry(
                    messageId = id, conversationId = target.conversationId, kind = OutboxKind.POLL_VOTE, targetMessageId = pollId,
                    text = null, replyTo = null, attachments = emptyList(), clientTs = ts, createdAt = stamp(), optionIds = ids,
                ),
            )
        }
        ctx.emit(EngineEvent.MessageChanged(target.conversationId, pollId))
        return id
    }

    /** The creator ends the poll: nobody can vote afterwards. Only the author of the poll can; shown at once. */
    suspend fun enqueueClosePoll(pollId: String): String {
        val account = ctx.requireAccount()
        val target = ctx.store.getMessage(pollId) ?: throw IllegalArgumentException("Unknown message")
        val poll = target.poll ?: throw IllegalArgumentException("Not a poll")
        require(target.outgoing && target.senderUserId == account.userId) { "Only the creator can close the poll" }
        require(!target.deleted && !poll.closed) { "The poll is closed already" }
        require(target.status != MessageStatus.PENDING && target.status != MessageStatus.FAILED) { "The poll has not been sent yet" }
        val id = ctx.config.newId()
        val ts = ctx.now()
        ctx.store.transaction {
            ctx.store.updateMessage(pollId) { it.copy(poll = it.poll?.copy(closed = true)) }
            ctx.store.putOutbox(
                OutboxEntry(
                    messageId = id, conversationId = target.conversationId, kind = OutboxKind.POLL_VOTE, targetMessageId = pollId,
                    text = null, replyTo = null, attachments = emptyList(), clientTs = ts, createdAt = stamp(), close = true,
                ),
            )
        }
        ctx.emit(EngineEvent.MessageChanged(target.conversationId, pollId))
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
        require(target.poll == null && target.location == null) { "A poll or a position cannot be edited" }
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
            ctx.store.removeReactions(messageId)
            ctx.store.removePollVotes(messageId)
            ctx.store.setStarred(messageId, target.conversationId, false, 0)
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

    /**
     * Reacts to [messageId] with [emoji], or takes our reaction back when it already is [emoji] (one reaction per member and
     * message). Shown at once; sent as an encrypted `reaction` to every device like a message. Returns the id of the
     * reaction message.
     */
    suspend fun enqueueReaction(messageId: String, emoji: String): String {
        require(Emoji.isValid(emoji)) { "Not a single emoji" }
        val account = ctx.requireAccount()
        val target = ctx.store.getMessage(messageId) ?: throw IllegalArgumentException("Unknown message")
        require(!target.deleted && !target.isSystem && target.decryptError == null) { "That message cannot be reacted to" }
        require(target.status != MessageStatus.PENDING && target.status != MessageStatus.FAILED) { "The message has not been sent yet" }
        val current = ctx.store.getReaction(messageId, account.userId)
        val remove = current?.emoji == emoji
        val id = ctx.config.newId()
        val ts = ctx.now()
        ctx.store.transaction {
            ctx.store.putReaction(Reaction(messageId, target.conversationId, account.userId, if (remove) null else emoji, null, ts))
            ctx.store.putOutbox(
                OutboxEntry(
                    messageId = id, conversationId = target.conversationId, kind = MessageKind.REACTION, targetMessageId = messageId,
                    text = emoji, replyTo = null, attachments = emptyList(), clientTs = ts, createdAt = stamp(), remove = remove,
                ),
            )
        }
        ctx.emit(EngineEvent.MessageChanged(target.conversationId, messageId))
        return id
    }

    /**
     * Sets the disappearing-messages timer of [conversationId] to [seconds] (0: off). The phones learn it from an encrypted
     * `timer` message; the server's pruning timer is patched afterwards, once that message is out.
     */
    suspend fun enqueueTimer(conversationId: String, seconds: Long): String {
        require(Content.isValidTimer(seconds)) { "Not an allowed timer" }
        val account = ctx.requireAccount()
        val conversation = ctx.store.getConversation(conversationId) ?: throw IllegalArgumentException("Unknown conversation")
        // the same rule as the server's PATCH: in a group only administrators, in a direct chat either person
        require(
            conversation.type != ConversationType.GROUP ||
                conversation.members.any { it.userId == account.userId && it.role == MemberRole.ADMIN },
        ) { "Only administrators can change the timer of a group" }
        val id = ctx.config.newId()
        val ts = ctx.now()
        ctx.store.transaction {
            ctx.store.putTimer(ConversationTimer(conversationId, seconds, account.userId, null, ts))
            directory.postNotice(conversation, "sys-timer-$id", SystemEvent.TimerChanged(account.userId, seconds), at = ts)
            ctx.store.putOutbox(
                OutboxEntry(
                    messageId = id, conversationId = conversationId, kind = OutboxKind.TIMER, targetMessageId = null, text = null,
                    replyTo = null, attachments = emptyList(), clientTs = ts, createdAt = stamp(), expiresIn = seconds,
                ),
            )
        }
        ctx.emit(EngineEvent.ConversationChanged(conversationId))
        return id
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
            clientTs = m.clientTs, createdAt = stamp(), forwarded = m.forwarded, expiresIn = m.expiresIn,
            mentions = m.mentions, poll = m.poll?.toSpec(), location = m.location,
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
        text = null, attachments = emptyList(), deleted = true, replyTo = null, mentions = emptyList(), poll = null, location = null,
        pendingEnvelopeType = null, pendingEnvelopeBody = null, decryptError = null,
    )

    // --- processing ---------------------------------------------------------------------------------------------------

    /** One pass over the outbox, oldest first. Safe to call concurrently (single flight). */
    suspend fun process(): OutboxResult = mutex.withLock {
        var sent = 0
        var failed = 0
        var pending = 0
        var offline = false
        // A scheduled message whose time has not come is not part of the queue (and must not hold it up); one that is due joins it.
        val now = ctx.now()
        val entries = ctx.store.getOutbox().mapNotNull { e ->
            when {
                e.sendAt == null -> e
                e.sendAt <= now -> releaseScheduled(e, now)
                else -> null
            }
        }
        try {
            retryPendingTimerPatches()
        } catch (e: ApiException) {
            if (e.isLoggedOut) throw e
        }
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
                val envelopes = encryptMissing(entry.envelopes, prepared, content)
                if (envelopes != entry.envelopes) {
                    entry = entry.copy(envelopes = envelopes)
                    ctx.store.putOutbox(entry)
                }
                val request = SendMessageRequest(
                    id = entry.messageId, conversationId = entry.conversationId,
                    kind = serverKind(entry),
                    targetMessageId = entry.targetMessageId, clientTs = entry.clientTs,
                    attachmentIds = entry.attachments.mapNotNull { it.uploadedId }, envelopes = envelopes,
                )
                try {
                    val response = ctx.api.sendMessage(request)
                    afterSent(entry, response.message.seq, response.message.serverTs)
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
            if (entry.kind == MessageKind.REACTION) dropUnsentReaction(entry)
            if (entry.kind == OutboxKind.POLL_VOTE) dropUnsentPollVote(entry)
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
            if (entry.kind == MessageKind.REACTION) {
                // The reaction now has its place in the server's order (it was shown as unsent until now).
                val me = ctx.requireAccount().userId
                val own = ctx.store.getReaction(entry.targetMessageId!!, me)
                if (own != null && own.seq == null && own.at == entry.clientTs) ctx.store.putReaction(own.copy(seq = seq))
            }
            if (entry.kind == OutboxKind.POLL_VOTE && !entry.close) {
                val me = ctx.requireAccount().userId
                val own = ctx.store.getPollVote(entry.targetMessageId!!, me)
                if (own != null && own.seq == null && own.at == entry.clientTs) ctx.store.putPollVote(own.copy(seq = seq))
            }
            if (entry.kind == OutboxKind.TIMER) {
                val own = ctx.store.getTimer(entry.conversationId)
                if (own != null && own.seq == null && own.at == entry.clientTs) ctx.store.putTimer(own.copy(seq = seq))
            }
            ctx.store.removeOutbox(entry.messageId)
        }
        ctx.emit(EngineEvent.MessageChanged(entry.conversationId, entry.targetMessageId ?: entry.messageId))
    }

    /** A timer change is an ordinary `message` for the server; everything else is sent under its own kind. */
    private fun serverKind(entry: OutboxEntry): String = when (entry.kind) {
        OutboxKind.TIMER -> MessageKind.MESSAGE
        OutboxKind.POLL_VOTE -> MessageKind.REACTION // the server sees a reaction, not that it is a vote
        else -> entry.kind
    }

    private suspend fun afterSent(entry: OutboxEntry, seq: Long, serverTs: Long) {
        markSent(entry, seq, serverTs)
        if (entry.kind == OutboxKind.TIMER) patchServerTimer(entry.conversationId, entry.expiresIn ?: 0L)
    }

    private suspend fun dropUnsentReaction(entry: OutboxEntry) {
        val me = ctx.requireAccount().userId
        val own = ctx.store.getReaction(entry.targetMessageId ?: return, me)
        if (own != null && own.seq == null && own.at == entry.clientTs) ctx.store.removeReaction(own.messageId, me)
    }

    /** A vote or closing that failed for good: our unsent vote is forgotten, a closing we showed is taken back. */
    private suspend fun dropUnsentPollVote(entry: OutboxEntry) {
        val pollId = entry.targetMessageId ?: return
        if (entry.close) {
            ctx.store.updateMessage(pollId) { it.copy(poll = it.poll?.copy(closed = false)) }
            return
        }
        val me = ctx.requireAccount().userId
        val own = ctx.store.getPollVote(pollId, me)
        if (own != null && own.seq == null && own.at == entry.clientTs) ctx.store.removePollVote(pollId, me)
    }

    /**
     * `PATCH /conversations/{id}` with the timer, after its encrypted message was accepted. The server only prunes with it
     * (SECURITY.md), so a failure never undoes the change: it is remembered and tried again on the next pass.
     */
    private suspend fun patchServerTimer(conversationId: String, seconds: Long) {
        val key = PENDING_TIMER_PATCH + conversationId
        try {
            ctx.api.updateConversation(conversationId, disappearingSeconds = seconds.takeIf { it > 0 }, clearDisappearing = seconds == 0L)
            ctx.store.setMeta(key, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            ctx.store.setMeta(key, seconds.toString())
        } catch (e: ApiException.Server) {
            ctx.store.setMeta(key, seconds.toString())
        } catch (e: ApiException.RateLimited) {
            ctx.store.setMeta(key, seconds.toString())
        } catch (e: ApiException) {
            // 403 for a member who may not (groups: admins only), 404, 422: not worth repeating. Sessions ending surface elsewhere.
            ctx.store.setMeta(key, null)
        }
    }

    private suspend fun retryPendingTimerPatches() {
        for (c in ctx.store.getConversations()) {
            val seconds = ctx.store.getMeta(PENDING_TIMER_PATCH + c.id)?.toLongOrNull() ?: continue
            patchServerTimer(c.id, seconds)
        }
    }

    // --- call signals -------------------------------------------------------------------------------------------------

    /**
     * Sends one call signal to the other member of a `direct` conversation and to this account's other devices. Unlike
     * [process] this never touches the outbox: a signal is time-critical and must not be delivered hours later, so
     * nothing is stored, there is no local message, and it does not wait behind a running outbox pass. The encryption
     * (shared with [sendEntry]: recipients, sessions, mismatch handling, the crypto lock) happens once; a failed POST
     * is repeated up to [SIGNAL_SEND_RETRIES] times within [SIGNAL_SEND_TIMEOUT_MS] with the same message id and the
     * same ciphertexts, so a lost response ends in `duplicate: true` and never in a second delivery.
     *
     * @throws IOException the server could not be reached in time
     * @throws ApiException the server refused (a `429` is not retried)
     * @throws IllegalStateException a device is not trusted (the identity change is noted like for a message), or we are not a member
     * @throws IllegalArgumentException the conversation is unknown or not `direct`
     */
    suspend fun sendSignal(conversationId: String, signal: CallSignal) {
        val first = ctx.store.getConversation(conversationId) ?: throw IllegalArgumentException("Unknown conversation")
        require(first.type == ConversationType.DIRECT) { "Calls are only possible in direct conversations" }
        val id = ctx.config.newId()
        val clientTs = ctx.now()
        val content = Content(type = ContentType.CALL, conversationId = conversationId, messageId = id, call = signal)
        var envelopes: List<Envelope>? = null
        var mismatch: ApiException.MismatchedDevices? = null
        try {
            repeat(ctx.config.maxMismatchRetries + 1) {
                val conversation = ctx.store.getConversation(conversationId) ?: error("Not a member")
                val prepared = directory.ensureSessions(directory.recipientsFor(conversation))
                val sealed = encryptMissing(envelopes, prepared, content)
                envelopes = sealed
                val request = SendMessageRequest(id = id, conversationId = conversationId, kind = MessageKind.CALL, clientTs = clientTs, envelopes = sealed)
                try {
                    postSignal(request)
                    return
                } catch (e: ApiException.MismatchedDevices) {
                    mismatch = e
                    val extra = refreshAfterMismatch(conversationId, e) ?: error("Not a member")
                    envelopes = sealed.filter { it.deviceId !in extra }
                }
            }
        } catch (e: IdentityChangedException) {
            if (!e.unapproved) directory.noteIdentityChange(e.userId, e.deviceId, e.newKey)
            else ctx.emit(EngineEvent.IdentityChanged(e.userId, e.deviceId, e.newKey))
            error("A device is not trusted")
        }
        throw mismatch ?: IllegalStateException("Signal not sent")
    }

    /** One signal POST, repeated on a network error or a 5xx (same request, so the server de-duplicates), within the time box. */
    private suspend fun postSignal(request: SendMessageRequest) {
        try {
            withTimeout(SIGNAL_SEND_TIMEOUT_MS) {
                var attempt = 0
                while (true) {
                    try {
                        ctx.api.sendMessage(request)
                        return@withTimeout
                    } catch (e: IOException) {
                        if (++attempt > SIGNAL_SEND_RETRIES) throw e
                    } catch (e: ApiException.Server) {
                        if (++attempt > SIGNAL_SEND_RETRIES) throw e
                    }
                    delay(SIGNAL_RETRY_DELAY_MS * attempt)
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw IOException("Call signal timed out", e)
        }
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
            MessageKind.REACTION -> Content(
                type = ContentType.REACTION, emoji = entry.text, remove = true.takeIf { entry.remove },
                conversationId = entry.conversationId, messageId = entry.messageId, targetMessageId = entry.targetMessageId,
            )
            OutboxKind.TIMER -> Content(
                type = ContentType.TIMER, seconds = entry.expiresIn, conversationId = entry.conversationId, messageId = entry.messageId,
            )
            OutboxKind.POLL_VOTE -> Content(
                type = ContentType.POLL_VOTE, optionIds = if (entry.close) null else entry.optionIds.orEmpty(),
                close = true.takeIf { entry.close }, conversationId = entry.conversationId, messageId = entry.messageId,
                targetMessageId = entry.targetMessageId,
            )
            else -> if (entry.poll != null) Content(
                type = ContentType.POLL, text = entry.text, poll = entry.poll, conversationId = entry.conversationId,
                messageId = entry.messageId, expiresIn = entry.expiresIn,
            ) else if (entry.location != null) Content(
                type = ContentType.LOCATION, location = entry.location, conversationId = entry.conversationId,
                messageId = entry.messageId, expiresIn = entry.expiresIn,
            ) else Content(
                type = ContentType.TEXT, text = entry.text, attachments = refs, replyTo = entry.replyTo,
                conversationId = entry.conversationId, messageId = entry.messageId,
                expiresIn = entry.expiresIn, forwarded = true.takeIf { entry.forwarded },
                mentions = entry.mentions.takeIf { it.isNotEmpty() },
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
    private suspend fun encryptMissing(have: List<Envelope>?, prepared: List<Recipient>, content: Content): List<Envelope> {
        val existing = have.orEmpty().associateBy { it.deviceId }
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
        val extraIds = refreshAfterMismatch(entry.conversationId, e) ?: return null
        return entry.copy(envelopes = entry.envelopes?.filter { it.deviceId !in extraIds }).also {
            if (it != entry) ctx.store.putOutbox(it)
        }
    }

    /** The device refresh of [resolveMismatch]; returns the ids of the devices to drop ciphertexts for, or null if we are no longer a member. */
    private suspend fun refreshAfterMismatch(conversationId: String, e: ApiException.MismatchedDevices): Set<String>? {
        val account = ctx.requireAccount()
        val fresh = try {
            ctx.api.getConversation(conversationId)
        } catch (nf: ApiException.NotFound) {
            return null
        }
        directory.applyConversation(fresh)
        val members = fresh.members.mapTo(LinkedHashSet()) { it.userId } + account.userId
        val users = (e.missingDevices + e.extraDevices).map { it.userId }.distinct().filter { it in members }
        for (u in users) directory.refreshDevices(u)
        for (m in fresh.members) if (ctx.store.getDevices(m.userId).isEmpty()) directory.refreshDevices(m.userId)
        for (d in e.extraDevices) directory.dropSession(d.userId, d.deviceId)
        return e.extraDevices.mapTo(HashSet()) { it.deviceId }
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
                val up = if (tmp.length() > ctx.config.resumableUploadThreshold) {
                    uploadResumable(tmp, enc.sha256Hex)
                } else {
                    ctx.api.uploadAttachment(StreamingRequestBody.of(tmp), AttachmentPurpose.MESSAGE, enc.sha256Hex)
                }
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

    /**
     * Uploads [file] (already encrypted) in chunks through `/attachments/uploads`. A chunk that breaks off is not lost: the
     * server's offset is asked for and the upload continues from there (`409 offset_mismatch` names it too). Gives up after a
     * few failures in a row; the next pass then starts a new session with a new key.
     */
    @Suppress("ThrowsCount") // each way the upload can end badly has its own exception
    private suspend fun uploadResumable(file: File, sha256Hex: String): UploadedAttachment {
        val size = file.length()
        val session = ctx.api.startUpload(StartUploadRequest(size = size, sha256 = sha256Hex))
        val chunkSize = minOf(ctx.config.uploadChunkBytes.toLong(), session.maxChunkBytes).coerceAtLeast(1).toInt()
        var offset = session.offset
        var failures = 0
        RandomAccessFile(file, "r").use { raf ->
            while (true) {
                val length = minOf(chunkSize.toLong(), size - offset).toInt()
                if (length <= 0) throw IOException("Upload offset $offset is beyond the file")
                val bytes = ByteArray(length)
                withContext(ctx.config.ioDispatcher) { raf.seek(offset); raf.readFully(bytes) }
                try {
                    when (val r = ctx.api.uploadChunk(session.uploadId, offset, bytes)) {
                        is UploadProgress.Finished -> return r.attachment
                        is UploadProgress.Partial -> offset = r.offset
                    }
                    failures = 0
                } catch (e: ApiException.OffsetMismatch) {
                    if (++failures > MAX_CHUNK_FAILURES) throw e
                    offset = e.serverOffset ?: ctx.api.uploadStatus(session.uploadId).offset
                } catch (e: IOException) {
                    if (++failures > MAX_CHUNK_FAILURES) throw e
                    offset = ctx.api.uploadStatus(session.uploadId).offset // may itself throw: then the pass retries later
                } catch (e: ApiException.Conflict) {
                    if (e.code != "upload_busy" || ++failures > MAX_CHUNK_FAILURES) throw e
                    kotlinx.coroutines.delay(100L * failures)
                }
            }
        }
        @Suppress("UNREACHABLE_CODE") throw IllegalStateException("upload loop ended without a result")
    }

    private class AttachmentSourceMissing(path: String) : Exception("Attachment source missing: $path")

    companion object {
        const val MAX_ATTACHMENTS = 10
        const val MAX_TEXT_BYTES = 40_000

        private const val MAX_CHUNK_FAILURES = 5
        private const val SIGNAL_SEND_RETRIES = 2
        private const val SIGNAL_SEND_TIMEOUT_MS = 3_000L
        private const val SIGNAL_RETRY_DELAY_MS = 300L
        private const val PENDING_TIMER_PATCH = "pending_timer_patch:"

        /** Padded plaintext limit; an envelope body may be 64 KiB and libsignal adds ~100 bytes of overhead. */
        const val MAX_CONTENT_BYTES = 60_000
    }
}
