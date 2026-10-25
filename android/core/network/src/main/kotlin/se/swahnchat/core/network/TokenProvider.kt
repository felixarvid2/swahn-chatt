package se.swahnchat.core.network

/** Access/refresh token pair as returned by the auth endpoints. Times are epoch milliseconds. */
data class Tokens(
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAt: Long = 0,
    val refreshExpiresAt: Long = 0,
)

enum class LogoutReason {
    /** Refresh token rejected, reused (`token_reused`), or the session/device was revoked (`unauthorized`). */
    SESSION_REVOKED,

    /** The user logged out on this device. */
    USER,
}

/**
 * Where [ApiClient] keeps the tokens. The app backs it with its encrypted database.
 *
 * All functions are called from OkHttp/IO threads and may block briefly (e.g. a Room `runBlocking` read); they
 * must be thread-safe. [saveTokens] must persist the new pair durably before returning: the refresh token rotates
 * on every refresh and the old one stops working.
 */
interface TokenProvider {
    fun getTokens(): Tokens?

    fun saveTokens(tokens: Tokens)

    /**
     * The session is over (see [LogoutReason]). The implementation should drop the tokens; [getTokens] must then
     * return null. Called at most once per ended session by [ApiClient].
     */
    fun onLoggedOut(reason: LogoutReason)
}

/** Simple thread-safe in-memory [TokenProvider] for tests and tools. */
class InMemoryTokenProvider(initial: Tokens? = null) : TokenProvider {
    @Volatile private var tokens: Tokens? = initial
    @Volatile var loggedOutReason: LogoutReason? = null
        private set

    override fun getTokens(): Tokens? = tokens
    override fun saveTokens(tokens: Tokens) {
        this.tokens = tokens
        loggedOutReason = null
    }

    override fun onLoggedOut(reason: LogoutReason) {
        tokens = null
        loggedOutReason = reason
    }
}
