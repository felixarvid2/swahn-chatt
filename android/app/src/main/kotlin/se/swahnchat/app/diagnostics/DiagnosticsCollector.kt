package se.swahnchat.app.diagnostics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import se.swahnchat.app.BuildConfig
import se.swahnchat.app.data.AppPrefs
import se.swahnchat.app.data.ChatRepository
import se.swahnchat.app.data.ServerCheck
import se.swahnchat.app.data.ServerChecker
import se.swahnchat.app.data.StorageDirs
import se.swahnchat.app.platform.push.PushManager
import se.swahnchat.app.qr.InviteQr
import se.swahnchat.core.client.SwahnChatEngine
import javax.inject.Inject
import javax.inject.Singleton

/** Gathers the figures of the diagnostics screen. Every part is read on its own, so one that fails leaves the rest intact. */
@Singleton
class DiagnosticsCollector @Inject constructor(
    private val engine: SwahnChatEngine,
    private val checker: ServerChecker,
    private val push: PushManager,
    private val prefs: AppPrefs,
    private val repo: ChatRepository,
    private val dirs: StorageDirs,
    private val device: DeviceStatus,
) {
    /** Everything that needs no network. */
    fun local(): DiagnosticsReport {
        val distributors = runCatching { push.distributors() }.getOrDefault(emptyList())
        val chosen = runCatching { push.selectedDistributor() }.getOrNull()
        val account = engine.account.value
        return DiagnosticsReport(
            appVersion = BuildConfig.VERSION_NAME, buildType = BuildConfig.BUILD_TYPE, androidSdk = android.os.Build.VERSION.SDK_INT,
            serverHost = account?.serverUrl?.let(InviteQr::hostOf),
            clockOffsetMs = account?.let { engine.serverNow() - System.currentTimeMillis() },
            realtime = engine.realtimeState.value, lastSyncAt = prefs.lastSyncAt,
            distributorInstalled = distributors.isNotEmpty(),
            distributorLabel = distributors.firstOrNull { it.packageName == chosen }?.label,
            endpointRegistered = prefs.pushEndpointUploaded != null,
            notificationsAllowed = device.notificationsAllowed(), batteryUnrestricted = device.batteryUnrestricted(),
            databaseBytes = DB_SUFFIXES.sumOf { suffix -> java.io.File(dirs.database.path + suffix).let { if (it.isFile) it.length() else 0L } },
            now = System.currentTimeMillis(),
        )
    }

    /** [local] plus the reachability of the server, the outbox size and what the server says about the last push to this device. */
    suspend fun collect(): DiagnosticsReport {
        var report = local()
        report = report.copy(outboxSize = runCatching { repo.outboxCount().first() }.getOrDefault(0))
        val url = engine.account.value?.serverUrl
        if (url != null) {
            val check = checker.check(url)
            report = report.copy(
                reachability = when (check) {
                    is ServerCheck.Reachable -> Reachability.REACHABLE
                    ServerCheck.Unreachable -> Reachability.UNREACHABLE
                    ServerCheck.TlsProblem -> Reachability.TLS_PROBLEM
                    is ServerCheck.UnexpectedAnswer -> Reachability.UNEXPECTED_ANSWER
                    ServerCheck.InvalidUrl, ServerCheck.InsecureNotAllowed -> Reachability.INVALID_ADDRESS
                },
                latencyMs = (check as? ServerCheck.Reachable)?.latencyMs,
            )
            if (check is ServerCheck.Reachable) {
                try {
                    val d = engine.currentDevice()
                    report = report.copy(lastPushAt = d.lastPushAt, lastPushStatus = d.lastPushStatus)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // the push line stays "never"
                }
            }
        }
        return report.copy(clockOffsetMs = if (url != null) engine.serverNow() - System.currentTimeMillis() else null, now = System.currentTimeMillis())
    }

    private companion object {
        val DB_SUFFIXES = listOf("", "-wal", "-shm", "-journal")
    }
}
