package se.swahnchat.app.data.db

import se.swahnchat.core.network.LogoutReason
import se.swahnchat.core.network.TokenProvider
import se.swahnchat.core.network.Tokens

/**
 * [TokenProvider] in the encrypted database. OkHttp calls these from its own threads, so the dedicated [TokenDao] is
 * synchronous. `saveTokens` has committed when it returns (the refresh token rotates on every use).
 */
class RoomTokenProvider(db: AppDatabase) : TokenProvider {
    private val dao = db.tokenDao()

    override fun getTokens(): Tokens? =
        dao.get()?.let { Tokens(it.accessToken, it.refreshToken, it.accessExpiresAt, it.refreshExpiresAt) }

    override fun saveTokens(tokens: Tokens) =
        dao.put(TokenEntity(accessToken = tokens.accessToken, refreshToken = tokens.refreshToken, accessExpiresAt = tokens.accessExpiresAt, refreshExpiresAt = tokens.refreshExpiresAt))

    override fun onLoggedOut(reason: LogoutReason) = dao.clear()
}
