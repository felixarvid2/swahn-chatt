package se.swahnchat.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import se.swahnchat.app.data.SessionManager
import se.swahnchat.app.data.db.AppDatabase
import se.swahnchat.app.data.db.decodeAttachments
import se.swahnchat.app.data.SessionState
import se.swahnchat.app.di.ApplicationScope
import se.swahnchat.app.media.AttachmentPreparer
import se.swahnchat.app.platform.AppForeground
import se.swahnchat.app.platform.ConnectivityMonitor
import se.swahnchat.app.platform.notify.Notifier
import se.swahnchat.app.platform.security.AppLock
import se.swahnchat.app.platform.work.WorkScheduler
import se.swahnchat.core.client.SwahnChatEngine
import se.swahnchat.core.client.LocalStore
import se.swahnchat.core.client.call.CallManager
import javax.inject.Inject
import javax.inject.Singleton

@HiltAndroidApp
class SwahnChatApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var starter: AppStarter

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        starter.start()
    }
}

/** Wires the process-wide pieces together once at start. */
@Singleton
class AppStarter @Inject constructor(
    private val engine: SwahnChatEngine,
    private val session: SessionManager,
    private val foreground: AppForeground,
    private val notifier: Notifier,
    private val connectivity: ConnectivityMonitor,
    private val work: WorkScheduler,
    private val store: LocalStore,
    private val db: AppDatabase,
    private val preparer: AttachmentPreparer,
    private val appLock: AppLock,
    private val expiry: se.swahnchat.app.platform.work.ExpiryManager,
    private val calls: CallManager,
    @ApplicationScope private val scope: CoroutineScope,
) {
    fun start() {
        foreground.addListener { fg -> if (fg) appLock.onForeground() else appLock.onBackground() }
        foreground.attach()
        notifier.start()
        calls.start()
        session.start()

        // No account, no lock (a new sign-in starts without one).
        scope.launch {
            session.state.collect {
                if (it is SessionState.SignedOut) {
                    calls.hangup()
                    if (appLock.enabled) appLock.disable()
                }
            }
        }

        // Realtime (WebSocket + sync on hints) only while the app is visible; in the background push and WorkManager take over.
        scope.launch {
            combine(foreground.isForeground, session.state) { fg, s -> fg && s is SessionState.SignedIn }
                .distinctUntilChanged()
                .collect { live ->
                    if (live) {
                        try {
                            engine.startRealtime()
                        } catch (e: Exception) {
                            // not signed in after all
                        }
                        work.enqueueOutbox()
                        // whatever came due while the app was away goes first
                        runCatching { expiry.purgeAndSchedule() }
                    } else {
                        engine.stopRealtime()
                    }
                }
        }

        // Queued messages go out as soon as the network is back.
        connectivity.start {
            if (session.state.value is SessionState.SignedIn) {
                work.enqueueOutbox()
                if (foreground.isForeground.value) engine.requestSync()
            }
        }

        // Disappearing messages that came due while the app was not running.
        scope.launch {
            session.awaitRestored()
            runCatching { expiry.purgeAndSchedule() }
        }

        // Leftover files of messages that were sent (or abandoned).
        scope.launch {
            session.awaitRestored()
            // Files of messages still in the database stay (they are shown in the conversation); only orphans go.
            val inOutbox = store.getOutbox().flatMap { it.attachments.map { a -> a.localPath } }
            val inMessages = db.uiDao().attachmentJsons().flatMap { json -> decodeAttachments(json).mapNotNull { it.localPath } }
            preparer.cleanup((inOutbox + inMessages).toSet())
            preparer.cleanupStaging()
        }
    }
}
