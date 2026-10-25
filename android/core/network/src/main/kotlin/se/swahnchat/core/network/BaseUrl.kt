package se.swahnchat.core.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class InvalidBaseUrlException(message: String) : IllegalArgumentException(message)

/** Turns what a person types into the server field into the API base URL. */
object BaseUrl {
    private const val API_PATH = "api/v1"

    /**
     * `"https://pi.tailnet.ts.net"`, `"pi.tailnet.ts.net/"` or `"https://host/api/v1"` all become
     * `https://pi.tailnet.ts.net/api/v1/` (always with a trailing slash, as Retrofit requires).
     *
     * - A missing scheme means `https`.
     * - Plain `http` is refused unless [allowInsecureLan] is set (debug builds only) AND the host is a loopback or
     *   private-LAN address: `localhost`, `127.*`, `10.*`, `192.168.*` or `100.64.0.0/10` (CGNAT/Tailscale).
     * - Credentials, query strings and fragments are refused. A path prefix (reverse proxy under `/chat`) is kept.
     *
     * @throws InvalidBaseUrlException
     */
    fun normalize(input: String, allowInsecureLan: Boolean = false): HttpUrl {
        var text = input.trim()
        if (text.isEmpty()) throw InvalidBaseUrlException("Server address is empty")
        if (!text.contains("://")) text = "https://$text"
        val url = text.toHttpUrlOrNull() ?: throw InvalidBaseUrlException("Not a valid server address: $input")
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            throw InvalidBaseUrlException("The server address must not contain a user name or password")
        }
        if (url.query != null || url.fragment != null) {
            throw InvalidBaseUrlException("The server address must not contain a query or fragment")
        }
        if (!url.isHttps && !(allowInsecureLan && isPrivateHost(url.host))) {
            throw InvalidBaseUrlException(
                if (allowInsecureLan) "Plain http is only allowed for localhost and local network addresses"
                else "Only https:// servers are allowed",
            )
        }
        // Drop empty segments and a trailing "api/v1" the user may have pasted, then append it exactly once.
        val segments = url.pathSegments.filter { it.isNotEmpty() }.toMutableList()
        if (segments.size >= 2 && segments[segments.size - 2] == "api" && segments.last() == "v1") {
            segments.removeAt(segments.size - 1); segments.removeAt(segments.size - 1)
        }
        val builder = url.newBuilder().encodedPath("/")
        for (s in segments) builder.addPathSegment(s)
        builder.addPathSegments(API_PATH).addPathSegment("")
        return builder.build()
    }

    /** `ws(s)://.../api/v1/ws` for [base] produced by [normalize]. */
    fun webSocketUrl(base: HttpUrl): HttpUrl = base.newBuilder().addPathSegment("ws").build()

    internal fun isPrivateHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".localhost")) return true
        val parts = host.split('.')
        if (parts.size != 4) return false
        val n = parts.map { it.toIntOrNull() ?: return false }
        if (n.any { it !in 0..255 }) return false
        return n[0] == 127 || n[0] == 10 ||
            (n[0] == 192 && n[1] == 168) ||
            (n[0] == 100 && n[1] in 64..127)
    }
}
