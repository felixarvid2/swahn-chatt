package se.swahnchat.app.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import se.swahnchat.app.data.db.AppDatabase
import androidx.paging.PagingSource
import se.swahnchat.app.data.db.ConversationEntity
import se.swahnchat.app.data.db.DraftEntity
import se.swahnchat.app.data.db.LatestMessageRow
import se.swahnchat.app.data.db.MessageEntity
import se.swahnchat.app.data.db.MemberEntity
import se.swahnchat.app.data.db.UserEntity
import se.swahnchat.app.data.db.toModel
import se.swahnchat.app.data.db.toPollVote
import se.swahnchat.app.ui.model.AvatarUi
import se.swahnchat.app.ui.model.ConversationItemUi
import se.swahnchat.app.ui.model.MemberUi
import se.swahnchat.app.ui.model.PreviewKind
import se.swahnchat.app.ui.model.SafetyUi
import se.swahnchat.app.ui.model.UserUi
import se.swahnchat.app.ui.model.delivery
import se.swahnchat.core.client.SwahnChatEngine
import se.swahnchat.core.client.PollVote
import se.swahnchat.core.client.Reaction
import se.swahnchat.core.client.ReceiptTimes
import se.swahnchat.core.client.StoredMessage
import se.swahnchat.core.model.ConversationType
import se.swahnchat.core.model.MemberRole
import se.swahnchat.core.model.User
import javax.inject.Inject
import javax.inject.Singleton

/** Reactive reads for the UI, on top of the Room tables (writes always go through the engine). */
@Singleton
class ChatRepository @Inject constructor(
    private val db: AppDatabase,
    private val engine: SwahnChatEngine,
) {
    private val ui get() = db.uiDao()

    val users: Flow<List<UserEntity>> get() = ui.observeUsers()

    /** The conversation list with titles, previews and unread counts, newest activity first. */
    fun conversationItems(unknownName: String): Flow<List<ConversationItemUi>> =
        combine(
            combine(ui.observeOverview(), ui.observeMembers(), ui.observeUsers(), ::Triple),
            engine.account, engine.typing, ui.observeDrafts(),
        ) { (overview, members, users), account, typing, drafts ->
            val now = System.currentTimeMillis()
            val draftBy = drafts.associate { it.conversationId to it.text }
            val userById = users.associateBy { it.id }
            val membersBy = members.groupBy { it.conversationId }
            overview.map { row ->
                val isGroup = row.type == ConversationType.GROUP
                val other = membersBy[row.id].orEmpty().firstOrNull { it.userId != account?.userId }?.let { userById[it.userId] }
                val title = if (isGroup) row.name.orEmpty() else other?.displayName ?: unknownName
                val avatar = if (isGroup) AvatarUi(title, row.avatarAttachmentId) else AvatarUi(title, other?.avatarAttachmentId)
                val last = ui.lastMessage(row.id)?.toModel()
                preview(last, isGroup, account?.userId, userById).let { p ->
                    ConversationItemUi(
                        id = row.id, title = title, isGroup = isGroup, avatar = avatar,
                        previewKind = p.first, previewText = p.second,
                        previewSender = if (isGroup && last != null && !last.outgoing) userById[last.senderUserId]?.displayName else null,
                        previewOwn = last?.outgoing == true,
                        previewDelivery = last?.delivery() ?: se.swahnchat.app.ui.model.DeliveryUi.NONE,
                        lastActivity = last?.let { it.serverTs ?: it.clientTs } ?: row.lastMessageAt ?: row.updatedAt,
                        unread = row.unread, typing = typing[row.id]?.isNotEmpty() == true,
                        draft = draftBy[row.id]?.takeIf { it.isNotBlank() },
                        pinned = row.pinnedAt != null, muted = isMuted(row.mutedUntil, now), archived = row.archived,
                        mentioned = row.mentioned > 0,
                    )
                }
            }
        }.distinctUntilChanged()

    private fun preview(m: StoredMessage?, isGroup: Boolean, me: String?, users: Map<String, UserEntity>): Pair<PreviewKind, String?> = when {
        m == null -> PreviewKind.NONE to null
        m.deleted -> PreviewKind.DELETED to null
        m.decryptError != null -> PreviewKind.UNDECRYPTABLE to null
        m.attachments.isNotEmpty() && m.text.isNullOrBlank() -> when (m.attachments.first().kind) {
            "image" -> PreviewKind.PHOTO to null
            "video" -> PreviewKind.VIDEO to null
            "voice" -> PreviewKind.VOICE to null
            else -> PreviewKind.FILE to m.attachments.first().name
        }
        m.poll != null -> PreviewKind.TEXT to "\uD83D\uDCCA ${m.text.orEmpty()}"
        else -> PreviewKind.TEXT to m.text
    }

    fun conversation(id: String): Flow<ConversationEntity?> = ui.observeConversation(id)

    fun messages(conversationId: String, limit: Int): Flow<List<StoredMessage>> =
        ui.observeMessages(conversationId, limit).map { list -> list.map { it.toModel() } }

    /** The messages of a conversation, newest first, in pages (the conversation screen's list). */
    fun pagedMessages(conversationId: String): PagingSource<Int, MessageEntity> = ui.pagedMessages(conversationId)

    /** One message, again whenever it changes (null: it does not exist, or was deleted on this phone). */
    fun message(id: String): Flow<StoredMessage?> = ui.observeMessage(id).map { it?.toModel() }

    /** The reactions that stand in a conversation, by message id (oldest reaction first). */
    fun reactions(conversationId: String): Flow<Map<String, List<Reaction>>> =
        ui.observeReactions(conversationId).map { list -> list.map { it.toModel() }.groupBy { it.messageId } }.distinctUntilChanged()

    /** The poll votes that stand in a conversation, by poll message id (oldest first). */
    fun pollVotes(conversationId: String): Flow<Map<String, List<PollVote>>> =
        ui.observePollVotes(conversationId).map { list -> list.map { it.toPollVote() }.groupBy { it.messageId } }.distinctUntilChanged()

    /** Ids of the starred messages of a conversation. */
    fun starredIds(conversationId: String): Flow<Set<String>> =
        ui.observeStarredIds(conversationId).map { it.toSet() }.distinctUntilChanged()

    /** The starred messages ([conversationId] null: all conversations), the most recently starred first. */
    fun starred(conversationId: String?): Flow<List<StoredMessage>> =
        ui.observeStarred(conversationId).map { list -> list.map { it.toModel() } }

    /** Messages waiting for their time ([conversationId] null: all), earliest first. */
    fun scheduled(conversationId: String?): Flow<List<StoredMessage>> =
        ui.observeScheduled(conversationId).map { list -> list.map { it.toModel() } }

    /** The disappearing-messages timer this phone applies in the conversation, in seconds (0: off). */
    fun timer(conversationId: String): Flow<Long> = ui.observeTimer(conversationId).map { it?.seconds ?: 0L }.distinctUntilChanged()

    /** When each member acknowledged / read one of our messages, as far as known. */
    fun receipts(messageId: String): Flow<List<ReceiptTimes>> =
        ui.observeReceipts(messageId).map { list -> list.map { ReceiptTimes(it.messageId, it.userId, it.deliveredAt, it.readAt) } }

    fun latestMessage(conversationId: String): Flow<LatestMessageRow?> = ui.observeLatest(conversationId).distinctUntilChanged()

    /** Highest sequence number among the messages from others (null: none yet); the read mark follows it. */
    fun newestIncomingSeq(conversationId: String): Flow<Long?> = ui.observeNewestIncomingSeq(conversationId).distinctUntilChanged()

    suspend fun maxSeq(conversationId: String): Long = ui.maxSeq(conversationId)

    fun incomingAfter(conversationId: String, afterSeq: Long): Flow<Int> = ui.observeIncomingAfter(conversationId, afterSeq)

    suspend fun firstUnreadId(conversationId: String, readSeq: Long): String? = ui.firstUnreadId(conversationId, readSeq)

    /** Offset of a message in [pagedMessages] (how many are newer), or null if it is not in the conversation. */
    suspend fun offsetOf(conversationId: String, messageId: String): Int? =
        if (db.storeDao().message(messageId)?.conversationId == conversationId) ui.newerThan(conversationId, messageId) else null

    // --- drafts ---------------------------------------------------------------------------------------------------

    suspend fun draft(conversationId: String): String? = db.storeDao().draft(conversationId)

    /** Stores the unsent text; blank text removes the draft. */
    suspend fun saveDraft(conversationId: String, text: String) {
        val dao = db.storeDao()
        if (text.isBlank()) dao.deleteDraft(conversationId) else dao.putDraft(DraftEntity(conversationId, text, System.currentTimeMillis()))
    }

    // --- safety numbers -------------------------------------------------------------------------------------------

    /** Whether the other members' devices are all verified, or an identity change waits for the user's decision. */
    fun safety(conversationId: String): Flow<SafetyUi> =
        combine(
            ui.observeMembers(), ui.observeDevices(), ui.observeIdentities(), ui.observeIdentityConflicts(), engine.account,
        ) { members, devices, identities, conflicts, account ->
            val me = account?.userId ?: return@combine SafetyUi()
            val others = members.filter { it.conversationId == conversationId && it.userId != me }.mapTo(HashSet()) { it.userId }
            computeSafety(me, others, devices, identities, conflicts.mapTo(HashSet()) { it.removePrefix(CONFLICT_PREFIX) })
        }.distinctUntilChanged()

    /** Members of a conversation with their user records. */
    fun members(conversationId: String): Flow<List<MemberUi>> =
        combine(ui.observeConversation(conversationId), ui.observeMembers(), ui.observeUsers(), engine.account) { _, members, users, account ->
            val userById = users.associateBy { it.id }
            members.filter { it.conversationId == conversationId }.sortedBy { it.joinedAt }.map { it.toMemberUi(userById, account?.userId) }
        }

    private fun MemberEntity.toMemberUi(users: Map<String, UserEntity>, me: String?): MemberUi {
        val u = users[userId]
        val name = u?.displayName ?: "?"
        return MemberUi(userId, name, u?.username.orEmpty(), AvatarUi(name, u?.avatarAttachmentId), role == MemberRole.ADMIN, userId == me)
    }

    fun allUsers(me: String?): Flow<List<UserUi>> = ui.observeUsers().map { list ->
        list.filter { it.id != me }.map { UserUi(it.id, it.displayName, it.username, AvatarUi(it.displayName, it.avatarAttachmentId)) }
    }

    fun userMap(): Flow<Map<String, User>> = ui.observeUsers().map { list -> list.associate { it.id to it.toModel() } }

    fun outboxCount(): Flow<Int> = ui.observeOutboxCount()

    fun devicesOf(userId: String): Flow<List<se.swahnchat.core.model.Device>> =
        ui.observeDevices().map { list -> list.filter { it.userId == userId }.map { it.toModel() } }

    fun user(userId: String): Flow<UserUi?> = ui.observeUsers().map { list ->
        list.firstOrNull { it.id == userId }?.let { UserUi(it.id, it.displayName, it.username, AvatarUi(it.displayName, it.avatarAttachmentId)) }
    }
}
