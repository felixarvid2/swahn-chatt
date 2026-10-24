package se.familychat.core.network

import okhttp3.RequestBody
import se.familychat.core.model.*
import java.io.File

/**
 * Typed access to every endpoint of docs/API.md. Obtain one from [ApiClient.api].
 *
 * Every function throws [ApiException] for a non-2xx answer and [java.io.IOException] for network failures.
 * Authentication, refresh-on-401 and retry are handled below this interface, so callers never touch tokens.
 */
interface FamilyChatApi {
    // --- health -----------------------------------------------------------------------------------------------
    suspend fun healthz()

    // --- authentication (no bearer token) -----------------------------------------------------------------------
    suspend fun redeemInvite(request: RedeemInviteRequest): AuthResponse
    suspend fun login(request: LoginRequest): AuthResponse

    /** Normally only [ApiClient] calls this; it rotates the refresh token. */
    suspend fun refresh(request: RefreshRequest): AuthResponse

    /** Revokes the session and device on the server. */
    suspend fun logout()

    // --- administration ------------------------------------------------------------------------------------------
    suspend fun createInvitation(request: CreateInvitationRequest = CreateInvitationRequest()): CreatedInvitation
    suspend fun listInvitations(): List<Invitation>
    suspend fun deleteInvitation(id: String)
    suspend fun deleteUser(userId: String)

    // --- users and devices ---------------------------------------------------------------------------------------
    suspend fun listUsers(): List<User>
    suspend fun me(): User

    /** Only non-null arguments are sent; set [clearAvatar] to send `avatar_attachment_id: null`. */
    suspend fun updateMe(displayName: String? = null, avatarAttachmentId: String? = null, clearAvatar: Boolean = false): User
    suspend fun changePassword(request: ChangePasswordRequest)
    suspend fun listDevices(userId: String): List<Device>
    suspend fun myDevice(): Device
    suspend fun setPush(request: PushRegistration)
    suspend fun clearPush()
    suspend fun deleteDevice(deviceId: String)

    // --- keys ----------------------------------------------------------------------------------------------------
    suspend fun uploadKeys(request: UploadKeysRequest)
    suspend fun keyStatus(): KeyStatus

    /** Consumes one one-time prekey per returned device. [deviceId] limits the answer to one device. */
    suspend fun getKeys(userId: String, deviceId: String? = null): KeyBundleResponse

    // --- conversations -------------------------------------------------------------------------------------------
    suspend fun listConversations(): List<Conversation>
    suspend fun createConversation(request: CreateConversationRequest): Conversation
    suspend fun getConversation(id: String): Conversation

    /** Group admins only. Only non-null arguments are sent; [clearAvatar] sends `avatar_attachment_id: null`. */
    suspend fun updateConversation(
        id: String,
        name: String? = null,
        avatarAttachmentId: String? = null,
        clearAvatar: Boolean = false,
    ): Conversation

    suspend fun addMembers(id: String, request: AddMembersRequest): Conversation
    suspend fun setMemberRole(id: String, userId: String, request: SetRoleRequest): Conversation
    suspend fun removeMember(id: String, userId: String)

    /** Newest first. */
    suspend fun listMessages(conversationId: String, beforeSeq: Long? = null, limit: Int = 50): List<Message>
    suspend fun markRead(conversationId: String, request: MarkReadRequest)

    // --- messages ------------------------------------------------------------------------------------------------
    /** Idempotent by `request.id`: a repeat returns the stored message with `duplicate = true`. */
    suspend fun sendMessage(request: SendMessageRequest): SendMessageResponse

    // --- synchronisation -----------------------------------------------------------------------------------------
    suspend fun sync(after: Long, limit: Int = 200): SyncResponse
    suspend fun ack(request: AckRequest)

    // --- attachments ---------------------------------------------------------------------------------------------
    /**
     * Streams [body] (already encrypted for purpose `message`) to the server. [sha256Hex] of the bytes is sent as
     * `X-Content-SHA256` so the server rejects corrupted uploads (`422 checksum_mismatch`).
     * Use [StreamingRequestBody] for files; the body may be written more than once (redirects/retries).
     */
    suspend fun uploadAttachment(
        body: RequestBody,
        purpose: String = AttachmentPurpose.MESSAGE,
        sha256Hex: String? = null,
    ): UploadedAttachment

    /**
     * Downloads attachment [id] into [target], resuming with a `Range` request when [target] already holds a
     * partial download. Returns the final size of [target]. If the server ignores the range (200) the file is
     * rewritten from scratch. The caller must verify size/SHA-256 afterwards; nothing here trusts the server.
     * Use a temporary name for [target] (e.g. `x.part`) and rename after verification.
     */
    suspend fun downloadAttachment(id: String, target: File, onProgress: ((bytesSoFar: Long) -> Unit)? = null): Long
}
