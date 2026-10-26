package se.swahnchat.app.diagnostics

import java.io.File
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

/** One line of the log: when (epoch ms) and the rendered, sanitised event. */
data class LogEntry(val at: Long, val line: String)

/**
 * A small ring buffer of the app's own events (the last [capacity]), also kept in a private file that rotates at [maxFileBytes]
 * (two files, so at most about twice that on the phone). Only [LogEvent]s go in, see there for what that rules out. [record]
 * never throws and never blocks the caller for more than a lock: writing to the file happens on [writer].
 *
 * Nothing leaves the phone by itself. The person exports it from the diagnostics screen, and sees what it contains first.
 */
@Singleton
class EventLog(
    private val dir: File?,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
    private val writer: Executor = Executor { it.run() },
) {
    @Inject constructor(dirs: se.swahnchat.app.data.StorageDirs) : this(
        File(dirs.attachments.parentFile ?: dirs.transfer, "diagnostics"), writer = BACKGROUND,
    )

    private val lock = Any()
    private val entries = ArrayDeque<LogEntry>()
    private val current get() = File(dir, CURRENT)
    private val previous get() = File(dir, PREVIOUS)

    /** Mixed into every [Ref]; random per install and kept next to the log, so exports of two phones never match up. */
    val salt: ByteArray = loadSalt()

    init {
        load()
    }

    /** The reference for [id] in this log. */
    fun ref(id: String?): Ref = Ref.of(id, salt)

    fun record(event: LogEvent) {
        try {
            val entry = LogEntry(clock(), render(event))
            synchronized(lock) {
                entries.addLast(entry)
                while (entries.size > capacity) entries.removeFirst()
            }
            writer.execute { append(entry) }
        } catch (e: Exception) {
            // diagnostics must never break the app
        }
    }

    /** The entries, oldest first. */
    fun entries(): List<LogEntry> = synchronized(lock) { entries.toList() }

    /** The log as text for the export: one line per entry, `time tag key=value ...`. */
    fun text(zone: java.time.ZoneId = java.time.ZoneOffset.UTC): String {
        val fmt = java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(zone)
        return entries().joinToString("\n") { "${fmt.format(java.time.Instant.ofEpochMilli(it.at))} ${it.line}" }
    }

    fun clear() {
        synchronized(lock) { entries.clear() }
        writer.execute {
            synchronized(fileLock) {
                current.delete(); previous.delete()
            }
        }
    }

    // --- file -----------------------------------------------------------------------------------------------------------

    private val fileLock = Any()

    private fun append(entry: LogEntry) {
        if (dir == null) return
        try {
            synchronized(fileLock) {
                dir.mkdirs()
                if (current.length() >= maxFileBytes) {
                    previous.delete()
                    current.renameTo(previous)
                }
                current.appendText("${entry.at}\t${entry.line}\n", Charsets.UTF_8)
            }
        } catch (e: Exception) {
            // a full disk or a missing folder: the memory copy still works
        }
    }

    private fun load() {
        if (dir == null) return
        try {
            val lines = (previous.takeIf { it.isFile }?.readLines().orEmpty() + current.takeIf { it.isFile }?.readLines().orEmpty())
            for (raw in lines.takeLast(capacity)) {
                val at = raw.substringBefore('\t').toLongOrNull()
                val line = LogVocabulary.accept(raw.substringAfter('\t', ""))
                if (at != null && line != null) entries.addLast(LogEntry(at, line))
            }
        } catch (e: Exception) {
            // an unreadable file starts an empty log
        }
    }

    private fun loadSalt(): ByteArray {
        if (dir == null) return ByteArray(8) { it.toByte() }
        return try {
            dir.mkdirs()
            val f = File(dir, SALT)
            if (f.isFile && f.length() == 8L) f.readBytes() else ByteArray(8).also { SecureRandom().nextBytes(it); f.writeBytes(it) }
        } catch (e: Exception) {
            ByteArray(8).also { SecureRandom().nextBytes(it) }
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 2000
        const val DEFAULT_MAX_FILE_BYTES = 1_000_000L
        private const val CURRENT = "events.log"
        private const val PREVIOUS = "events.1.log"
        private const val SALT = "salt"
        private val BACKGROUND: Executor = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "event-log").apply { isDaemon = true } }

        private val SAFE = Regex("[^A-Za-z0-9_.-]")

        /** Keeps a key or value to the characters of an identifier and at most 40 of them: a space, `/`, `:`, `@` or `=` cannot get in. */
        fun sanitize(s: String): String = SAFE.replace(s, "?").take(40)

        fun render(event: LogEvent): String =
            (listOf(sanitize(event.tag)) + event.fields().map { (k, v) -> "${sanitize(k)}=${sanitize(v)}" }).joinToString(" ")
    }
}
