@file:Suppress("MaxLineLength") // JSON fixtures

package se.swahnchat.core.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import se.swahnchat.core.model.*
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun reply(code: Int, body: String = "") =
    MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

/** The calls added in batch 8, against a recorded server: paths, methods, bodies, tokens and error mapping. */
class BatchEightApiTest {
    private lateinit var server: MockWebServer

    @BeforeTest fun setUp() { server = MockWebServer().also { it.start() } }
    @AfterTest fun tearDown() = server.shutdown()

    private fun api() = ApiClient.create(server.url("/").toString(), InMemoryTokenProvider(Tokens("tok", "ref")), allowInsecureLan = true).api

    @Test
    fun `the password reset carries no bearer token and sends the three fields`() = runBlocking<Unit> {
        server.enqueue(
            reply(
                200,
                """{"access_token":"a","access_expires_at":1,"refresh_token":"r","refresh_expires_at":2,
                    "user":{"id":"u","username":"anna","display_name":"A"},"device":{"id":"d","user_id":"u","number":1,"name":"P"}}""",
            ),
        )
        val auth = api().resetPassword(PasswordResetRequest("ABCD-1234", "a-long-new-password", "Pixel"))
        assertEquals("anna", auth.user.username)
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("/api/v1/auth/password/reset", r.path)
        assertNull(r.getHeader("Authorization"), "the code is the credential; no stale token goes along")
        val body = r.body.readUtf8()
        assertTrue("\"code\":\"ABCD-1234\"" in body && "\"new_password\":\"a-long-new-password\"" in body && "\"device_name\":\"Pixel\"" in body)
    }

    @Test
    fun `a bad reset code is a typed not-found`() = runBlocking<Unit> {
        server.enqueue(reply(404, """{"code":"invalid_reset_code","message":"no"}"""))
        assertEquals("invalid_reset_code", assertFailsWith<ApiException.NotFound> { api().resetPassword(PasswordResetRequest("x", "y".repeat(12), "P")) }.code)
        server.enqueue(reply(429, """{"code":"rate_limited","message":"slow"}""").setHeader("Retry-After", "30"))
        assertEquals(30L, assertFailsWith<ApiException.RateLimited> { api().resetPassword(PasswordResetRequest("x", "y".repeat(12), "P")) }.retryAfterSeconds)
    }

    @Test
    fun `deleting the account is a DELETE with the password in the body`() = runBlocking<Unit> {
        server.enqueue(reply(204))
        api().deleteAccount(DeleteAccountRequest("my-password"))
        val r = server.takeRequest()
        assertEquals("DELETE", r.method)
        assertEquals("/api/v1/users/me", r.path)
        assertEquals("Bearer tok", r.getHeader("Authorization"))
        assertEquals("""{"password":"my-password"}""", r.body.readUtf8())

        server.enqueue(reply(403, """{"code":"invalid_credentials","message":"wrong"}"""))
        assertEquals("invalid_credentials", assertFailsWith<ApiException.Forbidden> { api().deleteAccount(DeleteAccountRequest("no")) }.code)
        server.enqueue(reply(409, """{"code":"last_admin","message":"only admin"}"""))
        assertEquals("last_admin", assertFailsWith<ApiException.Conflict> { api().deleteAccount(DeleteAccountRequest("pw")) }.code)
    }

    @Test
    fun `renaming a device, the push test and the own device's push health`() = runBlocking<Unit> {
        server.enqueue(reply(200, """{"id":"d1","user_id":"u","number":1,"name":"Kitchen","created_at":1,"last_seen_at":2}"""))
        assertEquals("Kitchen", api().renameDevice("d1", "Kitchen").name)
        val rename = server.takeRequest()
        assertEquals("PATCH", rename.method)
        assertEquals("/api/v1/devices/d1", rename.path)
        assertEquals("""{"name":"Kitchen"}""", rename.body.readUtf8())

        server.enqueue(reply(202, """{"status":"queued"}"""))
        api().testPush()
        assertEquals("/api/v1/devices/me/push/test", server.takeRequest().path)

        server.enqueue(reply(409, """{"code":"push_not_configured","message":"none"}"""))
        assertEquals("push_not_configured", assertFailsWith<ApiException.Conflict> { api().testPush() }.code)

        server.enqueue(reply(200, """{"id":"d1","user_id":"u","number":1,"name":"P","last_push_at":99,"last_push_status":201}"""))
        val d = api().myDevice()
        assertEquals(99L, d.lastPushAt)
        assertEquals(201, d.lastPushStatus)
    }

    @Test
    fun `the admin calls use the documented paths and parse the answers`() = runBlocking<Unit> {
        val a = api()
        server.enqueue(
            reply(
                200,
                """[{"id":"u1","username":"anna","display_name":"Anna","is_admin":true,"created_at":1,"disabled_at":null,"suspended_at":5,
                    "last_seen_at":7,"active_devices":1,"attachment_bytes":10,
                    "devices":[{"id":"d1","number":1,"name":"P","last_seen_at":7,"push_registered":true,"last_push_at":8,"last_push_status":0,"push_failures":2}]}]""",
            ),
        )
        val u = a.adminUsers().single()
        assertEquals(5L, u.suspendedAt)
        assertEquals(2, u.devices.single().pushFailures)
        assertEquals(0, u.devices.single().lastPushStatus)
        assertEquals("/api/v1/admin/users", server.takeRequest().path)

        server.enqueue(reply(204)); a.suspendUser("u1")
        assertEquals("POST /api/v1/admin/users/u1/suspend", server.takeRequest().let { "${it.method} ${it.path}" })
        server.enqueue(reply(204)); a.unsuspendUser("u1")
        assertEquals("POST /api/v1/admin/users/u1/unsuspend", server.takeRequest().let { "${it.method} ${it.path}" })

        server.enqueue(reply(200,
            """{"id":"u1","username":"anna","display_name":"Anna","is_admin":false,"created_at":1,"disabled_at":null,"last_seen_at":null,"active_devices":0,"attachment_bytes":0}"""))
        a.setUserAdmin("u1", false)
        val patch = server.takeRequest()
        assertEquals("PATCH /api/v1/admin/users/u1", "${patch.method} ${patch.path}")
        assertEquals("""{"is_admin":false}""", patch.body.readUtf8())
        server.enqueue(reply(409, """{"code":"last_admin","message":"x"}"""))
        assertEquals("last_admin", assertFailsWith<ApiException.Conflict> { a.setUserAdmin("u1", false) }.code)
        server.takeRequest()

        server.enqueue(reply(201, """{"code":"RESET-1","expires_at":123}"""))
        val reset = a.issuePasswordReset("u1")
        assertEquals("RESET-1", reset.code)
        assertEquals("POST /api/v1/admin/users/u1/password-reset", server.takeRequest().let { "${it.method} ${it.path}" })

        server.enqueue(reply(204)); a.revokeUserDevice("u1", "d1")
        assertEquals("DELETE /api/v1/admin/users/u1/devices/d1", server.takeRequest().let { "${it.method} ${it.path}" })
    }

    @Test
    fun `stats and audit parse, the audit is paged by before_id`() = runBlocking<Unit> {
        val a = api()
        server.enqueue(
            reply(
                200,
                """{"users":3,"active_devices":4,"conversations":2,"messages":10,"undelivered_envelopes":0,"attachments":1,"db_bytes":4096,
                    "attachments_bytes":5,"disk_free_bytes":1000,"version":"0.5.0","migration":{"head":"h","current":"h"},"uptime_seconds":60,
                    "db_maintenance":{"at":1,"dialect":"sqlite","ok":true,"steps":["optimize"],"vacuum":"not_needed","duration_ms":3,"freelist_ratio":0.1,"error":null}}""",
            ),
        )
        val s = a.adminStats()
        assertEquals("0.5.0", s.version)
        assertEquals("not_needed", s.dbMaintenance?.vacuum)
        assertEquals("/api/v1/admin/stats", server.takeRequest().path)

        server.enqueue(reply(200,
            """[{"id":9,"at":1,"actor_user_id":"u","action":"user_suspended","target_user_id":"v","target_id":null,"ip":"10.0.0.1","details":{"via":"admin"}}]"""))
        val page = a.adminAudit(beforeId = 10, limit = 20)
        assertEquals("user_suspended", page.single().action)
        assertEquals("/api/v1/admin/audit?before_id=10&limit=20", server.takeRequest().path)
        server.enqueue(reply(200, "[]"))
        a.adminAudit()
        assertEquals("/api/v1/admin/audit?limit=50", server.takeRequest().path)
    }

    @Test
    fun `an invitation with several uses keeps its counters`() {
        val inv = SwahnChatJson.decodeFromString(Invitation.serializer(),
            """{"id":"i","note":null,"is_admin":false,"expires_at":1,"created_at":0,"used_at":null,"used_by":null,"revoked_at":null,"max_uses":5,"uses":2,"remaining":3}""")
        assertEquals(3, inv.remaining)
        assertEquals(5, inv.maxUses)
    }
}
