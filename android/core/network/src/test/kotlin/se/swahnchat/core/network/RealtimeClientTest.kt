package se.swahnchat.core.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import se.swahnchat.core.model.SwahnChatJson
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
import se.swahnchat.core.model.ServerFrame
import se.swahnchat.core.model.TypingState
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

        // Subscribe before the server sends: frames is a hot flow without replay.
        val typingFrame = async(start = CoroutineStart.UNDISPATCHED) {
            rt.frames.filterIsInstance<ServerFrame.Typing>().first()
        }
        serverSockets.poll(2, TimeUnit.SECONDS)!!.send("""{"type":"typing","conversation_id":"c","user_id":"u","state":"start"}""")
        val typing = withTimeout(5000) { typingFrame.await() }
        assertEquals(TypingState.START, typing.state)

        assertTrue(rt.sendTyping("c", TypingState.STOP))
        assertTrue(rt.sendPing())
        val typingOut = SwahnChatJson.parseToJsonElement(received.poll(2, TimeUnit.SECONDS)!!).jsonObject
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

    /**
     * A WebSocket server that speaks the protocol by hand, so that it can send a close code OkHttp's own server API refuses
     * to send (1012 is in the range OkHttp calls reserved). Each connection gets `hello` and then a close frame with [code].
     */
    private class RawCloseServer(private val code: Int) : AutoCloseable {
        private val listener = java.net.ServerSocket(0)
        val connections = java.util.concurrent.atomic.AtomicInteger()
        val port get() = listener.localPort

        init {
            Thread {
                try {
                    while (true) {
                        val socket = listener.accept()
                        connections.incrementAndGet()
                        Thread { serve(socket) }.apply { isDaemon = true }.start()
                    }
                } catch (e: java.io.IOException) {
                    // closed
                }
            }.apply { isDaemon = true }.start()
        }

        private fun serve(socket: java.net.Socket) = socket.use {
            val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            var key = ""
            while (true) {
                val line = input.readLine() ?: return
                if (line.isEmpty()) break
                if (line.startsWith("Sec-WebSocket-Key:", ignoreCase = true)) key = line.substringAfter(':').trim()
            }
            val accept = java.util.Base64.getEncoder().encodeToString(
                java.security.MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
            )
            val out = socket.getOutputStream()
            out.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
            val hello = """{"type":"hello","cursor":5}""".toByteArray()
            out.write(byteArrayOf(0x81.toByte(), hello.size.toByte()) + hello)
            out.write(byteArrayOf(0x88.toByte(), 2, (code shr 8).toByte(), code.toByte()))
            out.flush()
            Thread.sleep(200) // let the client read the close frame before the socket goes away
        }

        override fun close() = listener.close()
    }

    private fun rawClient(port: Int) = RealtimeClient(
        webSocketUrl = okhttp3.HttpUrl.Builder().scheme("http").host("127.0.0.1").port(port).addPathSegments("api/v1/ws").build(),
        okHttp = okhttp3.OkHttpClient(),
        tokens = tokens,
        refresh = { true },
        scope = scope,
        backoff = BackoffPolicy(baseMs = 30, maxMs = 200, random = Random(0)),
    )

    private fun reconnectsAfterClose(code: Int) = runBlocking<Unit> {
        RawCloseServer(code).use { raw ->
            val rt = rawClient(raw.port)
            val hellos = java.util.concurrent.atomic.AtomicInteger()
            val sub = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                rt.frames.filterIsInstance<ServerFrame.Hello>().collect { hellos.incrementAndGet() }
            }
            rt.start()
            // the server closes every connection with [code]; a client that treats it as normal keeps coming back
            withTimeout(10_000) { while (hellos.get() < 3) delay(10) }
            assertTrue(raw.connections.get() >= 3, "reconnected after each close with $code")
            assertTrue(rt.state.value != RealtimeState.LOGGED_OUT && rt.state.value != RealtimeState.STOPPED)
            sub.cancel(); rt.stop()
        }
    }

    @Test
    fun `close code 1001 going away is a normal reconnect`() = reconnectsAfterClose(1001)

    @Test
    fun `close code 1012 service restart is a normal reconnect`() = reconnectsAfterClose(1012)

    @Test
    fun `close code 1013 try again later is a normal reconnect`() = reconnectsAfterClose(1013)

    @Test
    fun `the answer to a close frame is always a code that OkHttp may send`() {
        for (ok in listOf(1000, 1001, 1002, 1003, 1007, 1011, 1012, 1013, 3000, 4401, 4999)) assertEquals(ok, RealtimeClient.replyCode(ok))
        for (reserved in listOf(0, 999, 1004, 1005, 1006, 1015, 2999, 5000)) assertEquals(1000, RealtimeClient.replyCode(reserved), "$reserved")
    }

    @Test
    fun `restart closes do not log the session out or refresh the token`() = runBlocking<Unit> {
        var refreshed = 0
        RawCloseServer(1012).use { raw ->
            val rt = RealtimeClient(
                okhttp3.HttpUrl.Builder().scheme("http").host("127.0.0.1").port(raw.port).addPathSegments("api/v1/ws").build(),
                okhttp3.OkHttpClient(), tokens, { refreshed++; true }, scope, BackoffPolicy(baseMs = 30, maxMs = 200, random = Random(0)),
            )
            rt.start()
            withTimeout(10_000) { while (raw.connections.get() < 2) delay(10) }
            rt.stop()
        }
        assertEquals(0, refreshed)
        assertEquals("t1", tokens.getTokens()!!.accessToken)
    }

    private inline fun <reified T : ServerFrame> firstFrame(rt: RealtimeClient) =
        scope.async(start = CoroutineStart.UNDISPATCHED) { rt.frames.filterIsInstance<T>().first() }
}
