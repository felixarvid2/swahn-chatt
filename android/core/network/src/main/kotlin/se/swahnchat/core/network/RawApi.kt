package se.swahnchat.core.network

import kotlinx.serialization.json.JsonObject
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*
import se.swahnchat.core.model.*

/** The Retrofit declaration; wrapped by [RetrofitSwahnChatApi] which maps errors to [ApiException]. */
internal interface RawApi {
    @GET("healthz") suspend fun healthz()

    @POST("auth/invite/redeem") suspend fun redeemInvite(@Body r: RedeemInviteRequest): AuthResponse
    @POST("auth/login") suspend fun login(@Body r: LoginRequest): AuthResponse
    @POST("auth/refresh") suspend fun refresh(@Body r: RefreshRequest): AuthResponse
    @POST("auth/logout") suspend fun logout()
    @POST("auth/password/reset") suspend fun resetPassword(@Body r: PasswordResetRequest): AuthResponse

    @POST("admin/invitations") suspend fun createInvitation(@Body r: CreateInvitationRequest): CreatedInvitation
    @GET("admin/invitations") suspend fun listInvitations(): List<Invitation>
    @DELETE("admin/invitations/{id}") suspend fun deleteInvitation(@Path("id") id: String)
    @DELETE("admin/users/{id}") suspend fun deleteUser(@Path("id") id: String)
    @GET("admin/users") suspend fun adminUsers(): List<AdminUser>
    @POST("admin/users/{id}/suspend") suspend fun suspendUser(@Path("id") id: String)
    @POST("admin/users/{id}/unsuspend") suspend fun unsuspendUser(@Path("id") id: String)
    @PATCH("admin/users/{id}") suspend fun setAdmin(@Path("id") id: String, @Body r: SetAdminRequest): AdminUser
    @POST("admin/users/{id}/password-reset") suspend fun issuePasswordReset(@Path("id") id: String): PasswordResetCreated
    @DELETE("admin/users/{id}/devices/{deviceId}") suspend fun revokeUserDevice(@Path("id") id: String, @Path("deviceId") deviceId: String)
    @GET("admin/stats") suspend fun adminStats(): AdminStats
    @GET("admin/audit") suspend fun adminAudit(@Query("before_id") beforeId: Long?, @Query("limit") limit: Int): List<AuditEvent>

    @GET("users") suspend fun listUsers(): List<User>
    @GET("users/me") suspend fun me(): User
    @PATCH("users/me") suspend fun updateMe(@Body body: JsonObject): User
    @GET("users/me/settings") suspend fun mySettings(): UserSettings
    @PATCH("users/me/settings") suspend fun updateMySettings(@Body body: JsonObject): UserSettings
    @PUT("users/me/password") suspend fun changePassword(@Body r: ChangePasswordRequest)
    @GET("users/{id}/devices") suspend fun listDevices(@Path("id") userId: String): List<Device>
    @HTTP(method = "DELETE", path = "users/me", hasBody = true) suspend fun deleteAccount(@Body r: DeleteAccountRequest)
    @GET("devices/me") suspend fun myDevice(): Device
    @POST("devices/me/push/test") suspend fun testPush(): PushTestQueued
    @PATCH("devices/{id}") suspend fun renameDevice(@Path("id") id: String, @Body r: DeviceRenameRequest): Device
    @PUT("devices/me/push") suspend fun setPush(@Body r: PushRegistration)
    @DELETE("devices/me/push") suspend fun clearPush()
    @DELETE("devices/{id}") suspend fun deleteDevice(@Path("id") id: String)

    @GET("calls/turn") suspend fun turnCredentials(): TurnCredentials

    @PUT("keys") suspend fun uploadKeys(@Body r: UploadKeysRequest)
    @GET("keys/status") suspend fun keyStatus(): KeyStatus
    @GET("keys/{userId}") suspend fun getKeys(
        @Path("userId") userId: String,
        @Query("device_id") deviceId: String?,
    ): KeyBundleResponse

    @GET("conversations") suspend fun listConversations(): List<Conversation>
    @POST("conversations") suspend fun createConversation(@Body r: CreateConversationRequest): Conversation
    @GET("conversations/{id}") suspend fun getConversation(@Path("id") id: String): Conversation
    @PATCH("conversations/{id}") suspend fun updateConversation(@Path("id") id: String, @Body body: JsonObject): Conversation
    @POST("conversations/{id}/members") suspend fun addMembers(@Path("id") id: String, @Body r: AddMembersRequest): Conversation
    @PATCH("conversations/{id}/members/{userId}") suspend fun setMemberRole(
        @Path("id") id: String,
        @Path("userId") userId: String,
        @Body r: SetRoleRequest,
    ): Conversation

    @DELETE("conversations/{id}/members/{userId}") suspend fun removeMember(
        @Path("id") id: String,
        @Path("userId") userId: String,
    )

    @GET("conversations/{id}/messages") suspend fun listMessages(
        @Path("id") id: String,
        @Query("before_seq") beforeSeq: Long?,
        @Query("limit") limit: Int,
    ): List<Message>

    @POST("conversations/{id}/read") suspend fun markRead(@Path("id") id: String, @Body r: MarkReadRequest)

    @POST("messages") suspend fun sendMessage(@Body r: SendMessageRequest): SendMessageResponse

    @GET("sync") suspend fun sync(@Query("after") after: Long, @Query("limit") limit: Int): SyncResponse
    @POST("sync/ack") suspend fun ack(@Body r: AckRequest)

    @POST("attachments") suspend fun uploadAttachment(
        @Query("purpose") purpose: String,
        @Header("X-Content-SHA256") sha256: String?,
        @Body body: RequestBody,
    ): UploadedAttachment

    @POST("attachments/uploads") suspend fun startUpload(@Body r: StartUploadRequest): UploadSession
    @GET("attachments/uploads/{id}") suspend fun uploadStatus(@Path("id") id: String): UploadSession

    @PATCH("attachments/uploads/{id}") suspend fun uploadChunk(
        @Path("id") id: String,
        @Header("Upload-Offset") offset: Long,
        @Body body: RequestBody,
    ): Response<ResponseBody>

    @Streaming
    @GET("attachments/{id}")
    suspend fun downloadAttachment(
        @Path("id") id: String,
        @Header("Range") range: String?,
    ): Response<ResponseBody>
}
