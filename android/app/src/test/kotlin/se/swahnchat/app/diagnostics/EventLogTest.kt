@file:Suppress("MaxLineLength")

package se.swahnchat.app.diagnostics

import se.swahnchat.core.client.FailureReason
import se.swahnchat.core.client.call.CallState
import se.swahnchat.core.client.call.EndReason
import se.swahnchat.core.network.ApiException
import se.swahnchat.core.network.RealtimeState
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.file.Files
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Everything somebody could try to smuggle into a troubleshooting log. */
private object Secrets {
    const val MESSAGE = "Hej mamma, mitt kreditkort är 4111 1111 1111 1111"
    const val NAME = "Anna Andersson"
    const val TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhbm5hIn0.SECRETSIGNATURE"
    const val ENDPOINT = "https://ntfy.example.org/up/AbCdEf123456SECRETTOPIC"
    const val URL_WITH_PATH = "https://family.example.org/api/v1/messages/9f8e7d6c-0000-4000-8000-000000000001"
    const val IP = "192.168.1.23"
    const val IPV6 = "2001:db8::1234"
    const val KEY = "BQhrZXlieXRlcwECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    const val UUID = "6f1c2b3a-9d4e-4f5a-8b7c-1d2e3f4a5b6c"
    val ALL = listOf(MESSAGE, NAME, TOKEN, ENDPOINT, URL_WITH_PATH, IP, IPV6, KEY, UUID, "SECRETSIGNATURE", "SECRETTOPIC", "kreditkort", "Andersson")
}

class EventLogTest {
    private val salt = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

    /** One of every event type, built with the nastiest input each type can take. */
    private fun everyEvent(): List<LogEvent> {
        val hostile = listOf(
            IOException(Secrets.URL_WITH_PATH + " " + Secrets.TOKEN),
            ApiException.Conflict(Secrets.MESSAGE, Secrets.NAME + " " + Secrets.ENDPOINT),
            ApiException.Server(503, Secrets.KEY, Secrets.IP),
            SSLHandshakeException(Secrets.IPV6),
            SocketTimeoutException(Secrets.ENDPOINT),
            IllegalStateException(Secrets.MESSAGE),
            RuntimeException(Secrets.TOKEN),
        )
        val errors = hostile.map { ErrorClass.of(it) }
        val status = hostile.maxOf { ErrorClass.statusOf(it) }
        val chat = Ref.of(Secrets.UUID, salt)
        val nameRef = Ref.of(Secrets.NAME + Secrets.MESSAGE, salt)
        return listOf(
            AppStarted(5, 34),
            SyncCycle(SyncTrigger.PUSH, 3, errors[0], status),
            PushReceived(),
            PushRegistration(true, errors[1]),
            PushTest(201, errors[2]),
            RealtimeChange(RealtimeState.CONNECTED),
            SendFailed(chat, FailureReason.REJECTED, errors[3], status),
            SendFailed(nameRef, null, errors[4]),
            OutboxPass(1, 2, 3, true, errors[5]),
            CallChange(CallPhase.ENDED, EndReason.FAILED, true),
            WorkerRun(WorkerKind.OUTBOX, true, 1234),
            SessionEvent(SessionChange.SESSION_ENDED),
        )
    }

    @Test fun `no secret gets into the log through any event type`() {
        val log = EventLog(null)
        everyEvent().forEach(log::record)
        val text = log.text()
        assertTrue(text.isNotEmpty())
        for (secret in Secrets.ALL) assertFalse(secret in text, "leaked: $secret")
        // not even fragments of an address, a path or a person
        for (fragment in listOf("http", "://", "/api", "family.example", "ntfy", "192.", "2001:", "Bearer", "eyJ")) assertFalse(fragment in text, "fragment: $fragment")
    }

    @Test fun `every event type is covered by the leak test and is built from numbers, flags, enums and references only`() {
        val covered = everyEvent().map { it::class.java }.toSet()
        val all = LogEvent::class.java.permittedSubclasses.orEmpty().toSet()
        assertTrue(all.isNotEmpty(), "the sealed hierarchy must be visible to the test")
        assertEquals(all, covered, "a new event type needs a case in everyEvent()")
        val allowed = setOf(Int::class.javaPrimitiveType, Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Ref::class.java)
        for (type in all) for (field in type.declaredFields.filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }) {
            assertTrue(field.type in allowed || field.type.isEnum, "${type.simpleName}.${field.name} is a ${field.type.simpleName}: free text cannot be a field of an event")
        }
    }

    @Test fun `an error is logged as its kind and status, never its message`() {
        assertEquals(ErrorClass.OFFLINE, ErrorClass.of(IOException(Secrets.URL_WITH_PATH)))
        assertEquals(ErrorClass.TIMEOUT, ErrorClass.of(SocketTimeoutException(Secrets.ENDPOINT)))
        assertEquals(ErrorClass.TLS, ErrorClass.of(SSLHandshakeException(Secrets.IP)))
        assertEquals(ErrorClass.SERVER_ERROR, ErrorClass.of(ApiException.Server(502, "x", "y")))
        assertEquals(ErrorClass.RATE_LIMITED, ErrorClass.of(ApiException.RateLimited("slow", 3)))
        assertEquals(ErrorClass.SESSION_ENDED, ErrorClass.of(ApiException.SessionEnded("unauthorized", "")))
        assertEquals(ErrorClass.REFUSED_BY_SERVER, ErrorClass.of(ApiException.Forbidden("forbidden", Secrets.NAME)))
        assertEquals(ErrorClass.NONE, ErrorClass.of(null))
        assertEquals(403, ErrorClass.statusOf(ApiException.Forbidden("a", "b")))
        assertEquals(0, ErrorClass.statusOf(IOException("x")))
    }

    @Test fun `a reference is eight hex digits, stable for one salt and different for another`() {
        val a = Ref.of(Secrets.UUID, salt)
        assertTrue(Regex("[0-9a-f]{8}").matches(a.shown))
        assertEquals(a, Ref.of(Secrets.UUID, salt))
        assertNotEquals(a, Ref.of(Secrets.UUID, ByteArray(8) { 9 }))
        assertNotEquals(a, Ref.of("another-id", salt))
        assertEquals("none", Ref.of(null).shown)
        assertFalse(Secrets.UUID in a.shown)
    }

    @Test fun `a value cannot carry a space, a slash or a colon`() {
        assertEquals("a?b?c?d?e", EventLog.sanitize("a b/c:d@e"))
        assertEquals(40, EventLog.sanitize("x".repeat(500)).length)
        // whatever an event holds is sanitised again when it is rendered
        val line = EventLog.render(WorkerRun(WorkerKind.SYNC, false, 5))
        assertEquals("worker kind=SYNC retry=false ms=5", line)
    }

    @Test fun `the buffer keeps the newest entries only`() {
        var t = 0L
        val log = EventLog(null, capacity = 5, clock = { ++t })
        repeat(12) { log.record(WorkerRun(WorkerKind.SYNC, false, it.toLong())) }
        val entries = log.entries()
        assertEquals(5, entries.size)
        assertEquals(listOf(8L, 9L, 10L, 11L, 12L), entries.map { it.at })
        assertTrue("ms=11" in entries.last().line)
        log.clear()
        assertTrue(log.entries().isEmpty())
    }

    @Test fun `the file rotates at its limit and a new log starts from what is on disk`() {
        val dir = Files.createTempDirectory("eventlog").toFile()
        try {
            var t = 0L
            val log = EventLog(dir, capacity = 100, maxFileBytes = 300, clock = { ++t })
            repeat(40) { log.record(WorkerRun(WorkerKind.SYNC, false, it.toLong())) }
            val current = File(dir, "events.log")
            val previous = File(dir, "events.1.log")
            assertTrue(previous.isFile, "rotated once the first file passed the limit")
            // a file stays near the limit: it is never allowed to grow beyond limit + one line
            assertTrue(current.length() < 300 + 80, "current ${current.length()}")
            assertTrue(previous.length() < 300 + 80, "previous ${previous.length()}")
            assertTrue(File(dir, "events.2.log").exists().not(), "only two files exist")

            val reopened = EventLog(dir, capacity = 100, maxFileBytes = 300, clock = { ++t })
            assertTrue(reopened.entries().isNotEmpty())
            assertTrue(reopened.entries().size < 40, "the oldest lines were dropped with the rotation")
            assertTrue("ms=39" in reopened.entries().last().line, "the newest line survives")
            assertEquals(log.entries().last(), reopened.entries().last())
            // the salt is kept, so references stay the same after a restart
            assertEquals(log.ref(Secrets.UUID), reopened.ref(Secrets.UUID))

            reopened.clear()
            assertFalse(current.exists())
            assertFalse(previous.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `every word an event writes is in the vocabulary that a file is checked against`() {
        for (event in everyEvent()) {
            assertTrue(event.tag in LogVocabulary.tags, event.tag)
            for ((k, _) in event.fields()) assertTrue(k in LogVocabulary.keys, "${event.tag}.$k")
            assertEquals(EventLog.render(event), LogVocabulary.accept(EventLog.render(event)), "own lines are accepted unchanged")
        }
    }

    @Test fun `a file somebody edited cannot add free text to an export`() {
        val dir = Files.createTempDirectory("eventlog").toFile()
        try {
            File(dir, "events.log").writeText(
                listOf(
                    "1\tsync trigger=PUSH applied=2 result=ok status=0", // a real line
                    "2\tsync trigger=PUSH note=${Secrets.MESSAGE.replace(' ', '_')}", // an unknown key
                    "3\tsync trigger=${Secrets.MESSAGE}", // spaces in a value
                    "4\tsecret_tag trigger=PUSH", // an unknown tag
                    "5\tsync trigger=https://evil.example/x", // a web address as a value
                    "garbage without a tab",
                ).joinToString("\n") + "\n",
            )
            val entries = EventLog(dir).entries()
            assertEquals(listOf("sync trigger=PUSH applied=2 result=ok status=0"), entries.map { it.line })
            val text = EventLog(dir).text()
            assertFalse("evil" in text || "kreditkort" in text || "://" in text)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `recording never throws, even when the clock or the writer fail`() {
        val bad = EventLog(null, clock = { error("no clock") })
        bad.record(PushReceived())
        val noWriter = EventLog(null, writer = { error("no thread") })
        noWriter.record(PushReceived())
        assertEquals(1, noWriter.entries().size)
    }

    @Test fun `call states are logged as a phase and a reason, nothing about who`() {
        val outgoing = CallState.Outgoing(Secrets.UUID, Secrets.UUID, Secrets.NAME, true)
        assertEquals(CallPhase.RINGING_OUT to null, outgoing.toPhase())
        assertEquals(CallPhase.RINGING_IN to null, CallState.Incoming("c", "v", "p", false).toPhase())
        assertEquals(CallPhase.ENDED to EndReason.MISSED, CallState.Ended("c", EndReason.MISSED).toPhase())
        assertEquals(CallPhase.IDLE to null, CallState.Idle.toPhase())
        val line = EventLog.render(CallChange(CallPhase.ENDED, EndReason.MISSED))
        assertEquals("call phase=ENDED reason=MISSED video=false", line)
    }
}
