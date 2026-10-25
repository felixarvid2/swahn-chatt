package se.swahnchat.core.network

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import se.swahnchat.core.model.*
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val USER = """{"id":"u1","username":"anna","display_name":"Anna","is_admin":false,"created_at":1}"""
private const val DEVICE = """{"id":"d1","user_id":"u1","number":1,"name":"P"}"""

private fun auth(access: String, refresh: String) =
    """{"access_token":"$access","access_expires_at":1,"refresh_token":"$refresh","refresh_expires_at":2,"user":$USER,"device":$DEVICE}"""

private fun json(code: Int, body: String) =
    MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

class ApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var tokens: InMemoryTokenProvider

    @BeforeTest fun setUp() {
        server = MockWebServer().also { it.start() }
        tokens = InMemoryTokenProvider(Tokens("old", "r1"))
    }

    @AfterTest fun tearDown() = server.shutdown()

    private fun client() = ApiClient.create(server.url("/").toString(), tokens, allowInsecureLan = true)

    @Test
    fun `sends bearer token and parses json`() = runBlocking<Unit> {
        server.enqueue(json(200, USER))
        val me = client().api.me()
        assertEquals("anna", me.username)
        val r = server.takeRequest()
        assertEquals("/api/v1/users/me", r.path)
        assertEquals("Bearer old", r.getHeader("Authorization"))
    }

    @Test
    fun `login carries no bearer token`() = runBlocking<Unit> {
        server.enqueue(json(200, auth("a", "r")))
        client().api.login(LoginRequest("anna", "pw", "P"))
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `typed errors`() = runBlocking<Unit> {
        val c = client().api
        server.enqueue(json(409, """{"code":"mismatched_devices","message":"m","missing_devices":[{"user_id":"u","device_id":"d"}],"extra_devices":[]}"""))
        val e = assertFailsWith<ApiException.MismatchedDevices> {
            c.sendMessage(SendMessageRequest("m", "c", "message", null, 1))
        }
        assertEquals(DeviceRef("u", "d"), e.missingDevices.single())

        server.enqueue(json(429, """{"code":"rate_limited","message":"slow"}""").setHeader("Retry-After", "12"))
        assertEquals(12L, assertFailsWith<ApiException.RateLimited> { c.keyStatus() }.retryAfterSeconds)

        server.enqueue(json(404, """{"code":"invalid_invite","message":"no"}"""))
        assertEquals("invalid_invite", assertFailsWith<ApiException.NotFound> {
            c.redeemInvite(RedeemInviteRequest("x", "anna", "pw", "A", "P"))
        }.code)

        server.enqueue(MockResponse().setResponseCode(500).setBody("<html>oops</html>"))
        assertIs<ApiException.Server>(assertFailsWith<ApiException> { c.me() })
    }

    @Test
    fun `network failure is an IOException not an ApiException`() = runBlocking<Unit> {
        val c = client().api
        server.shutdown()
        assertFailsWith<IOException> { c.me() }
        Unit
    }

    @Test
    fun `refreshes once on token_expired and retries`() = runBlocking<Unit> {
        server.enqueue(json(401, """{"code":"token_expired","message":"e"}"""))
        server.enqueue(json(200, auth("new", "r2")))
        server.enqueue(json(200, USER))
        assertEquals("anna", client().api.me().username)
        assertEquals("/api/v1/users/me", server.takeRequest().path)
        val refresh = server.takeRequest()
        assertEquals("/api/v1/auth/refresh", refresh.path)
        assertTrue(refresh.body.readUtf8().contains("\"r1\""))
        val retried = server.takeRequest()
        assertEquals("Bearer new", retried.getHeader("Authorization"))
        assertEquals(Tokens("new", "r2", 1, 2), tokens.getTokens())
    }

    @Test
    fun `concurrent expired requests refresh exactly once`() = runBlocking<Unit> {
        val refreshes = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.endsWith("/auth/refresh") -> {
                    refreshes.incrementAndGet()
                    Thread.sleep(150) // widen the race window
                    json(200, auth("new", "r2"))
                }
                request.getHeader("Authorization") == "Bearer new" -> json(200, USER)
                else -> json(401, """{"code":"token_expired","message":"e"}""")
            }
        }
        val api = client().api
        val users = (1..8).map { async(kotlinx.coroutines.Dispatchers.IO) { api.me() } }.awaitAll()
        assertEquals(8, users.size)
        assertEquals(1, refreshes.get())
    }

    @Test
    fun `token_reused ends the session without retry`() = runBlocking<Unit> {
        server.enqueue(json(401, """{"code":"token_expired","message":"e"}"""))
        server.enqueue(json(401, """{"code":"token_reused","message":"revoked"}"""))
        val c = client()
        val event = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { c.sessionEvents.first() }
        val e = assertFailsWith<ApiException.SessionEnded> { c.api.me() }
        assertEquals("session_ended", e.code) // the original 401 was token_expired, but refreshing ended the session
        assertEquals(LogoutReason.SESSION_REVOKED, tokens.loggedOutReason)
        assertNull(tokens.getTokens())
        assertEquals(2, server.requestCount)
        assertEquals(LogoutReason.SESSION_REVOKED, withTimeout(2000) { event.await() })
    }

    @Test
    fun `plain unauthorized ends the session`() = runBlocking<Unit> {
        server.enqueue(json(401, """{"code":"unauthorized","message":"revoked"}"""))
        val e = assertFailsWith<ApiException.SessionEnded> { client().api.me() }
        assertEquals("unauthorized", e.code)
        assertEquals(LogoutReason.SESSION_REVOKED, tokens.loggedOutReason)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `refresh server error keeps the session and fails as IOException`() = runBlocking<Unit> {
        server.enqueue(json(401, """{"code":"token_expired","message":"e"}"""))
        server.enqueue(MockResponse().setResponseCode(503))
        assertFailsWith<IOException> { client().api.me() }
        assertNotNull(tokens.getTokens())
        assertNull(tokens.loggedOutReason)
    }

    @Test
    fun `invalid credentials does not trigger logout logic`() = runBlocking<Unit> {
        server.enqueue(json(401, """{"code":"invalid_credentials","message":"bad"}"""))
        assertFailsWith<ApiException.InvalidCredentials> { client().api.login(LoginRequest("a", "b", "c")) }
        assertEquals(1, server.requestCount)
        assertNotNull(tokens.getTokens())
    }

    @Test
    fun `update calls send explicit null only when asked`() = runBlocking<Unit> {
        val api = client().api
        server.enqueue(json(200, USER)); server.enqueue(json(200, USER))
        api.updateMe(displayName = "Anna B")
        assertEquals("""{"display_name":"Anna B"}""", server.takeRequest().body.readUtf8())
        api.updateMe(clearAvatar = true)
        assertEquals("""{"avatar_attachment_id":null}""", server.takeRequest().body.readUtf8())
    }

    @Test
    fun `get keys sends optional device filter`() = runBlocking<Unit> {
        val api = client().api
        server.enqueue(json(200, """{"user_id":"u","devices":[]}""")); server.enqueue(json(200, """{"user_id":"u","devices":[]}"""))
        api.getKeys("u"); api.getKeys("u", "dev")
        assertEquals("/api/v1/keys/u", server.takeRequest().path)
        assertEquals("/api/v1/keys/u?device_id=dev", server.takeRequest().path)
    }

    @Test
    fun `streaming upload sends checksum and exact bytes`() = runBlocking<Unit> {
        server.enqueue(json(201, """{"id":"a1","purpose":"message","content_type":"application/octet-stream","size":300000,"sha256":"x","created_at":1}"""))
        val data = ByteArray(300_000) { (it * 31).toByte() }
        val f = File.createTempFile("upl", ".bin").apply { writeBytes(data); deleteOnExit() }
        val sha = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        var progress = 0L
        val up = client().api.uploadAttachment(StreamingRequestBody.of(f) { progress = it }, sha256Hex = sha)
        assertEquals("a1", up.id)
        val r = server.takeRequest()
        assertEquals("/api/v1/attachments?purpose=message", r.path)
        assertEquals(sha, r.getHeader("X-Content-SHA256"))
        assertEquals("application/octet-stream", r.getHeader("Content-Type"))
        assertEquals("300000", r.getHeader("Content-Length"))
        assertTrue(r.body.readByteArray().contentEquals(data))
        assertEquals(300_000L, progress)
    }

    @Test
    fun `download resumes with a range request`() = runBlocking<Unit> {
        val data = ByteArray(100_000) { (it % 251).toByte() }
        val f = File.createTempFile("dwn", ".part").apply { deleteOnExit() }
        val api = client().api

        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(data)))
        // simulate an interrupted first download by truncating what we got
        assertEquals(100_000L, api.downloadAttachment("a1", f))
        f.writeBytes(data.copyOf(40_000))
        assertNull(server.takeRequest().getHeader("Range"))

        server.enqueue(
            MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 40000-99999/100000")
                .setBody(Buffer().write(data, 40_000, 60_000)),
        )
        assertEquals(100_000L, api.downloadAttachment("a1", f))
        assertEquals("bytes=40000-", server.takeRequest().getHeader("Range"))
        assertTrue(f.readBytes().contentEquals(data))

        // server ignores the range and sends everything: file is rewritten, not appended
        f.writeBytes(data.copyOf(10))
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(data)))
        assertEquals(100_000L, api.downloadAttachment("a1", f))
        assertTrue(f.readBytes().contentEquals(data))

        // 416 on an already complete file returns its size
        server.takeRequest()
        server.enqueue(json(416, """{"code":"invalid_request","message":"range"}"""))
        assertEquals(100_000L, api.downloadAttachment("a1", f))

        server.enqueue(json(404, """{"code":"not_found","message":"x"}"""))
        f.delete()
        assertFailsWith<ApiException.NotFound> { api.downloadAttachment("gone", f) }
    }

    @Test
    fun `resumable upload endpoints`() = runBlocking<Unit> {
        val c = client().api
        server.enqueue(json(201, """{"upload_id":"up1","offset":0,"size":10,"expires_at":5,"max_chunk_bytes":8388608}"""))
        val session = c.startUpload(StartUploadRequest(10, sha256 = "ab"))
        assertEquals("up1", session.uploadId)
        val start = server.takeRequest()
        assertEquals("POST", start.method)
        assertEquals("/api/v1/attachments/uploads", start.path)
        assertTrue(start.body.readUtf8().contains(""""size":10"""))

        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Upload-Offset", "4").setHeader("Content-Type", "application/json").setBody("""{"offset":4}"""),
        )
        assertEquals(UploadProgress.Partial(4), c.uploadChunk("up1", 0, byteArrayOf(1, 2, 3, 4)))
        val chunk = server.takeRequest()
        assertEquals("PATCH", chunk.method)
        assertEquals("/api/v1/attachments/uploads/up1", chunk.path)
        assertEquals("0", chunk.getHeader("Upload-Offset"))
        assertTrue(chunk.getHeader("Content-Type")!!.startsWith("application/octet-stream"))
        assertEquals(4L, chunk.bodySize)

        server.enqueue(json(201, """{"id":"up1","purpose":"message","content_type":"application/octet-stream","size":10,"sha256":"ab","created_at":1}"""))
        val done = c.uploadChunk("up1", 4, ByteArray(6))
        assertEquals("up1", assertIs<UploadProgress.Finished>(done).attachment.id)

        server.enqueue(json(200, """{"upload_id":"up1","offset":4,"size":10,"expires_at":5,"max_chunk_bytes":8388608}"""))
        assertEquals(4L, c.uploadStatus("up1").offset)
        assertEquals("/api/v1/attachments/uploads/up1", server.takeRequest().also { server.takeRequest() }.path)
    }

    @Test
    fun `an offset mismatch tells where to continue`() = runBlocking<Unit> {
        server.enqueue(json(409, """{"code":"offset_mismatch","message":"x","offset":7}""").setHeader("Upload-Offset", "7"))
        val e = assertFailsWith<ApiException.OffsetMismatch> { client().api.uploadChunk("up1", 0, byteArrayOf(1)) }
        assertEquals(7L, e.serverOffset)
        server.enqueue(json(409, """{"code":"upload_busy","message":"x"}"""))
        assertEquals("upload_busy", assertFailsWith<ApiException.Conflict> { client().api.uploadChunk("up1", 7, byteArrayOf(1)) }.code)
    }

    @Test
    fun `conversation timer is patched and can be cleared with an explicit null`() = runBlocking<Unit> {
        val conv = """{"id":"c","type":"direct","created_by":"u1","members":[],"disappearing_seconds":3600}"""
        server.enqueue(json(200, conv))
        val updated = client().api.updateConversation("c", disappearingSeconds = 3600)
        assertEquals(3600L, updated.disappearingSeconds)
        assertEquals("""{"disappearing_seconds":3600}""", server.takeRequest().body.readUtf8())
        server.enqueue(json(200, """{"id":"c","type":"direct","created_by":"u1","members":[]}"""))
        assertNull(client().api.updateConversation("c", clearDisappearing = true).disappearingSeconds)
        assertEquals("""{"disappearing_seconds":null}""", server.takeRequest().body.readUtf8())
    }

    @Test
    fun `turn credentials are fetched with the bearer token`() = runBlocking<Unit> {
        server.enqueue(json(200, """{"urls":["turn:h:3478?transport=udp"],"username":"1:u1","credential":"c","ttl":3600}"""))
        val turn = client().api.turnCredentials()
        assertEquals(TurnCredentials(listOf("turn:h:3478?transport=udp"), "1:u1", "c", 3600), turn)
        val r = server.takeRequest()
        assertEquals("/api/v1/calls/turn", r.path)
        assertEquals("Bearer old", r.getHeader("Authorization"))
        server.enqueue(json(200, """{"urls":[],"username":null,"credential":null,"ttl":0}"""))
        assertNull(client().api.turnCredentials().username)
    }

    @Test
    fun `server clock learns the offset from the Date header of a successful response`() = runBlocking<Unit> {
        val c = client()
        assertEquals(0L, c.serverClock.offsetMs)
        val skewed = System.currentTimeMillis() + 5 * 60_000
        val date = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
            .format(java.time.Instant.ofEpochMilli(skewed).atZone(java.time.ZoneOffset.UTC))
        server.enqueue(json(200, USER).setHeader("Date", date))
        c.api.me()
        assertTrue(kotlin.math.abs(c.serverClock.offsetMs - 5 * 60_000) < 3_000, "offset ${c.serverClock.offsetMs}")
        assertTrue(kotlin.math.abs(c.serverClock.now() - skewed) < 3_000)
        // an error response (or none) does not move it
        val before = c.serverClock.offsetMs
        server.enqueue(json(500, """{"code":"internal","message":"x"}""").setHeader("Date", "Thu, 01 Jan 1970 00:00:00 GMT"))
        runCatching { c.api.me() }
        assertEquals(before, c.serverClock.offsetMs)
    }
}
