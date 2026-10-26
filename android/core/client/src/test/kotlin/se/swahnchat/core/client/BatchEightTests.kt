package se.swahnchat.core.client

import kotlinx.coroutines.runBlocking
import se.swahnchat.core.network.ApiException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Batch 8: reset with a code, own devices, deleting the account, the outbox and the admin calls, against the in-process server. */
class BatchEightTests : FeatureTestBase() {
    private fun makeAdmin(c: TestClient) {
        server.users.getValue(c.userId()).isAdmin = true
    }

    // --- reset password with a code ---------------------------------------------------------------------------------------------

    @Test
    fun `a reset code signs in on a new device, ends the old one and works once`() = runBlocking<Unit> {
        val t = trio()
        makeAdmin(t.alice)
        val created = t.alice.engine.issuePasswordReset(t.bob.userId(), "password-alice")
        assertTrue(created.expiresAt > 0)

        val newPhone = client("bobnew")
        val account = newPhone.engine.resetPassword("https://fake.example", created.code, "a-brand-new-password", "New phone")
        assertEquals(t.bob.userId(), account.userId)
        assertNotEquals(t.bob.deviceIdOnServer(), account.deviceId)
        assertEquals("New phone", account.deviceName)

        // the server revoked the old device and knows only the new password
        assertTrue(server.devices.getValue(t.bob.deviceIdOnServer()).revoked)
        assertFailsWith<ApiException.InvalidCredentials> { client("x").engine.login("https://fake.example", "bob", "password-bob") }
        assertEquals(account.deviceId, client("y").engine.login("https://fake.example", "bob", "a-brand-new-password").let { newPhone.deviceIdOnServer() })

        // the code was spent
        val again = assertFailsWith<ApiException.NotFound> { client("z").engine.resetPassword("https://fake.example", created.code,
            "another-new-password", "Phone") }
        assertEquals("invalid_reset_code", again.code)
    }

    @Test
    fun `an unknown code and a weak password are refused and sign nobody in`() = runBlocking<Unit> {
        val t = trio()
        makeAdmin(t.alice)
        val other = client("other")
        assertEquals(
            "invalid_reset_code",
            assertFailsWith<ApiException.NotFound> { other.engine.resetPassword("https://fake.example", "NOPE", "a-brand-new-password") }.code,
        )
        val code = t.alice.engine.issuePasswordReset(t.bob.userId(), "password-alice").code
        assertFailsWith<ApiException.Invalid> { other.engine.resetPassword("https://fake.example", code, "short") }
        assertNull(other.engine.account.value)
        assertFalse(server.devices.getValue(t.bob.deviceIdOnServer()).revoked, "a refused reset must not end the old device")
    }

    @Test
    fun `a new code cancels the older one`() = runBlocking<Unit> {
        val t = trio()
        makeAdmin(t.alice)
        val first = t.alice.engine.issuePasswordReset(t.bob.userId(), "password-alice").code
        val second = t.alice.engine.issuePasswordReset(t.bob.userId(), "password-alice").code
        assertFailsWith<ApiException.NotFound> { client("a").engine.resetPassword("https://fake.example", first, "a-brand-new-password") }
        assertNotNull(client("b").engine.resetPassword("https://fake.example", second, "a-brand-new-password"))
    }

    @Test
    fun `the contact has to approve the new device after a reset, like for any new device`() = runBlocking<Unit> {
        val t = trio()
        makeAdmin(t.alice)
        t.alice.engine.setVerified(t.bob.deviceIdOnServer(), true) // alice compared safety numbers with bob's phone
        val code = t.alice.engine.issuePasswordReset(t.bob.userId(), "password-alice").code
        val newPhone = client("bobnew")
        newPhone.engine.resetPassword("https://fake.example", code, "a-brand-new-password", "New phone")
        t.alice.engine.sync()
        // the new device is not trusted by alice until she decides: nothing is bypassed by the reset
        assertTrue(t.alice.engine.pendingIdentityChanges().any { it.deviceId == newPhone.deviceIdOnServer() })
    }

    // --- own devices ---------------------------------------------------------------------------------------------------------

    @Test
    fun `renaming a device shows in the list and is announced`() = runBlocking<Unit> {
        val t = trio()
        val me = t.bob.engine.myDevices().single()
        val renamed = t.bob.engine.renameDevice(me.id, "  Kitchen tablet ")
        assertEquals("Kitchen tablet", renamed.name)
        assertEquals("Kitchen tablet", t.bob.engine.myDevices().single().name)
        t.alice.engine.sync() // devices_changed reaches the people who share a conversation
        assertEquals("Kitchen tablet", server.devices.getValue(me.id).name)
    }

    @Test
    fun `a device name must have one to 64 characters and the device must be your own`() = runBlocking<Unit> {
        val t = trio()
        val mine = t.bob.deviceIdOnServer()
        assertFailsWith<IllegalArgumentException> { t.bob.engine.renameDevice(mine, "   ") }
        assertFailsWith<IllegalArgumentException> { t.bob.engine.renameDevice(mine, "x".repeat(65)) }
        t.bob.engine.renameDevice(mine, "x".repeat(64))
        assertFailsWith<ApiException.NotFound> { t.bob.engine.renameDevice(t.alice.deviceIdOnServer(), "mine now") }
    }

    @Test
    fun `the test push is queued and its result is read from the device`() = runBlocking<Unit> {
        val t = trio()
        assertNull(t.bob.engine.currentDevice().lastPushStatus)
        server.pushAnswer = 201
        t.bob.engine.sendTestPush()
        val d = t.bob.engine.currentDevice()
        assertEquals(201, d.lastPushStatus)
        assertNotNull(d.lastPushAt)
        server.pushEndpointRegistered = false
        assertEquals("push_not_configured", assertFailsWith<ApiException.Conflict> { t.bob.engine.sendTestPush() }.code)
    }

    // --- delete the account ------------------------------------------------------------------------------------------------------

    @Test
    fun `a wrong password deletes nothing, the right one wipes the phone and removes the member`() = runBlocking<Unit> {
        val t = trio()
        makeAdmin(t.alice)
        val bobId = t.bob.userId()
        assertEquals("invalid_credentials", assertFailsWith<ApiException.Forbidden> { t.bob.engine.deleteAccount("not-my-password") }.code)
        assertNotNull(t.bob.engine.account.value, "nothing is wiped when the server says no")
        assertNotNull(t.bob.store.getAccount())

        t.bob.engine.deleteAccount("password-bob")
        assertNull(t.bob.engine.account.value)
        assertNull(t.bob.store.getAccount())
        assertNull(t.bob.tokens.getTokens())
        assertTrue(server.users.getValue(bobId).removed)
        assertFalse(bobId in server.conversations.getValue(t.group).members.keys)
        // the messages the others have stay with them
        assertTrue(t.alice.store.getConversation(t.ab) != null)
    }

    @Test
    fun `the only administrator cannot delete the account`() = runBlocking<Unit> {
        val t = trio()
        makeAdmin(t.alice)
        assertEquals("last_admin", assertFailsWith<ApiException.Conflict> { t.alice.engine.deleteAccount("password-alice") }.code)
        assertNotNull(t.alice.engine.account.value)
        // after naming a second administrator it works
        t.alice.engine.setUserAdmin(t.bob.userId(), true)
        t.alice.engine.deleteAccount("password-alice")
        assertNull(t.alice.engine.account.value)
    }

    // --- the outbox ----------------------------------------------------------------------------------------------------------

    @Test
    fun `an unsent message stays in the outbox while the server is down, then goes out on retry`() = runBlocking<Unit> {
        val t = trio()
        server.failSends = 1
        val id = t.alice.engine.sendText(t.ab, "waiting")
        val result = t.alice.engine.processOutbox()
        assertTrue(result.offline || result.pending > 0)
        assertEquals(MessageStatus.PENDING, t.alice.store.getMessage(id)!!.status)
        assertNotNull(t.alice.store.getOutboxEntry(id))

        val retry = t.alice.engine.processOutbox()
        assertEquals(1, retry.sent)
        assertEquals(MessageStatus.SENT, t.alice.store.getMessage(id)!!.status)
        t.bob.engine.sync()
        assertEquals(listOf("waiting"), t.bob.texts(t.ab))
    }

    @Test
    fun `a failed message is retried and then delivered, or discarded without anything being sent`() = runBlocking<Unit> {
        val t = trio()
        val failed = t.alice.engine.sendText(t.ab, "will fail")
        t.alice.store.updateMessage(failed) { it.copy(status = MessageStatus.FAILED, failureReason = FailureReason.REJECTED) }
        t.alice.store.removeOutbox(failed)
        t.alice.engine.retryMessage(failed)
        assertEquals(MessageStatus.PENDING, t.alice.store.getMessage(failed)!!.status)
        t.alice.engine.processOutbox()
        t.bob.engine.sync()
        assertEquals(listOf("will fail"), t.bob.texts(t.ab))

        val dropped = t.alice.engine.sendText(t.ab, "never mind")
        t.alice.store.updateMessage(dropped) { it.copy(status = MessageStatus.FAILED, failureReason = FailureReason.REJECTED) }
        t.alice.store.removeOutbox(dropped)
        assertNotNull(t.alice.engine.discardUnsent(dropped))
        assertNull(t.alice.store.getMessage(dropped))
        t.alice.engine.processOutbox(); t.bob.engine.sync()
        assertEquals(listOf("will fail"), t.bob.texts(t.ab), "a discarded message is never sent")
    }

    @Test
    fun `a waiting message can be discarded, a sent one cannot`() = runBlocking<Unit> {
        val t = trio()
        val waiting = t.alice.engine.sendText(t.ab, "changed my mind")
        assertNotNull(t.alice.store.getOutboxEntry(waiting))
        t.alice.engine.discardUnsent(waiting)
        assertNull(t.alice.store.getOutboxEntry(waiting))
        assertNull(t.alice.store.getMessage(waiting))
        t.alice.engine.processOutbox(); t.bob.engine.sync()
        assertTrue(t.bob.texts(t.ab).isEmpty())

        val sent = t.alice.send(t.ab, "delivered")
        assertFailsWith<IllegalArgumentException> { t.alice.engine.discardUnsent(sent) }
        assertNotNull(t.alice.store.getMessage(sent))
    }

    // --- administration --------------------------------------------------------------------------------------------------

    @Test
    fun `reset codes and user removal need the administrators own password`() = runBlocking<Unit> {
        val t = trio()
        makeAdmin(t.alice)
        assertEquals("invalid_credentials", assertFailsWith<ApiException.Forbidden> { t.alice.engine.issuePasswordReset(t.bob.userId(), "wrong") }.code)
        assertFailsWith<ApiException.Forbidden> { t.alice.engine.removeUser(t.carol.userId(), "wrong") }
        assertTrue(t.alice.engine.adminUsers().first { it.username == "carol" }.disabledAt == null, "a wrong password removes nobody")
        assertFailsWith<ApiException.Conflict> { t.alice.engine.issuePasswordReset(t.alice.userId(), "password-alice") }
        assertTrue(t.alice.engine.issuePasswordReset(t.bob.userId(), "password-alice").code.isNotBlank())
    }

    @Test
    fun `the admin calls are refused for everybody else`() = runBlocking<Unit> {
        val t = trio()
        assertFailsWith<ApiException.Forbidden> { t.bob.engine.adminUsers() }
        assertFailsWith<ApiException.Forbidden> { t.bob.engine.adminStats() }
        assertFailsWith<ApiException.Forbidden> { t.bob.engine.issuePasswordReset(t.carol.userId(), "password-bob") }
    }

    @Test
    fun `suspend blocks the sign-in until unsuspended, and the last administrator is protected`() = runBlocking<Unit> {
        val t = trio()
        makeAdmin(t.alice)
        t.alice.engine.suspendUser(t.bob.userId())
        assertTrue(t.alice.engine.adminUsers().first { it.id == t.bob.userId() }.suspendedAt != null)
        assertFailsWith<ApiException.Forbidden> { client("s").engine.login("https://fake.example", "bob", "password-bob") }
        t.alice.engine.unsuspendUser(t.bob.userId())
        assertNotNull(client("u").engine.login("https://fake.example", "bob", "password-bob"))

        assertEquals("last_admin", assertFailsWith<ApiException.Conflict> { t.alice.engine.setUserAdmin(t.alice.userId(), false) }.code)
        assertTrue(t.alice.engine.setUserAdmin(t.bob.userId(), true).isAdmin)
        assertFalse(t.alice.engine.setUserAdmin(t.alice.userId(), false).isAdmin)
    }

    @Test
    fun `an administrator revokes another account's device and removes a user, and both show in the audit log`() = runBlocking<Unit> {
        val t = trio()
        makeAdmin(t.alice)
        val users = t.alice.engine.adminUsers()
        assertEquals(setOf("alice", "bob", "carol"), users.map { it.username }.toSet())
        assertEquals(1, users.first { it.username == "bob" }.devices.size)

        t.alice.engine.revokeUserDevice(t.bob.userId(), t.bob.deviceIdOnServer())
        assertTrue(server.devices.getValue(t.bob.deviceIdOnServer()).revoked)
        t.alice.engine.removeUser(t.carol.userId(), "password-alice")
        assertTrue(t.alice.engine.adminUsers().first { it.username == "carol" }.disabledAt != null)

        val page1 = t.alice.engine.adminAudit(limit = 1)
        assertEquals("user_removed", page1.single().action)
        val page2 = t.alice.engine.adminAudit(beforeId = page1.single().id, limit = 5)
        assertEquals(listOf("device_revoked"), page2.map { it.action })
        assertTrue(t.alice.engine.adminStats().users >= 2)
    }
}
