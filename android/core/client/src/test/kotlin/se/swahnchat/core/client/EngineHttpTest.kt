package se.swahnchat.core.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import se.swahnchat.core.crypto.InMemoryProtocolStorage
import se.swahnchat.core.network.ApiException
import se.swahnchat.core.network.InMemoryTokenProvider
import se.swahnchat.core.network.LogoutReason
import se.swahnchat.core.network.Tokens
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The engine on top of the real Retrofit/OkHttp stack against a scripted server: token refresh and session end. */
class EngineHttpTest {
    private lateinit var server: MockWebServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = InMemoryLocalStore()
    private val tokens = InMemoryTokenProvider(Tokens("old", "r1"))
    private lateinit var engine: SwahnChatEngine
    private val refreshes = AtomicInteger()

    private val userJson = """{"id":"u1","username":"anna","display_name":"Anna","is_admin":false,"created_at":1}"""
    private val deviceJson = """{"id":"d1","user_id":"u1","number":1,"name":"P"}"""

    @BeforeTest fun setUp() = runBlocking<Unit> {
        server = MockWebServer().also { it.start() }
        val dir = Files.createTempDirectory("fc-http").toFile()
        engine = SwahnChatEngine(
            store, InMemoryProtocolStorage(), tokens,
            EngineConfig("test", java.io.File(dir, "a"), java.io.File(dir, "t"), allowInsecureLan = true, startWorkers = false),
            scope,
        )
        store.saveAccount(Account(server.url("/").toString(), "u1", "d1", 1, "anna", "Anna", false, "P"))
        assertEquals("anna", engine.restoreSession()!!.username)
    }

    @AfterTest fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    @Test
    fun `expired access token is refreshed once for many concurrent calls and the calls succeed`() = runBlocking<Unit> {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.endsWith("/auth/refresh") -> {
                    refreshes.incrementAndGet(); Thread.sleep(100)
                    MockResponse().setBody(
                        """{"access_token":"new","access_expires_at":9,"refresh_token":"r2","refresh_expires_at":9,"user":$userJson,"device":$deviceJson}""",
                    )
                }
                request.getHeader("Authorization") != "Bearer new" ->
                    MockResponse().setResponseCode(401).setBody("""{"code":"token_expired","message":"expired"}""")
                request.path!!.contains("/devices") -> MockResponse().setBody("[$deviceJson]")
                else -> MockResponse().setResponseCode(404).setBody("""{"code":"not_found","message":"x"}""")
            }
        }
        (1..8).map { async(Dispatchers.IO) { engine.refreshDevices("u1") } }.awaitAll()
        assertEquals(1, refreshes.get())
        assertEquals("new", tokens.getTokens()!!.accessToken)
        assertEquals("r2", tokens.getTokens()!!.refreshToken)
        assertEquals(1, store.getDevices("u1").size)
    }

    @Test
    fun `reused refresh token ends the session, stops the engine and reports it`() = runBlocking<Unit> {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.endsWith("/auth/refresh") ->
                    MockResponse().setResponseCode(401).setBody("""{"code":"token_reused","message":"revoked"}""")
                else -> MockResponse().setResponseCode(401).setBody("""{"code":"token_expired","message":"expired"}""")
            }
        }
        val ended = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            engine.events.first { it is EngineEvent.SessionEnded }
        }
        val e = assertFailsWith<ApiException> { engine.refreshDevices("u1") }
        assertTrue(e.isLoggedOut || e is ApiException.TokenExpired)
        val event = withTimeout(5000) { ended.await() }
        assertEquals(LogoutReason.SESSION_REVOKED, (event as EngineEvent.SessionEnded).reason)
        assertNull(tokens.getTokens())
        assertEquals(LogoutReason.SESSION_REVOKED, tokens.loggedOutReason)
    }

    @Test
    fun `logout works offline and still wipes local data`() = runBlocking<Unit> {
        server.shutdown()
        engine.logout()
        assertNull(store.getAccount())
        assertNull(engine.account.value)
        assertNull(tokens.getTokens())
        assertIs<LogoutReason>(tokens.loggedOutReason)
    }
}
