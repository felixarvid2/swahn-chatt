package se.familychat.core.client

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.util.UUID

/**
 * Settings and injectable environment of the engine.
 *
 * @property deviceName shown to other users ("Anna added a new device") and in the device list
 * @property attachmentDir where decrypted downloaded attachments are written (app-private storage)
 * @property tempDir scratch space for encrypted uploads/downloads; downloads are resumable by file name
 */
class EngineConfig(
    val deviceName: String,
    val attachmentDir: File,
    val tempDir: File,
    val allowInsecureLan: Boolean = false,
    val clock: () -> Long = System::currentTimeMillis,
    val newId: () -> String = { UUID.randomUUID().toString() },
    val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** How often a failing send (409 mismatched devices) is retried inside one outbox pass. */
    val maxMismatchRetries: Int = 4,
    /** After this many failed passes a message becomes FAILED (network-only failures never do this). */
    val maxRejectedAttempts: Int = 3,
    /** Typing indicators disappear after this long without a refresh. */
    val typingTimeoutMs: Long = 7_000,
    /** Largest accepted attachment (plaintext bytes) for downloads and sends. */
    val maxAttachmentBytes: Long = 100L * 1024 * 1024,
    /**
     * Run the background workers that sync on hints and deliver the outbox automatically. Turn off to drive
     * `sync()` / `processOutbox()` by hand (tests, or an app that schedules everything with WorkManager).
     */
    val startWorkers: Boolean = true,
)
