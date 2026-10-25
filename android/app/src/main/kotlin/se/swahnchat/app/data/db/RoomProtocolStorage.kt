package se.swahnchat.app.data.db

import se.swahnchat.core.crypto.KeyIdKind
import se.swahnchat.core.crypto.LocalIdentity
import se.swahnchat.core.crypto.ProtocolStorage
import se.swahnchat.core.crypto.StoredIdentity
import se.swahnchat.core.crypto.StoredKyberPreKey
import java.util.concurrent.Callable

/**
 * [ProtocolStorage] on Room, in the same database as [RoomLocalStore]. All calls are blocking and run on the calling thread.
 * Each call commits on its own (durable when it returns: `PRAGMA synchronous=FULL`) unless the calling thread is inside a
 * transaction (`LocalStore.blockingTransaction`), in which case it is part of it.
 */
class RoomProtocolStorage(private val db: AppDatabase) : ProtocolStorage {
    private val dao = db.protocolDao()

    private fun <T> tx(block: () -> T): T = db.runInTransaction(Callable { block() })

    override fun getLocalIdentity(): LocalIdentity? = dao.identity()?.let { LocalIdentity(it.identityKeyPair, it.registrationId) }
    override fun setLocalIdentity(identity: LocalIdentity) =
        dao.putIdentity(SignalIdentityEntity(identityKeyPair = identity.identityKeyPair, registrationId = identity.registrationId))

    override fun loadPreKey(id: Int): ByteArray? = dao.preKey(id)
    override fun storePreKey(id: Int, record: ByteArray) = dao.putPreKey(SignalPreKeyEntity(id, record))
    override fun removePreKey(id: Int) = dao.removePreKey(id)
    override fun countPreKeys(): Int = dao.countPreKeys()

    override fun loadSignedPreKey(id: Int): ByteArray? = dao.signedPreKey(id)
    override fun loadSignedPreKeys(): List<ByteArray> = dao.signedPreKeys()
    override fun storeSignedPreKey(id: Int, record: ByteArray) = dao.putSignedPreKey(SignalSignedPreKeyEntity(id, record))
    override fun removeSignedPreKey(id: Int) = dao.removeSignedPreKey(id)

    override fun loadKyberPreKey(id: Int): ByteArray? = dao.kyberPreKey(id)
    override fun loadKyberPreKeys(): List<StoredKyberPreKey> =
        dao.kyberPreKeys().map { StoredKyberPreKey(it.id, it.record, it.lastResort) }

    override fun storeKyberPreKey(id: Int, record: ByteArray, lastResort: Boolean) =
        dao.putKyberPreKey(SignalKyberPreKeyEntity(id, record, lastResort))

    override fun removeKyberPreKey(id: Int) = dao.removeKyberPreKey(id)

    override fun markLastResortUsed(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ByteArray): Boolean =
        dao.insertLastResortUse(
            SignalLastResortUsedEntity(kyberPreKeyId, signedPreKeyId, baseKey.joinToString("") { "%02x".format(it) }),
        ) != -1L

    override fun loadSession(name: String): ByteArray? = dao.session(name)
    override fun storeSession(name: String, record: ByteArray) = dao.putSession(SignalSessionEntity(name, record))
    override fun deleteSession(name: String) = dao.deleteSession(name)
    override fun deleteSessionsWithPrefix(prefix: String) = dao.deleteSessionsWithPrefix(prefix)

    override fun getIdentity(deviceId: String): StoredIdentity? = dao.remoteIdentity(deviceId)?.toModel()

    override fun putIdentity(identity: StoredIdentity) = dao.putRemoteIdentity(
        SignalRemoteIdentityEntity(identity.deviceId, identity.userId, identity.identityKey, identity.verified, identity.approved),
    )

    override fun identitiesOf(userId: String): List<StoredIdentity> = dao.remoteIdentitiesOf(userId).map { it.toModel() }

    private fun SignalRemoteIdentityEntity.toModel() = StoredIdentity(deviceId, userId, identityKey, verified, approved)

    override fun reserveKeyIds(kind: KeyIdKind, count: Int): Int = tx {
        require(count in 1..MAX_ID)
        val first = dao.counter(kind.name) ?: 1
        // Wrap before the range would overflow so a reserved block is always consecutive.
        val start = if (first + count - 1 > MAX_ID) 1 else first
        dao.putCounter(SignalCounterEntity(kind.name, start + count))
        start
    }

    override fun clearAll() = tx {
        dao.clearIdentity(); dao.clearPreKeys(); dao.clearSignedPreKeys(); dao.clearKyberPreKeys()
        dao.clearLastResortUsed(); dao.clearSessions(); dao.clearRemoteIdentities(); dao.clearCounters()
    }

    companion object {
        const val MAX_ID = 0xFFFFFF
    }
}
