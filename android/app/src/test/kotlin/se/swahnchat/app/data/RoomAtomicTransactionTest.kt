package se.swahnchat.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.swahnchat.app.data.db.AppDatabase
import se.swahnchat.app.data.db.RoomLocalStore
import se.swahnchat.app.data.db.RoomProtocolStorage
import se.swahnchat.core.client.MessageStatus
import se.swahnchat.core.client.StoredMessage
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The crash window of the sync: libsignal writes its ratchet through [RoomProtocolStorage] while decrypting; the plaintext
 * is written through [RoomLocalStore] right after. `SyncProcessor` runs both inside `LocalStore.blockingTransaction`
 * (holding the crypto lock first). These tests prove, on a real SQLite database, that
 *  - both writes commit together or roll back together,
 *  - blocking protocol calls and suspending store calls on the transaction's thread join it without deadlock,
 *  - the lock order crypto lock -> transaction cannot deadlock against encryption (which holds the lock and writes).
 */
@RunWith(RobolectricTestRunner::class)
class RoomAtomicTransactionTest {
    private lateinit var db: AppDatabase
    private lateinit var store: RoomLocalStore
    private lateinit var protocol: RoomProtocolStorage

    @Before fun open() {
        db = RoomTestDb.file(ApplicationProvider.getApplicationContext<Context>(), "atomic.db")
        store = RoomLocalStore(db)
        protocol = RoomProtocolStorage(db)
    }

    @After fun close() = db.close()

    private fun msg(id: String, seq: Long) = StoredMessage(
        id = id, conversationId = "c1", senderUserId = "u2", senderDeviceId = "d2", outgoing = false, status = MessageStatus.RECEIVED,
        seq = seq, clientTs = seq, serverTs = seq, text = "t$id", replyTo = null,
    )

    private val ratchetBefore = byteArrayOf(1)
    private val ratchetAfter = byteArrayOf(2)

    /** What SyncProcessor does for one message: advance the ratchet, then store the plaintext. */
    private fun decryptAndStore(id: String, seq: Long, failAfterWrites: Boolean = false) {
        store.blockingTransaction {
            protocol.storeSession("u2/d1", ratchetAfter) // libsignal persists the new ratchet state while decrypting
            runBlocking { store.upsertMessage(msg(id, seq)) }
            if (failAfterWrites) throw IllegalStateException("crash before commit")
        }
    }

    @Test fun `ratchet and plaintext commit together`() {
        protocol.storeSession("u2/d1", ratchetBefore)
        decryptAndStore("m1", 1)
        assertContentEquals(ratchetAfter, protocol.loadSession("u2/d1"))
        assertNotNull(runBlocking { store.getMessage("m1") })
    }

    @Test fun `a failure after both writes rolls back the ratchet and the plaintext`() {
        protocol.storeSession("u2/d1", ratchetBefore)
        assertFailsWith<IllegalStateException> { decryptAndStore("m1", 1, failAfterWrites = true) }
        assertContentEquals(ratchetBefore, protocol.loadSession("u2/d1"), "ratchet must be unchanged so the replay can decrypt again")
        assertNull(runBlocking { store.getMessage("m1") })
    }

    @Test fun `suspending store calls inside the blocking transaction run inline`() {
        store.blockingTransaction {
            runBlocking {
                store.upsertMessage(msg("m1", 1))
                store.updateMessage("m1") { it.copy(text = "edited", edited = true) }
                store.setMeta("k", "v")
                store.transaction { store.upsertMessage(msg("m2", 2)) } // nested suspending transaction
                store.removeConversation("none")
            }
            protocol.storeSession("u2/d1", ratchetAfter)
        }
        assertEquals("edited", runBlocking { store.getMessage("m1") }!!.text)
        assertEquals(2, runBlocking { store.getMessages("c1") }.size)
    }

    @Test fun `a suspending transaction also covers blocking protocol calls made on its thread`() {
        protocol.storeSession("u2/d1", ratchetBefore)
        assertFailsWith<IllegalStateException> {
            runBlocking {
                store.transaction {
                    store.upsertMessage(msg("m1", 1))
                    protocol.storeSession("u2/d1", ratchetAfter)
                    throw IllegalStateException("boom")
                }
            }
        }
        assertContentEquals(ratchetBefore, protocol.loadSession("u2/d1"))
        assertNull(runBlocking { store.getMessage("m1") })
    }

    @Test fun `concurrent decrypt encrypt and store traffic does not deadlock and loses nothing`() {
        val cryptoLock = Any() // stands in for MessageCrypto's lock: held while libsignal reads and writes the protocol storage
        val iterations = 60
        val pool = Executors.newFixedThreadPool(4)
        try {
            val decrypt = pool.submit(Callable {
                for (i in 1..iterations) synchronized(cryptoLock) {
                    store.blockingTransaction {
                        protocol.storeSession("u2/d1", byteArrayOf(i.toByte()))
                        runBlocking { store.upsertMessage(msg("in$i", i.toLong())) }
                    }
                }
            })
            val encrypt = pool.submit(Callable {
                for (i in 1..iterations) synchronized(cryptoLock) {
                    protocol.storeSession("u3/d1", byteArrayOf(i.toByte()))
                    protocol.reserveKeyIds(se.swahnchat.core.crypto.KeyIdKind.PRE_KEY, 1)
                }
            })
            val sends = pool.submit(Callable {
                runBlocking {
                    for (i in 1..iterations) store.transaction {
                        store.upsertMessage(msg("out$i", 1000L + i))
                        store.setMeta("last", i.toString())
                    }
                }
            })
            val reads = pool.submit(Callable {
                runBlocking { for (i in 1..iterations) { store.getMessages("c1", 20); store.getConversations() } }
            })
            listOf(decrypt, encrypt, sends, reads).forEach { it.get(90, TimeUnit.SECONDS) } // a deadlock would time out here
        } finally {
            pool.shutdownNow()
        }
        assertEquals(iterations.toByte().toInt(), protocol.loadSession("u2/d1")!![0].toInt())
        assertEquals(2 * iterations, runBlocking { store.getMessages("c1", 1000) }.size)
        assertEquals(iterations.toString(), runBlocking { store.getMeta("last") })
    }
}
