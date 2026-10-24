package se.familychat.core.client

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import se.familychat.core.crypto.DeviceAddress
import se.familychat.core.crypto.InvalidBundleException
import se.familychat.core.crypto.Recipient
import se.familychat.core.crypto.UntrustedIdentityException
import se.familychat.core.model.Conversation
import se.familychat.core.model.Device
import java.util.Base64

/** Result of re-reading a user's device list: devices seen for the first time, and devices that vanished. */
internal class DeviceRefresh(val added: List<Device>, val removed: List<Device>)

/**
 * A device cannot be used: its identity key (or owner) differs from the pinned one ([unapproved] false), or it is a new
 * device next to verified ones and waits for the user's approval ([unapproved] true). [newKey] is the key in question.
 */
internal class IdentityChangedException(
    val userId: String,
    val deviceId: String,
    val newKey: ByteArray?,
    val unapproved: Boolean = false,
) : Exception("Device $deviceId of $userId is not trusted")

/**
 * Users, their devices, conversations' member lists and the Signal sessions with those devices.
 *
 * Trust (see `PersistentSignalProtocolStore`): the device UUID is the identity of a device; the server's device number
 * is informational. [refreshDevices] is the **only** writer of the device lists. It pins the identity key of every
 * device the first time it is listed, posts the "new device" notices, and flags every later key/owner change. Every
 * other part of the engine goes through it, so there is no path that introduces a device silently.
 */
internal class DeviceDirectory(private val ctx: EngineContext) {
    private val devicesMutex = Mutex()
    private val conversationMutex = Mutex()

    suspend fun refreshUsers() {
        ctx.store.upsertUsers(ctx.api.listUsers())
        ctx.emit(EngineEvent.DirectoryChanged(null))
    }

    // --- devices ------------------------------------------------------------------------------------------------------

    /**
     * Re-reads [userId]'s devices from the server, pins and announces new ones, and stores the list. Sessions with
     * devices that vanished are deleted (so a later device with the same id starts from a fresh bundle, never from a
     * stale ratchet); their pins are kept, so removing and re-adding a device cannot make it look new.
     *
     * What counts as "new": a device UUID with an identity key that has no pin yet. The very first successful fetch for
     * a user is the baseline (nothing to compare with, no notice). Afterwards every new device produces a notice in every
     * conversation with the user (for this account's own devices: in every conversation). If the local user verified a
     * device of the user, or the user is this account itself (a device added to your own account receives a copy of
     * everything you send), the new device is pinned *unapproved*: it receives nothing and may not send until the user
     * accepts it in the safety screen.
     */
    suspend fun refreshDevices(userId: String): DeviceRefresh = devicesMutex.withLock {
        val account = ctx.requireAccount()
        val signal = ctx.signalStore
        val listed = ctx.api.listDevices(userId)
        // Only devices that claim to belong to this user, with usable ids; a repeated id counts once. An identity key that
        // is not a valid key makes the device unusable (no keys).
        val fresh = listed
            .filter { it.userId == userId && DeviceAddress.isSafe(userId, it.id) }
            .distinctBy { it.id }
            .map { d -> if (d.identityKey != null && DeviceAddress.decodeIdentityKey(d.identityKey!!) == null) d.copy(identityKey = null) else d }
        val old = ctx.store.getDevices(userId)
        val baseline = ctx.store.getMeta(FETCHED_PREFIX + userId) == null
        // Next to verified devices, and always on this account itself, a new device waits for the user's approval.
        val protect = !baseline && (userId == account.userId || signal.hasVerifiedDevice(userId))
        val added = ArrayList<Device>()
        for (d in fresh) {
            val key = d.identityKey?.let { DeviceAddress.decodeIdentityKey(it) } ?: continue
            if (d.id == account.deviceId) continue // this device; never pinned, never encrypted for
            val pin = signal.pinnedIdentity(d.id)
            if (pin == null) {
                added += d
                // Notice first, pin second: a crash in between replays into the same (deduplicated) notice.
                if (!baseline) announceNewDevice(userId, d, needsApproval = protect)
                signal.pinDevice(userId, d.id, key, approved = !protect)
            } else if (pin.userId != userId || !pin.identityKey.contentEquals(key)) {
                noteIdentityChange(userId, d.id, key)
            } else if (pendingConflict(d.id) != null) {
                clearConflict(d.id) // the list agrees with the pin again
            }
        }
        ctx.store.replaceDevices(userId, fresh)
        ctx.store.setMeta(FETCHED_PREFIX + userId, "1")
        val freshIds = fresh.mapTo(HashSet()) { it.id }
        val removed = old.filter { it.id !in freshIds }
        for (d in removed) deleteSessionQuietly(userId, d.id)
        if (added.isNotEmpty() || removed.isNotEmpty()) ctx.emit(EngineEvent.DirectoryChanged(userId))
        DeviceRefresh(added, removed)
    }

    /** A user was removed on the server: no devices, no sessions. Pins stay (a device UUID is never reused). */
    suspend fun forgetUser(userId: String) = devicesMutex.withLock {
        ctx.store.replaceDevices(userId, emptyList())
        ctx.crypto.deleteAllSessions(userId)
        ctx.emit(EngineEvent.DirectoryChanged(userId))
    }

    private fun deleteSessionQuietly(userId: String, deviceId: String) {
        if (DeviceAddress.isSafe(userId, deviceId)) ctx.crypto.deleteSession(userId, deviceId)
    }

    /** Drops the session with a device the server says is gone (409 `extra_devices`). */
    fun dropSession(userId: String, deviceId: String) = deleteSessionQuietly(userId, deviceId)

    private suspend fun announceNewDevice(userId: String, d: Device, needsApproval: Boolean) {
        val own = userId == ctx.requireAccount().userId
        for (c in ctx.store.getConversations()) {
            if (!own && c.members.none { it.userId == userId }) continue
            postNotice(c, "sys-newdev-${c.id}-${d.id}", SystemEvent.NewDevice(userId, d.id, d.name, needsApproval))
        }
    }

    /**
     * Remembers the offending key so the UI can offer to accept it, and tells the user once (an event and a notice in
     * every conversation with the user). Used for everything that makes a known device untrustworthy: a changed key or
     * owner in the device list, a prekey message or bundle that presents another key, a send that was refused.
     */
    suspend fun noteIdentityChange(userId: String, deviceId: String, key: ByteArray?) {
        val pin = ctx.signalStore.pinnedIdentity(deviceId)
        if (pin != null && !pin.approved && pin.userId == userId && key != null && pin.identityKey.contentEquals(key)) {
            // Just a device waiting for approval: already listed as pending through its pin.
            ctx.emit(EngineEvent.IdentityChanged(userId, deviceId, key))
            return
        }
        val metaKey = "$IDENTITY_META_PREFIX$deviceId"
        val encoded = key?.let { Base64.getEncoder().encodeToString(it) } ?: ""
        val previous = ctx.store.getMeta(metaKey)
        ctx.store.setMeta(metaKey, encoded)
        if (previous == encoded) return
        ctx.emit(EngineEvent.IdentityChanged(userId, deviceId, key))
        val own = userId == ctx.requireAccount().userId
        for (c in ctx.store.getConversations()) {
            if (!own && c.members.none { it.userId == userId }) continue
            postNotice(c, "sys-idchg-${c.id}-$deviceId-${encoded.hashCode()}", SystemEvent.IdentityChanged(userId, deviceId))
        }
    }

    /** The pending identity key recorded by [noteIdentityChange] for the device, if any (empty array: key unknown). */
    suspend fun pendingConflict(deviceId: String): ByteArray? =
        ctx.store.getMeta("$IDENTITY_META_PREFIX$deviceId")?.let { if (it.isEmpty()) ByteArray(0) else Base64.getDecoder().decode(it) }

    suspend fun clearConflict(deviceId: String) = ctx.store.setMeta("$IDENTITY_META_PREFIX$deviceId", null)

    // --- conversations ------------------------------------------------------------------------------------------------

    /**
     * Stores the server's view of a conversation and posts a notice for every member that appeared or disappeared since
     * the stored version. All server-provided conversation data goes through here (sync events, refresh, create, member
     * changes); the first time a conversation is seen is the baseline and says nothing.
     */
    suspend fun applyConversation(c: Conversation) = conversationMutex.withLock {
        val old = ctx.store.getConversation(c.id)
        ctx.store.upsertConversation(c)
        if (old == null) return@withLock
        val me = ctx.requireAccount().userId
        val before = old.members.mapTo(HashSet()) { it.userId }
        val after = c.members.mapTo(LinkedHashSet()) { it.userId }
        for (u in after) if (u != me && u !in before) {
            postNotice(c, "sys-madd-${c.id}-$u-${ctx.config.newId()}", SystemEvent.MemberAdded(u))
        }
        for (u in before) if (u != me && u !in after) {
            postNotice(c, "sys-mrem-${c.id}-$u-${ctx.config.newId()}", SystemEvent.MemberRemoved(u))
        }
    }

    suspend fun postNotice(c: Conversation, id: String, event: SystemEvent) {
        if (ctx.store.getMessage(id) != null) return
        ctx.store.upsertMessage(
            StoredMessage(
                id = id, conversationId = c.id, senderUserId = "", senderDeviceId = null, outgoing = false,
                status = MessageStatus.RECEIVED, seq = c.lastMessageSeq ?: 0, clientTs = ctx.now(), serverTs = null,
                text = null, replyTo = null, systemEvent = event,
            ),
        )
        ctx.emit(EngineEvent.MessageReceived(c.id, id, fromSelfOtherDevice = false))
    }

    // --- sending ------------------------------------------------------------------------------------------------------

    /** Devices to encrypt for in [conversation]: all active devices with keys of all members, minus this device. */
    suspend fun recipientsFor(conversation: Conversation): List<Recipient> {
        val account = ctx.requireAccount()
        val out = ArrayList<Recipient>()
        for (member in conversation.members) {
            var devices = ctx.store.getDevices(member.userId)
            if (devices.isEmpty()) {
                refreshDevices(member.userId)
                devices = ctx.store.getDevices(member.userId)
            }
            for (d in devices) {
                if (d.id == account.deviceId || d.identityKey == null) continue
                out += Recipient(member.userId, d.id)
            }
        }
        return out
    }

    /**
     * Makes sure a session exists with every device in [recipients], fetching bundles for the missing ones.
     * Returns the recipients that could be prepared; devices the server no longer lists keys for are left out (the
     * server will then say `mismatched_devices` if it disagrees).
     *
     * Every recipient is checked against the pin first, even if a session exists: the key the device list shows must be
     * the pinned one, the pin must belong to this user and must be approved, and no identity change may be pending.
     *
     * @throws IdentityChangedException a device is not trusted (changed key or owner, or awaiting approval)
     */
    suspend fun ensureSessions(recipients: List<Recipient>): List<Recipient> {
        val usable = ArrayList<Recipient>(recipients.size)
        for (r in recipients) {
            if (!vet(r)) continue
            if (ctx.crypto.hasSession(r.userId, r.deviceId)) {
                usable += r
                continue
            }
            val response = ctx.api.getKeys(r.userId, r.deviceId)
            if (response.userId != r.userId) continue // not the user we asked about
            val bundle = response.devices.firstOrNull { it.deviceId == r.deviceId }
            if (bundle == null) continue // revoked or keys deleted since we listed it
            try {
                ctx.crypto.processBundle(r.userId, r.deviceId, bundle)
            } catch (e: UntrustedIdentityException) {
                // The bundle presents a key other than the pinned (= listed) one.
                throw IdentityChangedException(r.userId, r.deviceId, e.newIdentityKey)
            } catch (e: InvalidBundleException) {
                // A bundle that fails verification (signatures, wrong device) is never used.
                throw IllegalStateException("Server returned an invalid key bundle for ${r.userId}", e)
            }
            usable += r
        }
        return usable
    }

    /** False if the device is not known (skip it); throws if it is known but not trusted. */
    private suspend fun vet(r: Recipient): Boolean {
        val listed = ctx.store.getDevices(r.userId).firstOrNull { it.id == r.deviceId }?.identityKey
            ?.let { DeviceAddress.decodeIdentityKey(it) } ?: return false
        val pin = ctx.signalStore.pinnedIdentity(r.deviceId) ?: return false
        pendingConflict(r.deviceId)?.let { throw IdentityChangedException(r.userId, r.deviceId, it.takeIf { k -> k.isNotEmpty() }) }
        if (pin.userId != r.userId || !pin.identityKey.contentEquals(listed)) {
            noteIdentityChange(r.userId, r.deviceId, listed)
            throw IdentityChangedException(r.userId, r.deviceId, listed)
        }
        if (!pin.approved) throw IdentityChangedException(r.userId, r.deviceId, pin.identityKey, unapproved = true)
        return true
    }

    companion object {
        /** Per device UUID: the key (base64) that conflicts with the pin; removed when the user decides. */
        const val IDENTITY_META_PREFIX = "identity_conflict:"

        /** Per user id: set after the first successful device fetch (the baseline for "new device" notices). */
        const val FETCHED_PREFIX = "devices_fetched:"
    }
}
