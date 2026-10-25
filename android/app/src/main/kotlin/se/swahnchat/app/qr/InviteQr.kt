package se.swahnchat.app.qr

import okhttp3.HttpUrl
import se.swahnchat.core.network.BaseUrl
import se.swahnchat.core.network.InvalidBaseUrlException
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.net.URLEncoder

/** An invitation read from a QR code. Untrusted until the user has looked at [host] and chosen to use it. */
data class InviteLink(
    /** The server address as it is typed into the form (no `/api/v1`). */
    val server: String,
    /** The host to show prominently: whoever made the code decides where the account is created. */
    val host: String,
    val code: String,
    /** The host contains international (punycode) characters, which can imitate another name. */
    val suspiciousHost: Boolean,
)

enum class InviteRejection {
    /** Not an invitation code of this app at all. */
    NOT_AN_INVITE,

    /** An invitation of a version this app does not know. */
    UNSUPPORTED_VERSION,
    TOO_LONG,

    /** Parameters other than the ones of version 1, repeated ones, or missing ones. */
    MALFORMED,
    INVALID_SERVER,

    /** `http://` where only `https://` is allowed (every release build). */
    INSECURE_SERVER,
    INVALID_CODE,
}

sealed interface InviteParse {
    data class Ok(val invite: InviteLink) : InviteParse
    data class Rejected(val reason: InviteRejection) : InviteParse
}

/**
 * The invitation QR code: `swahnchat://invite?v=1&server=<url>&code=<code>`, shown to the new person by an administrator.
 *
 * Everything in a scanned code is untrusted input. It may only pre-fill the invitation form (server address and code) and
 * is never submitted by itself; the server must be `https` (plain `http` only where [BaseUrl] allows it for local
 * networks, which is debug builds). Strictness is the point: a code with any other parameter, a second value, a path, user
 * info, a fragment, control characters or more than [MAX_LENGTH] characters is refused as a whole.
 */
object InviteQr {
    const val SCHEME = "swahnchat"
    const val HOST = "invite"
    const val VERSION = "1"
    const val MAX_LENGTH = 512

    /** What the server generates (`token_urlsafe`); the bounds are generous but finite. */
    private val CODE = Regex("^[A-Za-z0-9_-]{4,128}$")
    private const val ENCODED = "UTF-8"

    /** The text for the QR code of invitation [code] on [serverUrl] (the account's server address; `/api/v1` is dropped). */
    fun payload(serverUrl: String, code: String): String {
        val server = displayServer(serverUrl)
        return "$SCHEME://$HOST?v=$VERSION&server=${URLEncoder.encode(server, ENCODED)}&code=${URLEncoder.encode(code, ENCODED)}"
    }

    /** Reads a scanned [text]. [allowInsecureLan] is `BuildConfig.ALLOW_INSECURE_LAN` (debug builds only). */
    @Suppress("ReturnCount") // one early exit per rule, each with its own reason
    fun parse(text: String, allowInsecureLan: Boolean): InviteParse {
        if (text.length > MAX_LENGTH) return reject(InviteRejection.TOO_LONG)
        if (text.any { it.code < 0x20 || it.code > 0x7e }) return reject(InviteRejection.NOT_AN_INVITE)
        val uri = try {
            URI(text)
        } catch (e: URISyntaxException) {
            return reject(InviteRejection.NOT_AN_INVITE)
        }
        if (uri.scheme != SCHEME || uri.host != HOST || uri.rawUserInfo != null || uri.port != -1) return reject(InviteRejection.NOT_AN_INVITE)
        if (!uri.rawPath.isNullOrEmpty() || uri.rawFragment != null) return reject(InviteRejection.MALFORMED)
        val params = queryParams(uri.rawQuery) ?: return reject(InviteRejection.MALFORMED)
        if (params["v"] != VERSION) {
            return reject(if (params.containsKey("v")) InviteRejection.UNSUPPORTED_VERSION else InviteRejection.MALFORMED)
        }
        if (params.keys != setOf("v", "server", "code")) return reject(InviteRejection.MALFORMED)
        val code = params.getValue("code")
        if (!CODE.matches(code)) return reject(InviteRejection.INVALID_CODE)
        val url = when (val r = checkServer(params.getValue("server"), allowInsecureLan)) {
            is ServerCheck.Bad -> return reject(r.reason)
            is ServerCheck.Good -> r.url
        }
        return InviteParse.Ok(InviteLink(displayServer(url.toString()), url.host, code, suspiciousHost(url.host)))
    }

    private sealed interface ServerCheck {
        class Good(val url: HttpUrl) : ServerCheck
        class Bad(val reason: InviteRejection) : ServerCheck
    }

    private fun checkServer(server: String, allowInsecureLan: Boolean): ServerCheck {
        // A full address with a scheme is required: BaseUrl would turn "javascript:x" or "host" into https://... silently.
        val http = server.startsWith("http://", ignoreCase = true)
        if (!http && !server.startsWith("https://", ignoreCase = true)) return ServerCheck.Bad(InviteRejection.INVALID_SERVER)
        return try {
            ServerCheck.Good(BaseUrl.normalize(server, allowInsecureLan))
        } catch (e: InvalidBaseUrlException) {
            // plain http that would have been fine over https is "insecure", anything else is just not a server address
            val fine = http && runCatching { BaseUrl.normalize("https" + server.substring(4)) }.isSuccess
            ServerCheck.Bad(if (fine) InviteRejection.INSECURE_SERVER else InviteRejection.INVALID_SERVER)
        }
    }

    /** Parameters of a raw query string; null if one is repeated, has no value or cannot be decoded. */
    @Suppress("ReturnCount")
    private fun queryParams(raw: String?): Map<String, String>? {
        if (raw.isNullOrEmpty()) return null
        val out = LinkedHashMap<String, String>()
        for (pair in raw.split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) return null
            val key = pair.substring(0, eq)
            val value = try {
                URLDecoder.decode(pair.substring(eq + 1), ENCODED)
            } catch (e: IllegalArgumentException) {
                return null
            }
            if (out.put(key, value) != null) return null
        }
        return out
    }

    /** The host of a server address as typed into the form, or null if it is not an address (for the line that names the server). */
    fun hostOf(server: String): String? = runCatching { BaseUrl.normalize(server, allowInsecureLan = true).host }.getOrNull()

    /** True if the host of [server] has international characters (punycode), which can imitate another name. */
    fun isSuspiciousServer(server: String): Boolean = hostOf(server)?.let(::suspiciousHost) == true

    private fun reject(reason: InviteRejection) = InviteParse.Rejected(reason)

    private fun suspiciousHost(host: String) = host.startsWith("xn--") || host.contains(".xn--") || host.any { it.code > 127 }

    /** `https://host[:port][/prefix]` for an address like `https://host/prefix/api/v1/`, the way a person would type it. */
    fun displayServer(serverUrl: String): String {
        val url = runCatching { BaseUrl.normalize(serverUrl, allowInsecureLan = true) }.getOrNull() ?: return serverUrl.trim().trimEnd('/')
        val segments = url.pathSegments.filter { it.isNotEmpty() }.dropLast(2) // "api", "v1"
        return buildString {
            append(url.scheme).append("://").append(if (url.host.contains(':')) "[${url.host}]" else url.host)
            if (url.port != HttpUrl.defaultPort(url.scheme)) append(':').append(url.port)
            segments.forEach { append('/').append(it) }
        }
    }
}
