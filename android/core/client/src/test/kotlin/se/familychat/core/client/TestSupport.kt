package se.familychat.core.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import se.familychat.core.crypto.InMemoryProtocolStorage
import se.familychat.core.network.InMemoryTokenProvider
import java.io.File
import java.nio.file.Files

/** One simulated phone: engine + its own stores, attachment directories, and tokens. */
class TestClient(
    val server: FakeServer,
    val name: String,
    val scope: CoroutineScope,
    workers: Boolean = false,
    val store: LocalStore = InMemoryLocalStore(),
    val protocol: InMemoryProtocolStorage = InMemoryProtocolStorage(),
    clock: () -> Long = System::currentTimeMillis,
    resumableThreshold: Long = 8L * 1024 * 1024,
    chunkBytes: Int = 8 * 1024 * 1024,
) {
    val tokens = InMemoryTokenProvider()
    val dir: File = Files.createTempDirectory("fc-$name").toFile().also { it.deleteOnExit() }
    val engine = FamilyChatEngine(
        store, protocol, tokens,
        EngineConfig(
            deviceName = "$name phone", attachmentDir = File(dir, "att"), tempDir = File(dir, "tmp"),
            allowInsecureLan = true, startWorkers = workers, typingTimeoutMs = 1_500, clock = clock,
            resumableUploadThreshold = resumableThreshold, uploadChunkBytes = chunkBytes,
        ),
        scope,
        connectionFactory = { server.connect() },
    )

    suspend fun signUp(): Account =
        engine.redeemInvite("https://fake.example", "CODE", name, "password-$name", name.replaceFirstChar { it.uppercase() })

    fun userId() = engine.account.value!!.userId

    /** The device list of this account as the server knows it. */
    fun deviceIdOnServer() = engine.account.value!!.deviceId

    /** The user approves every pending device (changed key or new device) after comparing safety numbers. */
    suspend fun approveAllPending() {
        for (p in engine.pendingIdentityChanges()) check(engine.acceptIdentity(p.userId, p.deviceId, p.newIdentityKey!!)) { "not accepted: ${p.deviceId}" }
    }

    suspend fun messages(conversationId: String) = store.getMessages(conversationId, 1000)
    suspend fun texts(conversationId: String) = messages(conversationId).filter { !it.isSystem }.map { it.text }
}

fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

suspend fun eventually(timeoutMs: Long = 8_000, what: String = "condition", cond: suspend () -> Boolean) {
    try {
        withTimeout(timeoutMs) { while (!cond()) delay(20) }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        throw AssertionError("Timed out waiting for $what")
    }
}

/** Delivers [msg] to [deviceId] as a `message` event, whatever the server really stored (a malicious server can). */
fun FakeServer.injectMessage(deviceId: String, msg: se.familychat.core.model.Message) {
    injectEvent(
        deviceId, se.familychat.core.model.EventType.MESSAGE, msg.conversationId,
        se.familychat.core.model.FamilyChatJson.encodeToJsonElement(se.familychat.core.model.Message.serializer(), msg),
    )
}

private val injectedSeq = java.util.concurrent.atomic.AtomicLong(1_000_000)

/** A message as the attacker would label it: [kind] from [senderDevice] of [senderUser] in [conversationId]. */
fun forgedMessage(
    id: String,
    conversationId: String,
    senderUser: String,
    senderDevice: String,
    envelope: se.familychat.core.model.Envelope?,
    kind: String = se.familychat.core.model.MessageKind.MESSAGE,
    target: String? = null,
) = se.familychat.core.model.Message(
    id = id, conversationId = conversationId, senderUserId = senderUser, senderDeviceId = senderDevice, kind = kind,
    targetMessageId = target, clientTs = 1, serverTs = 1000, seq = injectedSeq.incrementAndGet(),
    envelope = envelope?.let { se.familychat.core.model.MessageEnvelope(it.type, it.body) },
)

/** The bundle the server would give [asDevice] for [target]'s device (consumes a one-time key). */
suspend fun FakeServer.bundleFor(asDevice: String, target: TestClient) =
    connect(asDevice).api.getKeys(target.userId(), target.deviceIdOnServer()).devices.single()
