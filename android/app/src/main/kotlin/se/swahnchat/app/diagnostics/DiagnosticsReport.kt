package se.swahnchat.app.diagnostics

import se.swahnchat.core.network.RealtimeState
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Whether the server answered `/healthz`, as the report words it. */
enum class Reachability { NOT_CHECKED, REACHABLE, UNREACHABLE, TLS_PROBLEM, UNEXPECTED_ANSWER, INVALID_ADDRESS }

/**
 * Everything the diagnostics screen shows, as plain values. [toText] is the copyable summary and the top of the exported log. It has
 * the same limits as the log: no account name, no message content, no token or key, never the push endpoint, no address with a path
 * and no IP address (a server named by an IP address is shown as "IP address", on the screen only the host is shown).
 */
data class DiagnosticsReport(
    val appVersion: String,
    val buildType: String,
    val androidSdk: Int,
    val serverHost: String? = null,
    val reachability: Reachability = Reachability.NOT_CHECKED,
    val latencyMs: Long? = null,
    /** Server time minus phone time; null before the first answer. */
    val clockOffsetMs: Long? = null,
    val realtime: RealtimeState? = null,
    val lastSyncAt: Long = 0,
    val distributorInstalled: Boolean = false,
    val distributorLabel: String? = null,
    /** The server knows an endpoint for this device (yes or no; the endpoint itself is never part of the report). */
    val endpointRegistered: Boolean = false,
    val lastPushAt: Long? = null,
    val lastPushStatus: Int? = null,
    val notificationsAllowed: Boolean = true,
    val batteryUnrestricted: Boolean = false,
    val outboxSize: Int = 0,
    val databaseBytes: Long = 0,
    val now: Long = 0,
) {
    fun toText(zone: ZoneId = ZoneOffset.UTC): String = buildString {
        val fmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(zone)
        fun time(ms: Long) = if (ms <= 0) "never" else fmt.format(Instant.ofEpochMilli(ms))
        fun yn(b: Boolean) = if (b) "yes" else "no"
        appendLine("Swahn Chat diagnostics")
        appendLine("App: ${EventLog.sanitize(appVersion)} (${EventLog.sanitize(buildType)}), Android API $androidSdk")
        if (now > 0) appendLine("Created: ${time(now)}")
        appendLine("Server: ${serverHost?.let(::publicHost) ?: "unknown"}")
        appendLine(
            "Reachable: " + when (reachability) {
                Reachability.NOT_CHECKED -> "not checked"
                Reachability.REACHABLE -> "yes" + (latencyMs?.let { " ($it ms)" } ?: "")
                Reachability.UNREACHABLE -> "no (no connection)"
                Reachability.TLS_PROBLEM -> "no (certificate or TLS problem)"
                Reachability.UNEXPECTED_ANSWER -> "no (unexpected answer)"
                Reachability.INVALID_ADDRESS -> "no (invalid address)"
            },
        )
        appendLine("Clock offset to server: ${clockOffsetMs?.let { "$it ms" } ?: "unknown"}")
        appendLine("Realtime connection: ${realtime?.name ?: "unknown"}")
        appendLine("Last sync: ${time(lastSyncAt)}")
        appendLine("Push distributor installed: ${yn(distributorInstalled)}" + (distributorLabel?.let { " (${EventLog.sanitize(it)})" } ?: ""))
        appendLine("Push endpoint registered with the server: ${yn(endpointRegistered)}")
        appendLine("Last push attempt by the server: ${lastPushAt?.let { time(it) } ?: "never"}" + (lastPushStatus?.let { ", answer $it" } ?: ""))
        appendLine("Notifications allowed: ${yn(notificationsAllowed)}")
        appendLine("Battery optimisation off for the app: ${yn(batteryUnrestricted)}")
        appendLine("Exact alarms: not used")
        appendLine("Messages waiting in the outbox: $outboxSize")
        appendLine("Local database: ${databaseBytes / 1024} KiB")
    }

    companion object {
        /** A host name stays as it is; an IPv4 or IPv6 address is not written into a summary that may be sent to somebody. */
        fun publicHost(host: String): String {
            val h = host.trim().trim('[', ']')
            val ipv4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(h)
            val ipv6 = ':' in h
            return if (ipv4 || ipv6) "IP address" else EventLog.sanitize(h)
        }
    }
}

/** The export: what the diagnostics summary says, then the log. */
fun exportText(report: DiagnosticsReport, log: EventLog, zone: ZoneId = ZoneOffset.UTC): String =
    report.toText(zone) + "\nLog (newest last, ${log.entries().size} entries)\n" + log.text(zone) + "\n"
