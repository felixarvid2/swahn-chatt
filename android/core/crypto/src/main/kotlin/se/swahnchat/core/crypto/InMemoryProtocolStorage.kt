package se.swahnchat.core.crypto

/** Thread-safe in-memory [ProtocolStorage] for tests (and tools). Everything is lost with the instance. */
class InMemoryProtocolStorage : ProtocolStorage {
    private val lock = Any()
    private var local: LocalIdentity? = null
    private val preKeys = HashMap<Int, ByteArray>()
    private val signedPreKeys = LinkedHashMap<Int, ByteArray>()
    private val kyber = LinkedHashMap<Int, StoredKyberPreKey>()
    private val usedBaseKeys = HashSet<String>()
    private val sessions = HashMap<String, ByteArray>()
    private val identities = HashMap<String, StoredIdentity>()
    private val counters = HashMap<KeyIdKind, Int>()

    override fun getLocalIdentity() = synchronized(lock) { local }
    override fun setLocalIdentity(identity: LocalIdentity) = synchronized(lock) { local = identity }

    override fun loadPreKey(id: Int) = synchronized(lock) { preKeys[id]?.copyOf() }
    override fun storePreKey(id: Int, record: ByteArray) = synchronized(lock) { preKeys[id] = record.copyOf(); Unit }
    override fun removePreKey(id: Int) = synchronized(lock) { preKeys.remove(id); Unit }
    override fun countPreKeys() = synchronized(lock) { preKeys.size }

    override fun loadSignedPreKey(id: Int) = synchronized(lock) { signedPreKeys[id]?.copyOf() }
    override fun loadSignedPreKeys() = synchronized(lock) { signedPreKeys.values.map { it.copyOf() } }
    override fun storeSignedPreKey(id: Int, record: ByteArray) = synchronized(lock) { signedPreKeys[id] = record.copyOf(); Unit }
    override fun removeSignedPreKey(id: Int) = synchronized(lock) { signedPreKeys.remove(id); Unit }

    override fun loadKyberPreKey(id: Int) = synchronized(lock) { kyber[id]?.record?.copyOf() }
    override fun loadKyberPreKeys() = synchronized(lock) { kyber.values.toList() }
    override fun storeKyberPreKey(id: Int, record: ByteArray, lastResort: Boolean) =
        synchronized(lock) { kyber[id] = StoredKyberPreKey(id, record.copyOf(), lastResort); Unit }

    override fun removeKyberPreKey(id: Int) = synchronized(lock) { kyber.remove(id); Unit }

    override fun markLastResortUsed(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ByteArray) =
        synchronized(lock) { usedBaseKeys.add("$kyberPreKeyId/$signedPreKeyId/${baseKey.joinToString("") { "%02x".format(it) }}") }

    override fun loadSession(name: String) = synchronized(lock) { sessions[name]?.copyOf() }
    override fun storeSession(name: String, record: ByteArray) = synchronized(lock) { sessions[name] = record.copyOf(); Unit }
    override fun deleteSession(name: String) = synchronized(lock) { sessions.remove(name); Unit }
    override fun deleteSessionsWithPrefix(prefix: String) = synchronized(lock) { sessions.keys.removeAll { it.startsWith(prefix) }; Unit }

    override fun getIdentity(deviceId: String) = synchronized(lock) { identities[deviceId] }
    override fun putIdentity(identity: StoredIdentity) = synchronized(lock) { identities[identity.deviceId] = identity; Unit }
    override fun identitiesOf(userId: String) = synchronized(lock) { identities.values.filter { it.userId == userId } }

    override fun reserveKeyIds(kind: KeyIdKind, count: Int): Int = synchronized(lock) {
        require(count in 1..MAX_ID)
        val first = counters[kind] ?: 1
        // Wrap before the range would overflow so a reserved block is always consecutive.
        val start = if (first + count - 1 > MAX_ID) 1 else first
        counters[kind] = start + count
        start
    }

    override fun clearAll() = synchronized(lock) {
        local = null
        preKeys.clear(); signedPreKeys.clear(); kyber.clear(); usedBaseKeys.clear()
        sessions.clear(); identities.clear(); counters.clear()
    }

    companion object {
        const val MAX_ID = 0xFFFFFF
    }
}
