package se.familychat.core.network

import se.familychat.core.model.DeviceRef
import se.familychat.core.model.ErrorBody

/**
 * A non-2xx answer from the FamilyChat server, mapped from its `{"code","message"}` body.
 *
 * Network problems (no connection, timeouts, TLS) are NOT [ApiException]s; they stay [java.io.IOException]s so
 * callers can tell "the server said no" from "could not reach the server".
 */
sealed class ApiException(
    val httpStatus: Int,
    /** Machine code from the body, e.g. `token_expired`, `username_taken`, `checksum_mismatch`. */
    val code: String,
    message: String,
) : Exception("$code: $message") {

    /** 401 `token_expired`: refresh and retry. Handled inside [ApiClient]; only seen if refreshing failed. */
    class TokenExpired(message: String) : ApiException(401, "token_expired", message)

    /**
     * 401 that cannot be fixed by refreshing: `unauthorized`, `token_reused`, or a refresh that was rejected. The
     * session/device is gone; the app must show the login screen. [ApiClient.sessionEvents] emits the same fact.
     */
    class SessionEnded(code: String, message: String) : ApiException(401, code, message) {
        val tokenReused: Boolean get() = code == "token_reused"
    }

    /** 401 `invalid_credentials` from login. */
    class InvalidCredentials(message: String) : ApiException(401, "invalid_credentials", message)

    class Forbidden(code: String, message: String) : ApiException(403, code, message)

    /** 404, also `invalid_invite` for unknown/expired/used invitation codes. */
    class NotFound(code: String, message: String) : ApiException(404, code, message)

    /** 409 other than mismatched devices: `username_taken`, `id_conflict`, `identity_key_mismatch`, ... */
    class Conflict(code: String, message: String) : ApiException(409, code, message)

    /** 409 `offset_mismatch` on a resumable upload chunk: [serverOffset] is where the server wants the next byte (null: unknown). */
    class OffsetMismatch(message: String, val serverOffset: Long?) : ApiException(409, "offset_mismatch", message)

    /**
     * 409 `mismatched_devices` on `POST /messages`: refresh the device lists/keys of the users involved and retry
     * with the same message id.
     */
    class MismatchedDevices(
        message: String,
        val missingDevices: List<DeviceRef>,
        val extraDevices: List<DeviceRef>,
    ) : ApiException(409, "mismatched_devices", message)

    /** 413 `payload_too_large` or `quota_exceeded`. */
    class PayloadTooLarge(code: String, message: String) : ApiException(413, code, message)

    /** 415 */
    class UnsupportedMediaType(message: String) : ApiException(415, "unsupported_media_type", message)

    /** 422 `invalid_request` / `checksum_mismatch`. */
    class Invalid(code: String, message: String) : ApiException(422, code, message)

    /** 429 with [retryAfterSeconds] from the `Retry-After` header (null if absent). */
    class RateLimited(message: String, val retryAfterSeconds: Long?) : ApiException(429, "rate_limited", message)

    /** 5xx */
    class Server(httpStatus: Int, code: String, message: String) : ApiException(httpStatus, code, message)

    /** Anything else (other 4xx, unparsable bodies). */
    class Other(httpStatus: Int, code: String, message: String) : ApiException(httpStatus, code, message)

    /** True when the app has to log in again. */
    val isLoggedOut: Boolean get() = this is SessionEnded

    companion object {
        fun from(status: Int, body: ErrorBody?, retryAfterHeader: String?): ApiException {
            val code = body?.code?.takeIf { it.isNotEmpty() } ?: "http_$status"
            val msg = body?.message ?: ""
            return when (status) {
                401 -> when (code) {
                    "token_expired" -> TokenExpired(msg)
                    "invalid_credentials" -> InvalidCredentials(msg)
                    else -> SessionEnded(code, msg)
                }
                403 -> Forbidden(code, msg)
                404 -> NotFound(code, msg)
                409 ->
                    if (code == "mismatched_devices") {
                        MismatchedDevices(msg, body?.missingDevices.orEmpty(), body?.extraDevices.orEmpty())
                    } else if (code == "offset_mismatch") {
                        OffsetMismatch(msg, body?.offset)
                    } else {
                        Conflict(code, msg)
                    }
                413 -> PayloadTooLarge(code, msg)
                415 -> UnsupportedMediaType(msg)
                422 -> Invalid(code, msg)
                429 -> RateLimited(msg, retryAfterHeader?.trim()?.toLongOrNull())
                in 500..599 -> Server(status, code, msg)
                else -> Other(status, code, msg)
            }
        }
    }
}
