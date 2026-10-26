package se.swahnchat.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.Response as RetrofitResponse
import se.swahnchat.core.model.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** [SwahnChatApi] on top of Retrofit: maps HTTP errors to [ApiException] and adds streaming download. */
internal class RetrofitSwahnChatApi(
    private val raw: RawApi,
    /** True once the session was ended (tokens dropped); a 401 `token_expired` then really means "logged out". */
    private val isSessionEnded: () -> Boolean = { false },
) : SwahnChatApi {

    private suspend inline fun <T> call(block: () -> T): T =
        try {
            block()
        } catch (e: HttpException) {
            throw mapHttpException(e).let { mapped ->
                if (mapped is ApiException.TokenExpired && isSessionEnded()) {
                    ApiException.SessionEnded("session_ended", "The session has ended; please sign in again")
                } else {
                    mapped
                }
            }
        }

    override suspend fun healthz() = call { raw.healthz() }
    override suspend fun redeemInvite(request: RedeemInviteRequest) = call { raw.redeemInvite(request) }
    override suspend fun login(request: LoginRequest) = call { raw.login(request) }
    override suspend fun refresh(request: RefreshRequest) = call { raw.refresh(request) }
    override suspend fun logout() = call { raw.logout() }
    override suspend fun resetPassword(request: PasswordResetRequest) = call { raw.resetPassword(request) }

    override suspend fun createInvitation(request: CreateInvitationRequest) = call { raw.createInvitation(request) }
    override suspend fun listInvitations() = call { raw.listInvitations() }
    override suspend fun deleteInvitation(id: String) = call { raw.deleteInvitation(id) }
    override suspend fun deleteUser(userId: String) = call { raw.deleteUser(userId) }
    override suspend fun adminUsers() = call { raw.adminUsers() }
    override suspend fun suspendUser(userId: String) = call { raw.suspendUser(userId) }
    override suspend fun unsuspendUser(userId: String) = call { raw.unsuspendUser(userId) }
    override suspend fun setUserAdmin(userId: String, admin: Boolean) = call { raw.setAdmin(userId, SetAdminRequest(admin)) }
    override suspend fun issuePasswordReset(userId: String) = call { raw.issuePasswordReset(userId) }
    override suspend fun revokeUserDevice(userId: String, deviceId: String) = call { raw.revokeUserDevice(userId, deviceId) }
    override suspend fun adminStats() = call { raw.adminStats() }
    override suspend fun adminAudit(beforeId: Long?, limit: Int) = call { raw.adminAudit(beforeId, limit) }

    override suspend fun listUsers() = call { raw.listUsers() }
    override suspend fun me() = call { raw.me() }
    override suspend fun updateMe(displayName: String?, avatarAttachmentId: String?, clearAvatar: Boolean): User {
        val body = JsonObject(
            buildMap {
                displayName?.let { put("display_name", JsonPrimitive(it)) }
                if (clearAvatar) put("avatar_attachment_id", JsonNull)
                else avatarAttachmentId?.let { put("avatar_attachment_id", JsonPrimitive(it)) }
            },
        )
        return call { raw.updateMe(body) }
    }

    override suspend fun mySettings() = call { raw.mySettings() }
    override suspend fun updateMySettings(sharePresence: Boolean?): UserSettings {
        val body = JsonObject(buildMap { sharePresence?.let { put("share_presence", JsonPrimitive(it)) } })
        return call { raw.updateMySettings(body) }
    }

    override suspend fun changePassword(request: ChangePasswordRequest) = call { raw.changePassword(request) }
    override suspend fun listDevices(userId: String) = call { raw.listDevices(userId) }
    override suspend fun deleteAccount(request: DeleteAccountRequest) = call { raw.deleteAccount(request) }
    override suspend fun myDevice() = call { raw.myDevice() }
    override suspend fun testPush() { call { raw.testPush() } }
    override suspend fun renameDevice(deviceId: String, name: String) = call { raw.renameDevice(deviceId, DeviceRenameRequest(name)) }
    override suspend fun setPush(request: PushRegistration) = call { raw.setPush(request) }
    override suspend fun clearPush() = call { raw.clearPush() }
    override suspend fun deleteDevice(deviceId: String) = call { raw.deleteDevice(deviceId) }

    override suspend fun turnCredentials() = call { raw.turnCredentials() }

    override suspend fun uploadKeys(request: UploadKeysRequest) = call { raw.uploadKeys(request) }
    override suspend fun keyStatus() = call { raw.keyStatus() }
    override suspend fun getKeys(userId: String, deviceId: String?) = call { raw.getKeys(userId, deviceId) }

    override suspend fun listConversations() = call { raw.listConversations() }
    override suspend fun createConversation(request: CreateConversationRequest) = call { raw.createConversation(request) }
    override suspend fun getConversation(id: String) = call { raw.getConversation(id) }
    override suspend fun updateConversation(
        id: String,
        name: String?,
        avatarAttachmentId: String?,
        clearAvatar: Boolean,
        disappearingSeconds: Long?,
        clearDisappearing: Boolean,
    ): Conversation {
        val body = JsonObject(
            buildMap {
                name?.let { put("name", JsonPrimitive(it)) }
                if (clearAvatar) put("avatar_attachment_id", JsonNull)
                else avatarAttachmentId?.let { put("avatar_attachment_id", JsonPrimitive(it)) }
                if (clearDisappearing) put("disappearing_seconds", JsonNull)
                else disappearingSeconds?.let { put("disappearing_seconds", JsonPrimitive(it)) }
            },
        )
        return call { raw.updateConversation(id, body) }
    }

    override suspend fun addMembers(id: String, request: AddMembersRequest) = call { raw.addMembers(id, request) }
    override suspend fun setMemberRole(id: String, userId: String, request: SetRoleRequest) =
        call { raw.setMemberRole(id, userId, request) }

    override suspend fun removeMember(id: String, userId: String) = call { raw.removeMember(id, userId) }
    override suspend fun listMessages(conversationId: String, beforeSeq: Long?, limit: Int) =
        call { raw.listMessages(conversationId, beforeSeq, limit) }

    override suspend fun markRead(conversationId: String, request: MarkReadRequest) =
        call { raw.markRead(conversationId, request) }

    override suspend fun sendMessage(request: SendMessageRequest) = call { raw.sendMessage(request) }
    override suspend fun sync(after: Long, limit: Int) = call { raw.sync(after, limit) }
    override suspend fun ack(request: AckRequest) = call { raw.ack(request) }

    override suspend fun uploadAttachment(body: RequestBody, purpose: String, sha256Hex: String?) =
        call { raw.uploadAttachment(purpose, sha256Hex, body) }

    override suspend fun startUpload(request: StartUploadRequest) = call { raw.startUpload(request) }
    override suspend fun uploadStatus(uploadId: String) = call { raw.uploadStatus(uploadId) }

    override suspend fun uploadChunk(uploadId: String, offset: Long, bytes: ByteArray): UploadProgress {
        val body = bytes.toRequestBody("application/octet-stream".toMediaType())
        val response = raw.uploadChunk(uploadId, offset, body)
        return withContext(Dispatchers.IO) {
            response.use { r ->
                if (!r.isSuccessful) throw mapHttpException(HttpException(r))
                val text = r.body()?.string().orEmpty()
                when (r.code()) {
                    201 -> UploadProgress.Finished(SwahnChatJson.decodeFromString(UploadedAttachment.serializer(), text))
                    else -> UploadProgress.Partial(
                        r.headers()["Upload-Offset"]?.toLongOrNull()
                            ?: text.takeIf { it.isNotBlank() }?.let { SwahnChatJson.decodeFromString(UploadOffset.serializer(), it).offset }
                            ?: throw IOException("Upload answer without offset"),
                    )
                }
            }
        }
    }

    override suspend fun downloadAttachment(id: String, target: File, onProgress: ((Long) -> Unit)?): Long {
        val existing = if (target.exists()) target.length() else 0L
        val response = raw.downloadAttachment(id, if (existing > 0) "bytes=$existing-" else null)
        return withContext(Dispatchers.IO) {
            val append: Boolean
            when (response.code()) {
                200 -> append = false // full body (no range requested, or the server ignored it)
                206 -> {
                    val start = response.headers()["Content-Range"]?.let { RANGE_START.find(it)?.groupValues?.get(1)?.toLongOrNull() }
                    if (start != existing) {
                        response.body()?.close()
                        throw IOException("Unexpected Content-Range ${response.headers()["Content-Range"]} for offset $existing")
                    }
                    append = true
                }
                // The partial file already is the whole attachment (or longer): let the caller verify it.
                416 -> {
                    response.errorBody()?.close()
                    return@withContext existing
                }
                else -> throw mapHttpException(HttpException(response))
            }
            val body = response.body() ?: throw IOException("Empty download body")
            body.use { b ->
                FileOutputStream(target, append).use { out ->
                    var total = if (append) existing else 0L
                    val buf = ByteArray(64 * 1024)
                    b.byteStream().use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            total += n
                            onProgress?.invoke(total)
                        }
                    }
                }
            }
            target.length()
        }
    }

    private companion object {
        val RANGE_START = Regex("""bytes\s+(\d+)-""")
    }
}

private inline fun <T> RetrofitResponse<ResponseBody>.use(block: (RetrofitResponse<ResponseBody>) -> T): T =
    try {
        block(this)
    } finally {
        body()?.close()
        errorBody()?.close()
    }

internal fun mapHttpException(e: HttpException): ApiException {
    val resp = e.response()
    val text = try {
        resp?.errorBody()?.string()
    } catch (io: IOException) {
        null
    }
    val body = text?.takeIf { it.isNotBlank() }?.let {
        try {
            SwahnChatJson.decodeFromString(ErrorBody.serializer(), it)
        } catch (ex: Exception) {
            null
        }
    }
    return ApiException.from(e.code(), body, resp?.headers()?.get("Retry-After"))
}

/** Reads the `code` of an error response without consuming its body (for the authenticator). */
internal fun Response.peekErrorCode(): String? =
    try {
        SwahnChatJson.decodeFromString(ErrorBody.serializer(), peekBody(16 * 1024).string()).code
    } catch (e: Exception) {
        null
    }
