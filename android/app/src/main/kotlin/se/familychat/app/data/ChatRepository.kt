package se.familychat.app.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import se.familychat.app.data.db.AppDatabase
import se.familychat.app.data.db.ConversationEntity
import se.familychat.app.data.db.MemberEntity
import se.familychat.app.data.db.UserEntity
import se.familychat.app.data.db.toModel
import se.familychat.app.ui.model.AvatarUi
import se.familychat.app.ui.model.ConversationItemUi
import se.familychat.app.ui.model.MemberUi
import se.familychat.app.ui.model.PreviewKind
import se.familychat.app.ui.model.UserUi
import se.familychat.app.ui.model.delivery
import se.familychat.core.client.FamilyChatEngine
import se.familychat.core.client.StoredMessage
import se.familychat.core.model.ConversationType
import se.familychat.core.model.MemberRole
import se.familychat.core.model.User
import javax.inject.Inject
import javax.inject.Singleton

/** Reactive reads for the UI, on top of the Room tables (writes always go through the engine). */
@Singleton
class ChatRepository @Inject constructor(
    private val db: AppDatabase,
    private val engine: FamilyChatEngine,
) {
    private val ui get() = db.uiDao()

    val users: Flow<List<UserEntity>> get() = ui.observeUsers()

    /** The conversation list with titles, previews and unread counts, newest activity first. */
    fun conversationItems(unknownName: String): Flow<List<ConversationItemUi>> =
        combine(ui.observeOverview(), ui.observeMembers(), ui.observeUsers(), engine.account, engine.typing) { overview, members, users, account, typing ->
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
                        previewDelivery = last?.delivery() ?: se.familychat.app.ui.model.DeliveryUi.NONE,
                        lastActivity = last?.let { it.serverTs ?: it.clientTs } ?: row.lastMessageAt ?: row.updatedAt,
                        unread = row.unread, typing = typing[row.id]?.isNotEmpty() == true,
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
        else -> PreviewKind.TEXT to m.text
    }

    fun conversation(id: String): Flow<ConversationEntity?> = ui.observeConversation(id)

    fun messages(conversationId: String, limit: Int): Flow<List<StoredMessage>> =
        ui.observeMessages(conversationId, limit).map { list -> list.map { it.toModel() } }

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

    fun devicesOf(userId: String): Flow<List<se.familychat.core.model.Device>> =
        ui.observeDevices().map { list -> list.filter { it.userId == userId }.map { it.toModel() } }

    fun user(userId: String): Flow<UserUi?> = ui.observeUsers().map { list ->
        list.firstOrNull { it.id == userId }?.let { UserUi(it.id, it.displayName, it.username, AvatarUi(it.displayName, it.avatarAttachmentId)) }
    }
}
