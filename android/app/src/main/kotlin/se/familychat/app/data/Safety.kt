package se.familychat.app.data

import se.familychat.app.data.db.DeviceEntity
import se.familychat.app.data.db.IdentityRow
import se.familychat.app.ui.model.SafetyState
import se.familychat.app.ui.model.SafetyUi

/** Meta key prefix under which `DeviceDirectory` records an unresolved identity change (followed by the device UUID). */
internal const val CONFLICT_PREFIX = "identity_conflict:"

/**
 * The header's verdict on a conversation, from what the database holds (no engine call, so it can be a Flow).
 *
 * - [SafetyState.WARNING]: some device of the other members (or of the own account, which every conversation sends to)
 *   has a changed key or owner ([conflicts]: device UUIDs), or is pinned but not yet approved (a new device next to
 *   verified ones). [SafetyUi.reviewUserId] names the first such person, other members before the own account.
 * - [SafetyState.VERIFIED]: there is at least one other member, each of them has devices, and every one of those devices
 *   is pinned for its owner, approved and marked verified.
 */
fun computeSafety(
    me: String,
    others: Set<String>,
    devices: List<DeviceEntity>,
    identities: List<IdentityRow>,
    conflicts: Set<String>,
): SafetyUi {
    val pin = identities.associateBy { it.deviceId }
    fun pending(d: DeviceEntity) = d.id in conflicts || pin[d.id]?.let { it.userId == d.userId && !it.approved } == true
    val pendingUser = devices.filter { it.userId in others && pending(it) }.minByOrNull { it.userId }?.userId
        ?: devices.firstOrNull { it.userId == me && pending(it) }?.userId
    if (pendingUser != null) return SafetyUi(SafetyState.WARNING, pendingUser)
    if (others.isEmpty()) return SafetyUi()
    val byUser = devices.filter { it.userId in others }.groupBy { it.userId }
    val allVerified = others.all { u ->
        val list = byUser[u].orEmpty()
        list.isNotEmpty() && list.all { d -> pin[d.id]?.let { it.userId == d.userId && it.verified && it.approved } == true }
    }
    return if (allVerified) SafetyUi(SafetyState.VERIFIED) else SafetyUi()
}
