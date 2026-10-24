package se.familychat.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.familychat.app.data.db.AppDatabase
import se.familychat.app.data.db.IdentityRow
import se.familychat.app.data.db.MetaEntity
import se.familychat.app.data.db.MessageEntity
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The reactive queries the conversation screen's header, read marking and "new messages" button are built on. */
@RunWith(RobolectricTestRunner::class)
class UiQueriesTest {
    private lateinit var db: AppDatabase

    @Before fun open() {
        db = RoomTestDb.file(ApplicationProvider.getApplicationContext<Context>(), "uiqueries.db")
    }

    @After fun close() = db.close()

    private fun msg(id: String, seq: Long?, outgoing: Boolean = false, deleted: Boolean = false, system: String? = null, conversation: String = "c1") = MessageEntity(
        id = id, conversationId = conversation, senderUserId = if (outgoing) "me" else "bob", senderDeviceId = "d", outgoing = outgoing,
        status = "RECEIVED", seq = seq, clientTs = seq ?: 100, serverTs = seq, text = "t", replyTo = null, attachments = "[]", edited = false,
        editedAt = null, deleted = deleted, decryptError = null, failureReason = null, deliveredTo = "", readBy = "", systemType = system,
        systemUserId = null, systemDeviceId = null, systemDeviceName = null, pendingEnvelopeType = null, pendingEnvelopeBody = null,
    )

    @Test fun `identity conflicts are the meta keys with the exact prefix, nothing similar`() = runTest {
        val dao = db.storeDao()
        dao.putMeta(MetaEntity("identity_conflict:dev-1", ""))
        dao.putMeta(MetaEntity("identity_conflict:dev-2", "AAAA"))
        dao.putMeta(MetaEntity("identityXconflict:dev-3", "x")) // '_' must not act as a wildcard
        dao.putMeta(MetaEntity("pending_read:c1", "5"))
        val keys = db.uiDao().observeIdentityConflicts().first()
        assertEquals(setOf("identity_conflict:dev-1", "identity_conflict:dev-2"), keys.toSet())
        assertEquals(setOf("dev-1", "dev-2"), keys.map { it.removePrefix(CONFLICT_PREFIX) }.toSet())
    }

    @Test fun `pinned identities are listed with their flags and without the key`() = runTest {
        db.protocolDao().putRemoteIdentity(se.familychat.app.data.db.SignalRemoteIdentityEntity("dev-1", "bob", byteArrayOf(1, 2), verified = true, approved = true))
        db.protocolDao().putRemoteIdentity(se.familychat.app.data.db.SignalRemoteIdentityEntity("dev-2", "bob", byteArrayOf(3), verified = false, approved = false))
        assertEquals(
            setOf(IdentityRow("dev-1", "bob", true, true), IdentityRow("dev-2", "bob", false, false)),
            db.uiDao().observeIdentities().first().toSet(),
        )
    }

    @Test fun `the latest message and the newest incoming sequence follow the conversation`() = runTest {
        val dao = db.storeDao()
        assertNull(db.uiDao().observeLatest("c1").first())
        assertNull(db.uiDao().observeNewestIncomingSeq("c1").first())
        dao.putMessage(msg("a", 1)); dao.putMessage(msg("b", 2)); dao.putMessage(msg("mine", 3, outgoing = true))
        dao.putMessage(msg("other", 50, conversation = "c2"))
        assertEquals("mine", db.uiDao().observeLatest("c1").first()?.id)
        assertEquals(true, db.uiDao().observeLatest("c1").first()?.outgoing)
        assertEquals(2L, db.uiDao().observeNewestIncomingSeq("c1").first(), "own messages do not move the read mark")
        dao.putMessage(msg("pending", null, outgoing = true))
        assertEquals("pending", db.uiDao().observeLatest("c1").first()?.id, "an unsent message is the newest")
    }

    @Test fun `incoming messages since a sequence number skip own, deleted and system messages`() = runTest {
        val dao = db.storeDao()
        for (m in listOf(msg("a", 1), msg("b", 2), msg("c", 3), msg("d", 4, deleted = true), msg("e", 5, system = "member_added"), msg("f", 6, outgoing = true), msg("g", 7))) dao.putMessage(m)
        assertEquals(1, db.uiDao().observeIncomingAfter("c1", 3).first(), "only g: d is deleted, e is a notice, f is own")
        assertEquals(4, db.uiDao().observeIncomingAfter("c1", 0).first())
        assertEquals(0, db.uiDao().observeIncomingAfter("c1", 7).first())
    }
}
