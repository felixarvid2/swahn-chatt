package se.swahnchat.core.network

import okhttp3.Authenticator
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import se.swahnchat.core.model.AuthResponse
import se.swahnchat.core.model.SwahnChatJson
import se.swahnchat.core.model.RefreshRequest
import java.io.IOException

/**
 * Single-flight token refresh. All callers (the OkHttp [Authenticator] on many threads, the WebSocket) go through
 * [refreshIfStale], which serialises on one lock: the first caller refreshes, the others find the already
 * rotated access token and just reuse it, so a burst of expired requests produces exactly one `POST /auth/refresh`
 * (the refresh token rotates and reuse would revoke the session).
 */
internal class TokenRefresher(
    private val baseUrl: HttpUrl,
    private val plainClient: OkHttpClient, // no authenticator/interceptor
    private val tokens: TokenProvider,
    private val onSessionEnded: (LogoutReason) -> Unit,
) {
    private val lock = Any()

    /**
     * Returns an access token that differs from [staleAccessToken] (refreshing if necessary), or null when the
     * session is over (the provider was told via [TokenProvider.onLoggedOut]).
     *
     * @throws IOException when the server could not be reached or answered 429/5xx: the session is still fine.
     */
    fun refreshIfStale(staleAccessToken: String?): String? = synchronized(lock) {
        val current = tokens.getTokens() ?: return null
        if (staleAccessToken != null && current.accessToken != staleAccessToken) return current.accessToken

        val body = SwahnChatJson.encodeToString(RefreshRequest.serializer(), RefreshRequest(current.refreshToken))
            .toRequestBody(JSON)
        val request = Request.Builder().url(baseUrl.newBuilder().addPathSegments("auth/refresh").build())
            .post(body).build()
        plainClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            when {
                response.isSuccessful -> {
                    val auth = try {
                        SwahnChatJson.decodeFromString(AuthResponse.serializer(), text)
                    } catch (e: Exception) {
                        throw IOException("Unreadable refresh response", e)
                    }
                    tokens.saveTokens(
                        Tokens(auth.accessToken, auth.refreshToken, auth.accessExpiresAt, auth.refreshExpiresAt),
                    )
                    auth.accessToken
                }
                response.code == 401 || response.code == 403 -> {
                    endSession()
                    null
                }
                else -> throw IOException("Token refresh failed with HTTP ${response.code}")
            }
        }
    }

    fun endSession(reason: LogoutReason = LogoutReason.SESSION_REVOKED) {
        synchronized(lock) {
            if (tokens.getTokens() == null) return
            tokens.onLoggedOut(reason)
        }
        onSessionEnded(reason)
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

private val ANONYMOUS_SUFFIXES = listOf("/auth/login", "/auth/invite/redeem", "/auth/refresh", "/healthz", "/readyz")

internal fun Request.isAnonymousEndpoint(): Boolean {
    val path = url.encodedPath
    return ANONYMOUS_SUFFIXES.any { path.endsWith(it) }
}

/** Adds the bearer token to every request except the anonymous endpoints. */
internal class AuthInterceptor(private val tokens: TokenProvider) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.isAnonymousEndpoint() || request.header("Authorization") != null) return chain.proceed(request)
        val token = tokens.getTokens()?.accessToken ?: return chain.proceed(request)
        return chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").build())
    }
}

/**
 * Refreshes once on `401 token_expired` and retries the request; gives up (and ends the session) on any other 401
 * from an authenticated endpoint (`unauthorized`, `token_reused`).
 */
internal class RefreshAuthenticator(
    private val refresher: TokenRefresher,
    private val tokens: TokenProvider,
) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        val request = response.request
        if (response.code != 401 || request.isAnonymousEndpoint()) return null
        if (request.header(RETRIED_HEADER) != null) return null // already retried once; surface the 401

        val stale = request.header("Authorization")?.removePrefix("Bearer ")?.trim()
        val code = response.peekErrorCode()

        // Somebody else already refreshed while this request was in flight: just retry with the new token.
        val current = tokens.getTokens()?.accessToken
        if (current != null && stale != null && current != stale) return retry(request, current)

        if (code == "token_expired") {
            val fresh = refresher.refreshIfStale(stale) ?: return null
            return retry(request, fresh)
        }
        refresher.endSession()
        return null
    }

    private fun retry(request: Request, token: String): Request =
        request.newBuilder().header("Authorization", "Bearer $token").header(RETRIED_HEADER, "1").build()

    private companion object {
        const val RETRIED_HEADER = "X-FC-Auth-Retried"
    }
}
