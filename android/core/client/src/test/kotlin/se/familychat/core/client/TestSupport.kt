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
) {
    val tokens = InMemoryTokenProvider()
    val dir: File = Files.createTempDirectory("fc-$name").toFile().also { it.deleteOnExit() }
    val engine = FamilyChatEngine(
        store, protocol, tokens,
        EngineConfig(
            deviceName = "$name phone", attachmentDir = File(dir, "att"), tempDir = File(dir, "tmp"),
            allowInsecureLan = true, startWorkers = workers, typingTimeoutMs = 1_500,
        ),
        scope,
        connectionFactory = { server.connect() },
    )

    suspend fun signUp(): Account =
        engine.redeemInvite("https://fake.example", "CODE", name, "password-$name", name.replaceFirstChar { it.uppercase() })

    fun userId() = engine.account.value!!.userId

    /** The device list of this account as the server knows it. */
    fun deviceIdOnServer() = engine.account.value!!.deviceId

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
