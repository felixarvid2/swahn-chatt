package se.familychat.core.network

import kotlinx.serialization.json.JsonObject
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*
import se.familychat.core.model.*

/** The Retrofit declaration; wrapped by [RetrofitFamilyChatApi] which maps errors to [ApiException]. */
internal interface RawApi {
    @GET("healthz") suspend fun healthz()

    @POST("auth/invite/redeem") suspend fun redeemInvite(@Body r: RedeemInviteRequest): AuthResponse
    @POST("auth/login") suspend fun login(@Body r: LoginRequest): AuthResponse
    @POST("auth/refresh") suspend fun refresh(@Body r: RefreshRequest): AuthResponse
    @POST("auth/logout") suspend fun logout()

    @POST("admin/invitations") suspend fun createInvitation(@Body r: CreateInvitationRequest): CreatedInvitation
    @GET("admin/invitations") suspend fun listInvitations(): List<Invitation>
    @DELETE("admin/invitations/{id}") suspend fun deleteInvitation(@Path("id") id: String)
    @DELETE("admin/users/{id}") suspend fun deleteUser(@Path("id") id: String)

    @GET("users") suspend fun listUsers(): List<User>
    @GET("users/me") suspend fun me(): User
    @PATCH("users/me") suspend fun updateMe(@Body body: JsonObject): User
    @PUT("users/me/password") suspend fun changePassword(@Body r: ChangePasswordRequest)
    @GET("users/{id}/devices") suspend fun listDevices(@Path("id") userId: String): List<Device>
    @GET("devices/me") suspend fun myDevice(): Device
    @PUT("devices/me/push") suspend fun setPush(@Body r: PushRegistration)
    @DELETE("devices/me/push") suspend fun clearPush()
    @DELETE("devices/{id}") suspend fun deleteDevice(@Path("id") id: String)

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

    @Streaming
    @GET("attachments/{id}")
    suspend fun downloadAttachment(
        @Path("id") id: String,
        @Header("Range") range: String?,
    ): Response<ResponseBody>
}
