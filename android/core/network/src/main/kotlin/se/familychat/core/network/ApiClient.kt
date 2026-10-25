package se.familychat.core.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import se.familychat.core.model.FamilyChatJson
import java.util.concurrent.TimeUnit

/** What the messaging engine needs from the network layer; [ApiClient] is the real implementation. */
interface ServerConnection {
    val api: FamilyChatApi

    /** The server's clock relative to this phone's; the default never learns an offset. */
    val serverClock: ServerClock get() = ServerClock.UNSYNCED

    /** Emits when the session ended (refresh rejected, token reused, device revoked) and the app must log in. */
    val sessionEvents: SharedFlow<LogoutReason>

    /** Creates the WebSocket client; call [RealtimeConnection.start] to connect. */
    fun realtime(scope: CoroutineScope): RealtimeConnection
}

/**
 * One configured connection to a FamilyChat server: the typed [api], token handling and the [realtime] factory.
 *
 * ```
 * val client = ApiClient.create("https://pi.tailnet.ts.net", tokenProvider)
 * val auth = client.api.login(LoginRequest("anna", "pw", "Pixel 8"))
 * tokenProvider.saveTokens(Tokens(auth.accessToken, auth.refreshToken, ...))
 * ```
 * After [FamilyChatApi.login]/[FamilyChatApi.redeemInvite] the caller must store the returned tokens through its
 * [TokenProvider]; from then on every call is authenticated and refreshed transparently.
 */
class ApiClient private constructor(
    val baseUrl: HttpUrl,
    override val api: FamilyChatApi,
    private val tokens: TokenProvider,
    private val refresher: TokenRefresher,
    private val httpClient: OkHttpClient,
    private val sessionFlow: MutableSharedFlow<LogoutReason>,
    /** The offset between the server's clock and this phone's, learned from response `Date` headers. */
    override val serverClock: ServerClock,
) : ServerConnection {
    override val sessionEvents: SharedFlow<LogoutReason> = sessionFlow.asSharedFlow()

    /** The shared OkHttp client (authenticated); the engine uses it for nothing else, exposed for image loaders. */
    val okHttpClient: OkHttpClient get() = httpClient

    /**
     * Refreshes the token unless it already differs from [staleAccessToken] (single-flight with the authenticator).
     * Returns true if the session continues, false if it ended. Throws [java.io.IOException] if the server could not be reached.
     */
    suspend fun refreshTokens(staleAccessToken: String? = tokens.getTokens()?.accessToken): Boolean =
        withContext(Dispatchers.IO) { refresher.refreshIfStale(staleAccessToken) != null }

    /** Marks the local session as ended after an explicit logout (the server call is [FamilyChatApi.logout]). */
    fun endSession(reason: LogoutReason = LogoutReason.USER) = refresher.endSession(reason)

    override fun realtime(scope: CoroutineScope): RealtimeClient = realtime(scope, BackoffPolicy())

    /** Like [realtime] with a custom [BackoffPolicy] (tests use short delays). */
    fun realtime(scope: CoroutineScope, backoff: BackoffPolicy): RealtimeClient =
        RealtimeClient(
            webSocketUrl = BaseUrl.webSocketUrl(baseUrl),
            okHttp = httpClient.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(30, TimeUnit.SECONDS).build(),
            tokens = tokens,
            refresh = { stale -> refreshTokens(stale) },
            scope = scope,
            backoff = backoff,
        )

    companion object {
        /**
         * @param baseUrl what the user typed; normalised by [BaseUrl.normalize] (throws [InvalidBaseUrlException]).
         * @param allowInsecureLan permit `http://` to localhost / private LAN addresses. Debug builds only.
         * @param configure hook for extra OkHttp settings (logging, test certificates).
         */
        fun create(
            baseUrl: String,
            tokens: TokenProvider,
            allowInsecureLan: Boolean = false,
            configure: (OkHttpClient.Builder) -> Unit = {},
        ): ApiClient {
            val url = BaseUrl.normalize(baseUrl, allowInsecureLan)
            val base = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .also(configure)
                .build()
            val sessionFlow = MutableSharedFlow<LogoutReason>(extraBufferCapacity = 8)
            val serverClock = ServerClock()
            val refresher = TokenRefresher(url, base, tokens) { sessionFlow.tryEmit(it) }
            val http = base.newBuilder()
                .addInterceptor(AuthInterceptor(tokens))
                .addInterceptor(ServerClockInterceptor(serverClock))
                .authenticator(RefreshAuthenticator(refresher, tokens))
                .build()
            val retrofit = Retrofit.Builder()
                .baseUrl(url)
                .client(http)
                .addConverterFactory(FamilyChatJson.asConverterFactory("application/json; charset=UTF-8".toMediaType()))
                .build()
            val api = RetrofitFamilyChatApi(retrofit.create(RawApi::class.java)) { tokens.getTokens() == null }
            return ApiClient(url, api, tokens, refresher, http, sessionFlow, serverClock)
        }
    }
}
