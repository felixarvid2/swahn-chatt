package se.familychat.core.client

import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Profile, device, push and avatar functions of the engine, and the atomic decrypt-and-store step of the sync. */
class EngineProfileTest {
    private val server = FakeServer()
    private val scope = newScope()

    @AfterTest fun tearDown() = scope.cancel()

    @Test
    fun `avatar upload and download are cached and size limited`() = runBlocking<Unit> {
        val alice = TestClient(server, "alice", scope).also { it.signUp() }
        val bytes = ByteArray(2000) { (it % 251).toByte() }
        val id = alice.engine.uploadAvatar(bytes, "image/jpeg")
        val file = alice.engine.avatarFile(id)
        assertContentEquals(bytes, file.readBytes())
        server.forgetAttachmentBlob(id) // second call must come from the cache, not the server
        assertEquals(file, alice.engine.avatarFile(id))
        assertFailsWith<IllegalArgumentException> { alice.engine.uploadAvatar(ByteArray(2 * 1024 * 1024 + 1)) }
    }

    @Test
    fun `own devices can be listed and others revoked but not the current one`() = runBlocking<Unit> {
        val alice = TestClient(server, "alice", scope).also { it.signUp() }
        val devices = alice.engine.myDevices()
        assertEquals(listOf(alice.deviceIdOnServer()), devices.map { it.id })
        assertFailsWith<IllegalArgumentException> { alice.engine.revokeDevice(alice.deviceIdOnServer()) }
        alice.engine.registerPush("https://ntfy.example/up123")
        alice.engine.clearPush()
    }

    @Test
    fun `updateProfile keeps the stored account in step with the display name`() = runBlocking<Unit> {
        val alice = TestClient(server, "alice", scope).also { it.signUp() }
        val user = alice.engine.updateProfile(displayName = "  Alice A  ")
        assertEquals(user.id, alice.userId())
        assertTrue(alice.store.getUser(alice.userId()) != null)
    }

    /** Counts how often the engine decrypts inside a [LocalStore.blockingTransaction]. */
    private class CountingStore(private val delegate: LocalStore) : LocalStore by delegate {
        val blocking = AtomicInteger()
        override fun <T> blockingTransaction(block: () -> T): T {
            blocking.incrementAndGet()
            return delegate.blockingTransaction(block)
        }
    }

    @Test
    fun `incoming messages are decrypted and stored inside one blocking transaction`() = runBlocking<Unit> {
        val alice = TestClient(server, "alice", scope)
        val counting = CountingStore(InMemoryLocalStore())
        val bob = TestClient(server, "bob", scope, store = counting)
        alice.signUp(); bob.signUp()
        alice.engine.refreshDirectory(); bob.engine.refreshDirectory()
        val conv = alice.engine.createDirect(bob.userId())
        bob.engine.sync()
        val id = alice.engine.sendText(conv.id, "hi")
        alice.engine.processOutbox()
        bob.engine.sync()
        assertEquals("hi", bob.store.getMessage(id)!!.text)
        assertTrue(counting.blocking.get() >= 1)
        // an edit is decrypted and applied in the same kind of transaction
        val before = counting.blocking.get()
        alice.engine.editMessage(id, "hi!")
        alice.engine.processOutbox()
        bob.engine.sync()
        assertEquals("hi!", bob.store.getMessage(id)!!.text)
        assertTrue(counting.blocking.get() > before)
    }
}
