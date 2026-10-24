package se.familychat.core.network

import se.familychat.core.model.DeviceRef
import se.familychat.core.model.ErrorBody
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ErrorMappingTest {
    @Test
    fun `status and code map to types`() {
        assertIs<ApiException.TokenExpired>(ApiException.from(401, ErrorBody("token_expired", "x"), null))
        val reused = assertIs<ApiException.SessionEnded>(ApiException.from(401, ErrorBody("token_reused", "x"), null))
        assertTrue(reused.tokenReused && reused.isLoggedOut)
        assertIs<ApiException.SessionEnded>(ApiException.from(401, ErrorBody("unauthorized", "x"), null))
        assertIs<ApiException.InvalidCredentials>(ApiException.from(401, ErrorBody("invalid_credentials", "x"), null))
        assertIs<ApiException.NotFound>(ApiException.from(404, ErrorBody("invalid_invite", "x"), null))
        assertIs<ApiException.Conflict>(ApiException.from(409, ErrorBody("username_taken", "x"), null))
        assertIs<ApiException.PayloadTooLarge>(ApiException.from(413, ErrorBody("quota_exceeded", "x"), null))
        assertIs<ApiException.Invalid>(ApiException.from(422, ErrorBody("checksum_mismatch", "x"), null))
        assertIs<ApiException.Server>(ApiException.from(503, null, null))
        assertEquals("http_418", ApiException.from(418, null, null).code)
    }

    @Test
    fun `mismatched devices carries lists`() {
        val e = ApiException.from(
            409,
            ErrorBody("mismatched_devices", "m", listOf(DeviceRef("u1", "d1")), listOf(DeviceRef("u2", "d2"))),
            null,
        )
        e as ApiException.MismatchedDevices
        assertEquals(listOf(DeviceRef("u1", "d1")), e.missingDevices)
        assertEquals(listOf(DeviceRef("u2", "d2")), e.extraDevices)
    }

    @Test
    fun `rate limit parses retry after`() {
        assertEquals(7L, (ApiException.from(429, ErrorBody("rate_limited", "x"), " 7 ") as ApiException.RateLimited).retryAfterSeconds)
        assertNull((ApiException.from(429, null, null) as ApiException.RateLimited).retryAfterSeconds)
        assertNull((ApiException.from(429, null, "Wed, 21 Oct") as ApiException.RateLimited).retryAfterSeconds)
    }
}

class BackoffTest {
    @Test
    fun `ceiling doubles from 1s to 60s and stays there`() {
        val b = BackoffPolicy()
        assertEquals(listOf(1000L, 2000, 4000, 8000, 16000, 32000, 60000, 60000), (0..7).map { b.ceilingMs(it) })
        assertEquals(60000L, b.ceilingMs(500))
    }

    @Test
    fun `jittered delay stays within 75 to 100 percent of the ceiling`() {
        val b = BackoffPolicy(random = Random(1))
        repeat(200) { n ->
            val attempt = n % 10
            val d = b.delayMs(attempt)
            val c = b.ceilingMs(attempt)
            assertTrue(d in (c * 3 / 4)..c, "$d vs $c")
        }
    }
}
