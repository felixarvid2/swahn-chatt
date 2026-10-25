package se.swahnchat.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import se.swahnchat.app.BuildConfig
import se.swahnchat.core.network.ApiClient
import se.swahnchat.core.network.ApiException
import se.swahnchat.core.network.BaseUrl
import se.swahnchat.core.network.InMemoryTokenProvider
import se.swahnchat.core.network.InvalidBaseUrlException
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException

sealed interface ServerCheck {
    /** `/healthz` answered. [encrypted] is false for a plain-http development server. */
    data class Reachable(val normalizedUrl: String, val encrypted: Boolean) : ServerCheck

    /** Empty or malformed address. */
    data object InvalidUrl : ServerCheck

    /** http:// to a host that is not localhost / private network, or any http:// in a release build. */
    data object InsecureNotAllowed : ServerCheck

    data object Unreachable : ServerCheck

    /** The certificate is not trusted or the TLS handshake failed. */
    data object TlsProblem : ServerCheck

    data class UnexpectedAnswer(val status: Int) : ServerCheck
}

/** "Test connection": `GET /healthz` against the address as typed, without signing in. */
@Singleton
class ServerChecker @Inject constructor() {
    suspend fun check(input: String): ServerCheck = withContext(Dispatchers.IO) {
        val url = try {
            BaseUrl.normalize(input, BuildConfig.ALLOW_INSECURE_LAN)
        } catch (e: InvalidBaseUrlException) {
            return@withContext if (input.trim().startsWith("http://", ignoreCase = true)) ServerCheck.InsecureNotAllowed else ServerCheck.InvalidUrl
        }
        try {
            val client = ApiClient.create(input, InMemoryTokenProvider(), BuildConfig.ALLOW_INSECURE_LAN) {
                it.connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
            }
            client.api.healthz()
            ServerCheck.Reachable(url.toString(), encrypted = url.isHttps)
        } catch (e: ApiException) {
            ServerCheck.UnexpectedAnswer(e.httpStatus)
        } catch (e: SSLException) {
            ServerCheck.TlsProblem
        } catch (e: IOException) {
            ServerCheck.Unreachable
        }
    }
}
