package se.familychat.core.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import se.familychat.core.model.FamilyChatJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import se.familychat.core.model.ServerFrame
import se.familychat.core.model.TypingState
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RealtimeClientTest {
    private lateinit var server: MockWebServer
    private lateinit var tokens: InMemoryTokenProvider
    private lateinit var scope: CoroutineScope
    private val serverSockets = LinkedBlockingQueue<WebSocket>()
    private val received = LinkedBlockingQueue<String>()

    @BeforeTest fun setUp() {
        server = MockWebServer().also { it.start() }
        tokens = InMemoryTokenProvider(Tokens("t1", "r1"))
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @AfterTest fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun accept() = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            serverSockets.add(webSocket)
            webSocket.send("""{"type":"hello","cursor":5}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            received.add(text)
        }
    })

    private fun client(refresh: suspend (String?) -> Boolean = { true }) = RealtimeClient(
        webSocketUrl = server.url("/api/v1/ws"),
        okHttp = okhttp3.OkHttpClient(),
        tokens = tokens,
        refresh = refresh,
        scope = scope,
        backoff = BackoffPolicy(baseMs = 30, maxMs = 200, random = Random(0)),
    )

    @Test
    fun `connects with bearer header, delivers frames and sends typing and ping`() = runBlocking<Unit> {
        server.enqueue(accept())
        val rt = client()
        val hello = firstFrame<ServerFrame.Hello>(rt)
        rt.start()
        assertEquals(5L, withTimeout(5000) { hello.await() }.cursor)
        assertEquals("Bearer t1", server.takeRequest().getHeader("Authorization"))
        assertEquals(RealtimeState.CONNECTED, rt.state.value)

        serverSockets.poll(2, TimeUnit.SECONDS)!!.send("""{"type":"typing","conversation_id":"c","user_id":"u","state":"start"}""")
        val typing = withTimeout(5000) { rt.frames.filterIsInstance<ServerFrame.Typing>().first() }
        assertEquals(TypingState.START, typing.state)

        assertTrue(rt.sendTyping("c", TypingState.STOP))
        assertTrue(rt.sendPing())
        val typingOut = FamilyChatJson.parseToJsonElement(received.poll(2, TimeUnit.SECONDS)!!).jsonObject
        assertEquals("typing", typingOut["type"]!!.jsonPrimitive.content)
        assertEquals("c", typingOut["conversation_id"]!!.jsonPrimitive.content)
        assertEquals("stop", typingOut["state"]!!.jsonPrimitive.content)
        assertEquals("""{"type":"ping"}""", received.poll(2, TimeUnit.SECONDS))
        rt.stop()
        assertEquals(RealtimeState.STOPPED, rt.state.value)
    }

    @Test
    fun `reconnects after the server drops the socket and hello arrives again`() = runBlocking<Unit> {
        server.enqueue(accept()); server.enqueue(accept())
        val rt = client()
        val hellos = java.util.concurrent.atomic.AtomicInteger()
        val sub = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            rt.frames.filterIsInstance<ServerFrame.Hello>().collect { hellos.incrementAndGet() }
        }
        rt.start()
        withTimeout(5000) { while (hellos.get() < 1) delay(10) }
        serverSockets.poll(2, TimeUnit.SECONDS)!!.close(1001, "restart")
        withTimeout(5000) { while (hellos.get() < 2) delay(10) }
        assertEquals(2, server.requestCount)
        sub.cancel(); rt.stop()
    }

    @Test
    fun `reconnects when the server is down and comes back (failure path with backoff)`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(accept())
        val rt = client()
        val hello = firstFrame<ServerFrame.Hello>(rt)
        rt.start()
        withTimeout(5000) { hello.await() }
        assertEquals(3, server.requestCount)
        rt.stop()
    }

    @Test
    fun `close code 4401 refreshes the token and reconnects with the new one`() = runBlocking<Unit> {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.close(4401, "expired")
            }
        }))
        server.enqueue(accept())
        var staleSeen: String? = null
        val rt = client { stale -> staleSeen = stale; tokens.saveTokens(Tokens("t2", "r2")); true }
        val hello = firstFrame<ServerFrame.Hello>(rt)
        rt.start()
        withTimeout(5000) { hello.await() }
        assertEquals("t1", staleSeen)
        assertEquals("Bearer t1", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer t2", server.takeRequest().getHeader("Authorization"))
        rt.stop()
    }

    @Test
    fun `http 401 on upgrade also refreshes`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"token_expired","message":"e"}"""))
        server.enqueue(accept())
        val rt = client { _ -> tokens.saveTokens(Tokens("t2", "r2")); true }
        val hello = firstFrame<ServerFrame.Hello>(rt)
        rt.start()
        withTimeout(5000) { hello.await() }
        server.takeRequest()
        assertEquals("Bearer t2", server.takeRequest().getHeader("Authorization"))
        rt.stop()
    }

    @Test
    fun `stops for good when the session ended`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(401))
        val rt = client { _ -> tokens.onLoggedOut(LogoutReason.SESSION_REVOKED); false }
        rt.start()
        withTimeout(5000) { rt.state.first { it == RealtimeState.LOGGED_OUT } }
        delay(300)
        assertEquals(1, server.requestCount) // no reconnect attempts
        assertNotNull(rt)
    }

    @Test
    fun `stop closes the connection and prevents reconnects`() = runBlocking<Unit> {
        server.enqueue(accept())
        val rt = client()
        val hello = firstFrame<ServerFrame.Hello>(rt)
        rt.start()
        withTimeout(5000) { hello.await() }
        rt.stop()
        delay(400)
        assertEquals(1, server.requestCount)
        assertEquals(RealtimeState.STOPPED, rt.state.value)
    }

    private inline fun <reified T : ServerFrame> firstFrame(rt: RealtimeClient) =
        scope.async(start = CoroutineStart.UNDISPATCHED) { rt.frames.filterIsInstance<T>().first() }
}
