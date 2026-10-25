package se.swahnchat.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.swahnchat.app.data.db.AppDatabase
import se.swahnchat.app.data.db.RoomLocalStore
import se.swahnchat.app.data.db.RoomTokenProvider
import se.swahnchat.core.network.LogoutReason
import se.swahnchat.core.network.Tokens
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class TokenProviderTest {
    private lateinit var db: AppDatabase

    @Before fun open() {
        db = RoomTestDb.file(ApplicationProvider.getApplicationContext<Context>(), "tokens.db")
    }

    @After fun close() = db.close()

    @Test fun `tokens are stored replaced and dropped on logout`() {
        val provider = RoomTokenProvider(db)
        assertNull(provider.getTokens())
        provider.saveTokens(Tokens("a1", "r1", 10, 20))
        provider.saveTokens(Tokens("a2", "r2", 11, 21))
        assertEquals(Tokens("a2", "r2", 11, 21), provider.getTokens())
        provider.onLoggedOut(LogoutReason.SESSION_REVOKED)
        assertNull(provider.getTokens())
    }

    @Test fun `tokens are read from threads other than the one that wrote them`() {
        val provider = RoomTokenProvider(db)
        provider.saveTokens(Tokens("a", "r", 1, 2))
        var seen: Tokens? = null
        val t = Thread { seen = provider.getTokens() }
        t.start(); t.join()
        assertEquals("r", seen!!.refreshToken)
    }

    @Test fun `logging out of the store wipes tokens as well`() {
        val provider = RoomTokenProvider(db)
        provider.saveTokens(Tokens("a", "r", 1, 2))
        kotlinx.coroutines.runBlocking { RoomLocalStore(db).clearAll() }
        assertNull(provider.getTokens())
    }
}
