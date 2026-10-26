package se.swahnchat.core.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// All DTOs mirror docs/API.md. Times are milliseconds since the epoch (UTC); ids are lowercase UUID strings;
// binary values are standard base64 with padding.

// ---------------------------------------------------------------------------------------------------------
// Objects
// ---------------------------------------------------------------------------------------------------------

@Serializable
data class User(
    val id: String,
    val username: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("avatar_attachment_id") val avatarAttachmentId: String? = null,
    @SerialName("is_admin") val isAdmin: Boolean = false,
    @SerialName("created_at") val createdAt: Long = 0,
    /** Presence: both are null unless this user opted in to sharing it (`GET /users`); never stored, see `SwahnChatEngine.presence`. */
    val online: Boolean? = null,
    @SerialName("last_seen_at") val lastSeenAt: Long? = null,
)

/** `GET/PATCH /users/me/settings`: today only whether other people may see when this account is online. */
@Serializable
data class UserSettings(@SerialName("share_presence") val sharePresence: Boolean = false)

/** A device of a user. [identityKey] (base64 `ECPublicKey`) is null until the device uploaded keys. */
@Serializable
data class Device(
    val id: String,
    @SerialName("user_id") val userId: String,
    /** Device number 1..127, unique per user; this is the libsignal `deviceId`. */
    val number: Int,
    val name: String,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("last_seen_at") val lastSeenAt: Long = 0,
    @SerialName("identity_key") val identityKey: String? = null,
    /**
     * Only in the answer of `GET /devices/me`: when the server last tried to push to this device and the HTTP status the push
     * server answered (0: no answer). Never stored; other users' devices never carry them.
     */
    @SerialName("last_push_at") val lastPushAt: Long? = null,
    @SerialName("last_push_status") val lastPushStatus: Int? = null,
)

object ConversationType {
    const val DIRECT = "direct"
    const val GROUP = "group"
}

object MemberRole {
    const val ADMIN = "admin"
    const val MEMBER = "member"
}

@Serializable
data class ConversationMember(
    @SerialName("user_id") val userId: String,
    val role: String = MemberRole.MEMBER,
    @SerialName("joined_at") val joinedAt: Long = 0,
)

@Serializable
data class Conversation(
    val id: String,
    /** [ConversationType.DIRECT] or [ConversationType.GROUP]. */
    val type: String,
    val name: String? = null,
    @SerialName("avatar_attachment_id") val avatarAttachmentId: String? = null,
    @SerialName("created_by") val createdBy: String,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("updated_at") val updatedAt: Long = 0,
    @SerialName("last_message_at") val lastMessageAt: Long? = null,
    @SerialName("last_message_seq") val lastMessageSeq: Long? = null,
    @SerialName("my_read_seq") val myReadSeq: Long = 0,
    @SerialName("unread_count") val unreadCount: Int = 0,
    val members: List<ConversationMember> = emptyList(),
    /**
     * The server's pruning timer. Untrusted for anything on the phone: it must never delete local history (SECURITY.md,
     * "Disappearing messages"); only encrypted `timer` content from a member sets the phone's own timer.
     */
    @SerialName("disappearing_seconds") val disappearingSeconds: Long? = null,
)

object EnvelopeType {
    const val PREKEY = "prekey"
    const val WHISPER = "whisper"
}

/** One ciphertext addressed to one device (request side of `POST /messages`). */
@Serializable
data class Envelope(
    @SerialName("device_id") val deviceId: String,
    /** [EnvelopeType.PREKEY] or [EnvelopeType.WHISPER]. */
    val type: String,
    /** base64 ciphertext */
    val body: String,
)

/** The receiving device's own ciphertext inside a [Message]. */
@Serializable
data class MessageEnvelope(val type: String, val body: String)

object MessageKind {
    const val MESSAGE = "message"
    const val EDIT = "edit"
    const val DELETE = "delete"
    const val REACTION = "reaction"

    /** A 1:1 call signal: transient, never stored by the server (docs/API.md "kind: call"). */
    const val CALL = "call"
}

@Serializable
data class Message(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("sender_user_id") val senderUserId: String,
    @SerialName("sender_device_id") val senderDeviceId: String,
    /** [MessageKind] constant. */
    val kind: String = MessageKind.MESSAGE,
    @SerialName("target_message_id") val targetMessageId: String? = null,
    @SerialName("client_ts") val clientTs: Long = 0,
    @SerialName("server_ts") val serverTs: Long = 0,
    val seq: Long = 0,
    @SerialName("attachment_ids") val attachmentIds: List<String> = emptyList(),
    val deleted: Boolean = false,
    val envelope: MessageEnvelope? = null,
    /** The server's pruning time; never used for local deletion (see [Conversation.disappearingSeconds]). */
    @SerialName("expires_at") val expiresAt: Long? = null,
)

// ---------------------------------------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------------------------------------

@Serializable
data class DeviceRef(
    @SerialName("user_id") val userId: String,
    @SerialName("device_id") val deviceId: String,
)

/** Body of every non-2xx response; the optional lists are only present on `409 mismatched_devices`. */
@Serializable
data class ErrorBody(
    val code: String = "unknown",
    val message: String = "",
    @SerialName("missing_devices") val missingDevices: List<DeviceRef> = emptyList(),
    @SerialName("extra_devices") val extraDevices: List<DeviceRef> = emptyList(),
    /** Only on `409 offset_mismatch` of a resumable upload: the offset the server expects. */
    val offset: Long? = null,
)

// ---------------------------------------------------------------------------------------------------------
// Authentication
// ---------------------------------------------------------------------------------------------------------

@Serializable
data class RedeemInviteRequest(
    val code: String,
    val username: String,
    val password: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("device_name") val deviceName: String,
)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
    @SerialName("device_name") val deviceName: String,
)

@Serializable
data class RefreshRequest(@SerialName("refresh_token") val refreshToken: String)

@Serializable
data class AuthResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("access_expires_at") val accessExpiresAt: Long = 0,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("refresh_expires_at") val refreshExpiresAt: Long = 0,
    val user: User,
    val device: Device,
)

// ---------------------------------------------------------------------------------------------------------
// Administration
// ---------------------------------------------------------------------------------------------------------

@Serializable
data class CreateInvitationRequest(
    val note: String? = null,
    @SerialName("expires_in_hours") val expiresInHours: Int? = null,
    @SerialName("is_admin") val isAdmin: Boolean? = null,
)

/** Response of `POST /admin/invitations`; [code] is shown only here. */
@Serializable
data class CreatedInvitation(
    val id: String,
    val code: String,
    val note: String? = null,
    @SerialName("is_admin") val isAdmin: Boolean = false,
    @SerialName("expires_at") val expiresAt: Long = 0,
    @SerialName("created_at") val createdAt: Long = 0,
)

@Serializable
data class Invitation(
    val id: String,
    val note: String? = null,
    @SerialName("is_admin") val isAdmin: Boolean = false,
    @SerialName("expires_at") val expiresAt: Long = 0,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("used_at") val usedAt: Long? = null,
    @SerialName("used_by") val usedBy: String? = null,
    @SerialName("revoked_at") val revokedAt: Long? = null,
    @SerialName("max_uses") val maxUses: Int = 1,
    val uses: Int = 0,
    /** What can still be redeemed; 0 once used up, revoked or expired. */
    val remaining: Int = 1,
)

// ---------------------------------------------------------------------------------------------------------
// Users and devices
// ---------------------------------------------------------------------------------------------------------

@Serializable
data class ChangePasswordRequest(
    @SerialName("current_password") val currentPassword: String,
    @SerialName("new_password") val newPassword: String,
)

/** `POST /auth/password/reset`: redeems a reset code from an administrator; signs in on a new device like a login. */
@Serializable
data class PasswordResetRequest(
    val code: String,
    @SerialName("new_password") val newPassword: String,
    @SerialName("device_name") val deviceName: String,
)

/** `PATCH /devices/{id}`. */
@Serializable
data class DeviceRenameRequest(val name: String)

/** `DELETE /users/me`. */
@Serializable
data class DeleteAccountRequest(val password: String)

/** Body of `POST /admin/users/{id}/password-reset` and `DELETE /admin/users/{id}`: the calling administrator's own password. */
@Serializable
data class AdminConfirmRequest(val password: String)

/** `POST /devices/me/push/test` answers `202 {"status":"queued"}`. */
@Serializable
data class PushTestQueued(val status: String = "queued")

@Serializable
data class PushRegistration(val kind: String = "unifiedpush", val endpoint: String)

// ---------------------------------------------------------------------------------------------------------
// Keys
// ---------------------------------------------------------------------------------------------------------

@Serializable
data class SignedPreKeyDto(
    @SerialName("key_id") val keyId: Int,
    @SerialName("public_key") val publicKey: String,
    val signature: String,
)

@Serializable
data class OneTimePreKeyDto(
    @SerialName("key_id") val keyId: Int,
    @SerialName("public_key") val publicKey: String,
)

/** A Kyber (PQ) prekey: `public_key` is a serialized `KEMPublicKey`, `signature` is by the identity key. */
@Serializable
data class PqPreKeyDto(
    @SerialName("key_id") val keyId: Int,
    @SerialName("public_key") val publicKey: String,
    val signature: String,
)

/** `PUT /keys`. The first upload needs the first four fields; later uploads may carry any subset. */
@Serializable
data class UploadKeysRequest(
    @SerialName("registration_id") val registrationId: Int? = null,
    @SerialName("identity_key") val identityKey: String? = null,
    @SerialName("signed_prekey") val signedPrekey: SignedPreKeyDto? = null,
    @SerialName("pq_last_resort_prekey") val pqLastResortPrekey: PqPreKeyDto? = null,
    @SerialName("one_time_prekeys") val oneTimePrekeys: List<OneTimePreKeyDto>? = null,
    @SerialName("one_time_pq_prekeys") val oneTimePqPrekeys: List<PqPreKeyDto>? = null,
)

@Serializable
data class KeyStatus(
    @SerialName("one_time_prekeys") val oneTimePrekeys: Int = 0,
    @SerialName("one_time_pq_prekeys") val oneTimePqPrekeys: Int = 0,
    @SerialName("signed_prekey_id") val signedPrekeyId: Int? = null,
    @SerialName("pq_last_resort_prekey_id") val pqLastResortPrekeyId: Int? = null,
)

@Serializable
data class DeviceKeyBundle(
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_number") val deviceNumber: Int,
    @SerialName("registration_id") val registrationId: Int,
    @SerialName("identity_key") val identityKey: String,
    @SerialName("signed_prekey") val signedPrekey: SignedPreKeyDto,
    @SerialName("pq_prekey") val pqPrekey: PqPreKeyDto,
    /** null when the device's one-time EC keys are exhausted. */
    @SerialName("one_time_prekey") val oneTimePrekey: OneTimePreKeyDto? = null,
)

@Serializable
data class KeyBundleResponse(
    @SerialName("user_id") val userId: String,
    val devices: List<DeviceKeyBundle> = emptyList(),
)

// ---------------------------------------------------------------------------------------------------------
// Conversations
// ---------------------------------------------------------------------------------------------------------

@Serializable
data class CreateConversationRequest(
    val type: String,
    val name: String? = null,
    @SerialName("member_user_ids") val memberUserIds: List<String>,
    @SerialName("avatar_attachment_id") val avatarAttachmentId: String? = null,
)

@Serializable
data class AddMembersRequest(@SerialName("user_ids") val userIds: List<String>)

@Serializable
data class SetRoleRequest(val role: String)

@Serializable
data class MarkReadRequest(
    @SerialName("up_to_seq") val upToSeq: Long,
    /** `false` = silent read: the read pointer moves but the other members get no receipt. Omitted from the wire while true (older servers). */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val notify: Boolean = true,
)

// ---------------------------------------------------------------------------------------------------------
// Messages
// ---------------------------------------------------------------------------------------------------------

@Serializable
data class SendMessageRequest(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    val kind: String,
    @SerialName("target_message_id") val targetMessageId: String? = null,
    @SerialName("client_ts") val clientTs: Long,
    @SerialName("attachment_ids") val attachmentIds: List<String> = emptyList(),
    val envelopes: List<Envelope> = emptyList(),
)

@Serializable
data class SendMessageResponse(val message: Message, val duplicate: Boolean = false)

// ---------------------------------------------------------------------------------------------------------
// Attachments
// ---------------------------------------------------------------------------------------------------------

object AttachmentPurpose {
    const val MESSAGE = "message"
    const val AVATAR = "avatar"
}

/** `POST /attachments/uploads` and `GET /attachments/uploads/{id}`: where a resumable upload stands. */
@Serializable
data class UploadSession(
    @SerialName("upload_id") val uploadId: String,
    val offset: Long = 0,
    val size: Long = 0,
    @SerialName("expires_at") val expiresAt: Long = 0,
    @SerialName("max_chunk_bytes") val maxChunkBytes: Long = 8L * 1024 * 1024,
)

/** Answer of a chunk upload: [UploadedAttachment] after the last byte, else the new [offset]. */
sealed interface UploadProgress {
    data class Partial(val offset: Long) : UploadProgress
    data class Finished(val attachment: UploadedAttachment) : UploadProgress
}

@Serializable
data class UploadOffset(val offset: Long = 0)

@Serializable
data class StartUploadRequest(
    val size: Long,
    val purpose: String = AttachmentPurpose.MESSAGE,
    val sha256: String? = null,
)

@Serializable
data class UploadedAttachment(
    val id: String,
    val purpose: String,
    @SerialName("content_type") val contentType: String = "",
    val size: Long = 0,
    val sha256: String = "",
    @SerialName("created_at") val createdAt: Long = 0,
)

// ---------------------------------------------------------------------------------------------------------
// Synchronisation
// ---------------------------------------------------------------------------------------------------------

@Serializable
data class Event(
    val seq: Long,
    val type: String,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("conversation_id") val conversationId: String? = null,
    /** Shape depends on [type]; use [parseEvent]. */
    val data: JsonElement? = null,
)

@Serializable
data class SyncResponse(
    val events: List<Event> = emptyList(),
    @SerialName("next_cursor") val nextCursor: Long = 0,
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class AckRequest(val cursor: Long)

/** `receipt` event data: either `type = "delivered"` with [messageIds] or `type = "read"` with [upToSeq]. */
@Serializable
data class ReceiptData(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("user_id") val userId: String,
    val type: String,
    @SerialName("message_ids") val messageIds: List<String> = emptyList(),
    @SerialName("up_to_seq") val upToSeq: Long? = null,
) {
    companion object {
        const val DELIVERED = "delivered"
        const val READ = "read"
    }
}

@Serializable
data class ConversationRemovedData(@SerialName("conversation_id") val conversationId: String)

@Serializable
data class UserIdData(@SerialName("user_id") val userId: String)

object EventType {
    const val MESSAGE = "message"
    const val RECEIPT = "receipt"
    const val CONVERSATION_UPDATED = "conversation_updated"
    const val CONVERSATION_REMOVED = "conversation_removed"
    const val DEVICES_CHANGED = "devices_changed"
    const val USER_UPDATED = "user_updated"
    const val USER_REMOVED = "user_removed"
}

// ---------------------------------------------------------------------------------------------------------
// Administration views (admins only)
// ---------------------------------------------------------------------------------------------------------

/** A device of an account as the administrator sees it: with its push health, never the push endpoint. */
@Serializable
data class AdminDevice(
    val id: String,
    val number: Int = 0,
    val name: String,
    @SerialName("last_seen_at") val lastSeenAt: Long = 0,
    @SerialName("push_registered") val pushRegistered: Boolean = false,
    @SerialName("last_push_at") val lastPushAt: Long? = null,
    /** HTTP status of the last push attempt; 0 = no answer. */
    @SerialName("last_push_status") val lastPushStatus: Int? = null,
    @SerialName("push_failures") val pushFailures: Int = 0,
)

/** One row of `GET /admin/users` (removed accounts included: [disabledAt] set). */
@Serializable
data class AdminUser(
    val id: String,
    val username: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("is_admin") val isAdmin: Boolean = false,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("disabled_at") val disabledAt: Long? = null,
    @SerialName("suspended_at") val suspendedAt: Long? = null,
    @SerialName("last_seen_at") val lastSeenAt: Long? = null,
    @SerialName("active_devices") val activeDevices: Int = 0,
    @SerialName("attachment_bytes") val attachmentBytes: Long = 0,
    val devices: List<AdminDevice> = emptyList(),
)

/** `PATCH /admin/users/{id}`. */
@Serializable
data class SetAdminRequest(@SerialName("is_admin") val isAdmin: Boolean)

/** `POST /admin/users/{id}/password-reset`: the [code] is shown only in this answer. */
@Serializable
data class PasswordResetCreated(val code: String, @SerialName("expires_at") val expiresAt: Long)

@Serializable
data class MigrationInfo(val head: String = "", val current: String? = null)

@Serializable
data class DbMaintenanceInfo(
    val at: Long = 0,
    val dialect: String = "",
    val ok: Boolean = true,
    val steps: List<String> = emptyList(),
    val vacuum: String = "",
    @SerialName("duration_ms") val durationMs: Long = 0,
    @SerialName("freelist_ratio") val freelistRatio: Double? = null,
    val error: String? = null,
)

/** `GET /admin/stats`. */
@Serializable
data class AdminStats(
    val users: Int = 0,
    @SerialName("active_devices") val activeDevices: Int = 0,
    val conversations: Int = 0,
    val messages: Long = 0,
    @SerialName("undelivered_envelopes") val undeliveredEnvelopes: Long = 0,
    val attachments: Long = 0,
    @SerialName("db_bytes") val dbBytes: Long? = null,
    @SerialName("attachments_bytes") val attachmentsBytes: Long = 0,
    @SerialName("disk_free_bytes") val diskFreeBytes: Long = 0,
    val version: String = "",
    val migration: MigrationInfo = MigrationInfo(),
    @SerialName("uptime_seconds") val uptimeSeconds: Long = 0,
    @SerialName("db_maintenance") val dbMaintenance: DbMaintenanceInfo? = null,
)

/** One row of `GET /admin/audit` (newest first). The IP address and [details] are the server's; the app shows only the event. */
@Serializable
data class AuditEvent(
    val id: Long,
    val at: Long = 0,
    @SerialName("actor_user_id") val actorUserId: String? = null,
    val action: String,
    @SerialName("target_user_id") val targetUserId: String? = null,
    @SerialName("target_id") val targetId: String? = null,
)
