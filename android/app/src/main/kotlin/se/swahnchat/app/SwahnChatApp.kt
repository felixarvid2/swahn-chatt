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
import se.swahnchat.app.call.CallNotifier
import se.swahnchat.core.client.call.CallManager
import se.swahnchat.core.client.call.CallState
import javax.inject.Inject
import javax.inject.Singleton

@HiltAndroidApp
class SwahnChatApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var starter: AppStarter

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(AppLocale.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        starter.start()
    }
}

/**
 * Whether the realtime connection should be up: someone is signed in and the app is visible, or a call is in progress
 * (ringing, connecting or connected), also in the background. It stops again when the call ends and the app is still hidden.
 */
fun realtimeWanted(foreground: Boolean, signedIn: Boolean, call: CallState): Boolean =
    signedIn && (foreground || call is CallState.InCall)

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
    private val callNotifier: CallNotifier,
    private val shortcutSync: se.swahnchat.app.platform.notify.ShortcutSync,
    private val prefs: se.swahnchat.app.data.AppPrefs,
    private val recorder: se.swahnchat.app.diagnostics.DiagnosticsRecorder,
    @ApplicationScope private val scope: CoroutineScope,
) {
    fun start() {
        foreground.addListener { fg -> if (fg) appLock.onForeground() else appLock.onBackground() }
        foreground.attach()
        notifier.start()
        shortcutSync.start()
        calls.start()
        callNotifier.start()
        session.start()
        recorder.start()

        // No account, no lock (a new sign-in starts without one).
        scope.launch {
            session.state.collect {
                if (it is SessionState.SignedOut) {
                    notifier.onSignedOut() // notifications, per-chat channels and launcher shortcuts name people: none stays
                    calls.hangup()
                    if (appLock.enabled) appLock.disable()
                }
            }
        }

        // The privacy switches of the settings decide what the engine tells other people.
        scope.launch { prefs.sendReadReceipts.collect { engine.sendReadReceipts = it } }
        scope.launch { prefs.showTyping.collect { engine.sendTypingIndicators = it } }

        // Realtime (WebSocket + sync on hints) while the app is visible or a call is going on (the caller's hang-up must reach a
        // ringing phone); otherwise push and WorkManager take over.
        scope.launch {
            combine(foreground.isForeground, session.state, calls.state) { fg, s, call ->
                realtimeWanted(fg, s is SessionState.SignedIn, call)
            }
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
