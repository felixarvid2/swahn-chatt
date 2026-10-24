package se.familychat.app.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import org.junit.runner.RunWith
import se.familychat.core.model.Content
import se.familychat.core.model.FamilyChatJson
import se.familychat.core.model.LoginRequest
import se.familychat.core.model.OneTimePreKeyDto
import se.familychat.core.model.PqPreKeyDto
import se.familychat.core.model.SignedPreKeyDto
import se.familychat.core.model.UploadKeysRequest
import se.familychat.core.network.ApiClient
import se.familychat.core.network.InMemoryTokenProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * kotlinx.serialization and the Retrofit proxy of `RawApi` on a device. Under R8 (qa build) a missing keep rule shows up
 * here as a SerializationException or an IllegalArgumentException from Retrofit, never at build time.
 */
@RunWith(AndroidJUnit4::class)
class SerializationNetworkInstrumentedTest {
    @Test fun wireNamesAreStableAndNullsAreOmitted() {
        val json = FamilyChatJson.encodeToString(Content.serializer(), Content(text = "hej", conversationId = "c", messageId = "m"))
        assertTrue("\"conversation_id\":\"c\"" in json, json)
        assertTrue("\"message_id\":\"m\"" in json, json)
        assertTrue("\"v\":2" in json, json)
        assertFalse("reply_to" in json, json)
        // unknown keys from a newer server are ignored
        val back = FamilyChatJson.decodeFromString(Content.serializer(), """{"v":2,"type":"text","text":"x","future":1}""")
        assertEquals("x", back.text)
    }

    @Test fun requestBodiesRoundTrip() {
        val req = UploadKeysRequest(
            registrationId = 7, identityKey = "aWQ=", signedPrekey = SignedPreKeyDto(1, "cHVi", "c2ln"),
            pqLastResortPrekey = PqPreKeyDto(2, "cHE=", "c2ln"), oneTimePrekeys = listOf(OneTimePreKeyDto(3, "b3Q=")),
        )
        val json = FamilyChatJson.encodeToString(UploadKeysRequest.serializer(), req)
        assertTrue("\"signed_prekey\"" in json && "\"one_time_prekeys\"" in json, json)
        assertEquals(req, FamilyChatJson.decodeFromString(UploadKeysRequest.serializer(), json))
    }

    @Test fun retrofitProxyCallsTheTypedApiAndParsesTheAnswer() = runBlocking<Unit> {
        val user = """{"id":"u1","username":"anna","display_name":"Anna","is_admin":false,"created_at":1}"""
        val device = """{"id":"d1","user_id":"u1","number":1,"name":"Pixel"}"""
        var seenPath: String? = null
        var seenBody: String? = null
        val canned = Interceptor { chain ->
            val request = chain.request()
            seenPath = request.url.encodedPath
            seenBody = request.body?.let { b -> okio.Buffer().also { b.writeTo(it) }.readUtf8() }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(
                    """{"access_token":"a","access_expires_at":1,"refresh_token":"r","refresh_expires_at":2,"user":$user,"device":$device}"""
                        .toResponseBody("application/json".toMediaType()),
                ).build()
        }
        val client = ApiClient.create("https://familychat.invalid", InMemoryTokenProvider()) { it.addInterceptor(canned) }
        val auth = client.api.login(LoginRequest("anna", "pw", "Pixel"))
        assertEquals("anna", auth.user.username)
        assertEquals("a", auth.accessToken)
        assertEquals("/api/v1/auth/login", seenPath)
        assertTrue("\"username\":\"anna\"" in seenBody.orEmpty(), seenBody)
    }
}
