package se.swahnchat.core.network

import okhttp3.RequestBody
import se.swahnchat.core.model.*
import java.io.File

/**
 * Typed access to every endpoint of docs/API.md. Obtain one from [ApiClient.api].
 *
 * Every function throws [ApiException] for a non-2xx answer and [java.io.IOException] for network failures.
 * Authentication, refresh-on-401 and retry are handled below this interface, so callers never touch tokens.
 */
interface SwahnChatApi {
    // --- health -----------------------------------------------------------------------------------------------
    suspend fun healthz()

    // --- authentication (no bearer token) -----------------------------------------------------------------------
    suspend fun redeemInvite(request: RedeemInviteRequest): AuthResponse
    suspend fun login(request: LoginRequest): AuthResponse

    /** Normally only [ApiClient] calls this; it rotates the refresh token. */
    suspend fun refresh(request: RefreshRequest): AuthResponse

    /** Revokes the session and device on the server. */
    suspend fun logout()

    /** Redeems a password-reset code from an administrator: sets the password, revokes every old device, signs in on a new one. */
    suspend fun resetPassword(request: PasswordResetRequest): AuthResponse

    // --- administration ------------------------------------------------------------------------------------------
    suspend fun createInvitation(request: CreateInvitationRequest = CreateInvitationRequest()): CreatedInvitation
    suspend fun listInvitations(): List<Invitation>
    suspend fun deleteInvitation(id: String)
    suspend fun deleteUser(userId: String, request: AdminConfirmRequest)

    /** `GET /admin/users`: all accounts (removed ones too) with usage and push health. */
    suspend fun adminUsers(): List<AdminUser>
    suspend fun suspendUser(userId: String)
    suspend fun unsuspendUser(userId: String)

    /** `409 last_admin` when it would leave no active administrator. */
    suspend fun setUserAdmin(userId: String, admin: Boolean): AdminUser

    /**
     * The returned code is shown only here; the server keeps just a hash. [request] carries the calling administrator's own
     * password (`403` when wrong, `409` for the administrator's own account).
     */
    suspend fun issuePasswordReset(userId: String, request: AdminConfirmRequest): PasswordResetCreated
    suspend fun revokeUserDevice(userId: String, deviceId: String)
    suspend fun adminStats(): AdminStats

    /** Newest first; pass the smallest `id` of a page as [beforeId] for the next, older page. */
    suspend fun adminAudit(beforeId: Long? = null, limit: Int = 50): List<AuditEvent>

    // --- users and devices ---------------------------------------------------------------------------------------
    suspend fun listUsers(): List<User>
    suspend fun me(): User

    /** Only non-null arguments are sent; set [clearAvatar] to send `avatar_attachment_id: null`. */
    suspend fun updateMe(displayName: String? = null, avatarAttachmentId: String? = null, clearAvatar: Boolean = false): User
    suspend fun changePassword(request: ChangePasswordRequest)

    /** The account's settings kept on the server (whether presence is shared). */
    suspend fun mySettings(): UserSettings

    /** Only non-null arguments are sent. */
    suspend fun updateMySettings(sharePresence: Boolean? = null): UserSettings
    suspend fun listDevices(userId: String): List<Device>

    /** `DELETE /users/me`: deletes the caller's own account (`403` wrong password, `409 last_admin`, `429`). */
    suspend fun deleteAccount(request: DeleteAccountRequest)
    suspend fun myDevice(): Device

    /** Queues one push to this device's registered endpoint (`409 push_not_configured` without one); read the result from [myDevice]. */
    suspend fun testPush()

    /** Renames one of the caller's own devices (1..64 characters). */
    suspend fun renameDevice(deviceId: String, name: String): Device
    suspend fun setPush(request: PushRegistration)
    suspend fun clearPush()
    suspend fun deleteDevice(deviceId: String)

    // --- calls ---------------------------------------------------------------------------------------------------
    /** Fresh TURN REST credentials (or STUN-only urls with null secret) for one call; fetch per call, never cache. */
    suspend fun turnCredentials(): TurnCredentials

    // --- keys ----------------------------------------------------------------------------------------------------
    suspend fun uploadKeys(request: UploadKeysRequest)
    suspend fun keyStatus(): KeyStatus

    /** Consumes one one-time prekey per returned device. [deviceId] limits the answer to one device. */
    suspend fun getKeys(userId: String, deviceId: String? = null): KeyBundleResponse

    // --- conversations -------------------------------------------------------------------------------------------
    suspend fun listConversations(): List<Conversation>
    suspend fun createConversation(request: CreateConversationRequest): Conversation
    suspend fun getConversation(id: String): Conversation

    /**
     * Group admins only (a direct chat: any member for [disappearingSeconds]). Only non-null arguments are sent;
     * [clearAvatar] sends `avatar_attachment_id: null`, [clearDisappearing] sends `disappearing_seconds: null`.
     */
    suspend fun updateConversation(
        id: String,
        name: String? = null,
        avatarAttachmentId: String? = null,
        clearAvatar: Boolean = false,
        disappearingSeconds: Long? = null,
        clearDisappearing: Boolean = false,
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

    /** Starts a resumable upload of [request].size bytes (`POST /attachments/uploads`). */
    suspend fun startUpload(request: StartUploadRequest): UploadSession

    /** Where the upload stands (`GET /attachments/uploads/{id}`); use it to continue after a broken request. */
    suspend fun uploadStatus(uploadId: String): UploadSession

    /**
     * Sends [bytes] (at most 8 MiB) at [offset]. Throws [ApiException.OffsetMismatch] when the server expects another
     * offset. Returns [UploadProgress.Finished] with the attachment (whose id is the upload id) after the last byte.
     */
    suspend fun uploadChunk(uploadId: String, offset: Long, bytes: ByteArray): UploadProgress

    /**
     * Downloads attachment [id] into [target], resuming with a `Range` request when [target] already holds a
     * partial download. Returns the final size of [target]. If the server ignores the range (200) the file is
     * rewritten from scratch. The caller must verify size/SHA-256 afterwards; nothing here trusts the server.
     * Use a temporary name for [target] (e.g. `x.part`) and rename after verification.
     */
    suspend fun downloadAttachment(id: String, target: File, onProgress: ((bytesSoFar: Long) -> Unit)? = null): Long
}
