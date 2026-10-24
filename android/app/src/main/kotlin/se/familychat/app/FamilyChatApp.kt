package se.familychat.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import se.familychat.app.data.SessionManager
import se.familychat.app.data.db.AppDatabase
import se.familychat.app.data.db.decodeAttachments
import se.familychat.app.data.SessionState
import se.familychat.app.di.ApplicationScope
import se.familychat.app.media.AttachmentPreparer
import se.familychat.app.platform.AppForeground
import se.familychat.app.platform.ConnectivityMonitor
import se.familychat.app.platform.notify.Notifier
import se.familychat.app.platform.work.WorkScheduler
import se.familychat.core.client.FamilyChatEngine
import se.familychat.core.client.LocalStore
import javax.inject.Inject
import javax.inject.Singleton

@HiltAndroidApp
class FamilyChatApp : Application(), Configuration.Provider {
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
    private val engine: FamilyChatEngine,
    private val session: SessionManager,
    private val foreground: AppForeground,
    private val notifier: Notifier,
    private val connectivity: ConnectivityMonitor,
    private val work: WorkScheduler,
    private val store: LocalStore,
    private val db: AppDatabase,
    private val preparer: AttachmentPreparer,
    @ApplicationScope private val scope: CoroutineScope,
) {
    fun start() {
        foreground.attach()
        notifier.start()
        session.start()

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

        // Leftover files of messages that were sent (or abandoned).
        scope.launch {
            session.awaitRestored()
            // Files of messages still in the database stay (they are shown in the conversation); only orphans go.
            val inOutbox = store.getOutbox().flatMap { it.attachments.map { a -> a.localPath } }
            val inMessages = db.uiDao().attachmentJsons().flatMap { json -> decodeAttachments(json).mapNotNull { it.localPath } }
            preparer.cleanup((inOutbox + inMessages).toSet())
        }
    }
}
