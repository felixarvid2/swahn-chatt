package se.familychat.core.client

import se.familychat.core.crypto.InvalidBundleException
import se.familychat.core.crypto.Recipient
import se.familychat.core.crypto.UntrustedIdentityException
import se.familychat.core.model.Conversation
import se.familychat.core.model.Device

/** Result of re-reading a user's device list. */
internal class DeviceRefresh(val added: List<Device>, val removed: List<Device>, val hadPrevious: Boolean)

/** A bundle could not be used because the device's identity key changed. */
internal class IdentityChangedException(val userId: String, val deviceNumber: Int, val newKey: ByteArray?) :
    Exception("Identity of $userId.$deviceNumber changed")

/** Users, their devices and the Signal sessions with those devices. */
internal class DeviceDirectory(private val ctx: EngineContext) {

    suspend fun refreshUsers() {
        ctx.store.upsertUsers(ctx.api.listUsers())
        ctx.emit(EngineEvent.DirectoryChanged(null))
    }

    /**
     * Re-reads [userId]'s devices from the server and stores them. Sessions with devices that vanished are deleted
     * (so a later device with the same number starts from a fresh bundle, never from a stale ratchet).
     */
    suspend fun refreshDevices(userId: String): DeviceRefresh {
        val fresh = ctx.api.listDevices(userId)
        val old = ctx.store.getDevices(userId)
        val freshIds = fresh.mapTo(HashSet()) { it.id }
        val removed = old.filter { it.id !in freshIds }
        // "Added" means usable: a device only counts once it has uploaded its keys.
        val oldUsable = old.filter { it.identityKey != null }.mapTo(HashSet()) { it.id }
        val added = fresh.filter { it.identityKey != null && it.id !in oldUsable }
        ctx.store.replaceDevices(userId, fresh)
        for (d in removed) ctx.crypto.deleteSession(userId, d.number)
        if (added.isNotEmpty() || removed.isNotEmpty()) ctx.emit(EngineEvent.DirectoryChanged(userId))
        return DeviceRefresh(added, removed, hadPrevious = oldUsable.isNotEmpty())
    }

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
                out += Recipient(member.userId, d.number, d.id)
            }
        }
        return out
    }

    /**
     * Makes sure a session exists with every device in [recipients], fetching bundles for the missing ones.
     * Returns the recipients that could be prepared; devices the server no longer lists keys for are left out (the
     * server will then say `mismatched_devices` if it disagrees).
     *
     * @throws IdentityChangedException a device presented another identity key than the pinned one
     */
    suspend fun ensureSessions(recipients: List<Recipient>): List<Recipient> {
        val usable = ArrayList<Recipient>(recipients.size)
        for (r in recipients) {
            if (ctx.crypto.hasSession(r.userId, r.deviceNumber)) {
                usable += r
                continue
            }
            val response = ctx.api.getKeys(r.userId, r.deviceId)
            val bundle = response.devices.firstOrNull { it.deviceId == r.deviceId }
            if (bundle == null) continue // revoked or keys deleted since we listed it
            try {
                ctx.crypto.processBundle(r.userId, bundle)
            } catch (e: UntrustedIdentityException) {
                throw IdentityChangedException(r.userId, r.deviceNumber, e.newIdentityKey)
            } catch (e: InvalidBundleException) {
                // A bundle that fails signature verification is never used; treat the device as unreachable.
                throw IllegalStateException("Server returned an invalid key bundle for ${r.userId}", e)
            }
            usable += r
        }
        return usable
    }
}
