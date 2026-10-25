package se.familychat.core.network

import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.atomic.AtomicLong

/**
 * How far the server's clock is from this phone's, learned from the `Date` header of successful responses. The phone
 * and the Pi can differ by minutes, which would make a fresh call signal look stale (or a stale one fresh) when its
 * `server_ts` is compared with the local time. The header has one-second resolution, which is fine for a 60 s limit.
 */
class ServerClock(private val localNow: () -> Long = System::currentTimeMillis) {
    private val offset = AtomicLong(0)

    /** Server time minus local time in milliseconds; 0 until a response has been seen. */
    val offsetMs: Long get() = offset.get()

    /** The current server time estimated from the local clock. */
    fun now(): Long = localNow() + offsetMs

    /** Records the offset given a response's `Date` header time ([serverMs]). */
    fun record(serverMs: Long) {
        offset.set(serverMs - localNow())
    }

    companion object {
        /** A clock that never learns anything (offset 0), for connections without a real server. */
        val UNSYNCED = ServerClock()
    }
}

/** Feeds [ServerClock] from every successful response that carries a parseable `Date` header. */
internal class ServerClockInterceptor(private val clock: ServerClock) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.isSuccessful) response.headers.getDate("Date")?.let { clock.record(it.time) }
        return response
    }
}
