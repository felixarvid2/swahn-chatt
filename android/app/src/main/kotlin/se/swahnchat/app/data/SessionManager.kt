package se.swahnchat.app.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.swahnchat.app.data.security.PassphraseVault
import se.swahnchat.app.diagnostics.ErrorClass
import se.swahnchat.app.diagnostics.EventLog
import se.swahnchat.app.diagnostics.OutboxPass
import se.swahnchat.app.diagnostics.SessionChange
import se.swahnchat.app.diagnostics.SessionEvent
import se.swahnchat.app.diagnostics.SyncCycle
import se.swahnchat.app.diagnostics.SyncTrigger
import se.swahnchat.app.diagnostics.WorkerKind
import se.swahnchat.app.diagnostics.WorkerRun
import se.swahnchat.app.di.ApplicationScope
import se.swahnchat.app.platform.notify.Notifier
import se.swahnchat.app.platform.push.PushManager
import se.swahnchat.app.platform.work.BackgroundResult
import se.swahnchat.app.platform.work.WorkScheduler
import se.swahnchat.core.client.Account
import se.swahnchat.core.client.EngineEvent
import se.swahnchat.core.client.SwahnChatEngine
import se.swahnchat.core.network.ApiException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SessionState {
    /** The stored account is still being read from the encrypted database. */
    data object Loading : SessionState
    data object SignedOut : SessionState
    data class SignedIn(val account: Account) : SessionState

    /**
     * The encrypted database cannot be opened right now (the Keystore did not answer). Nothing was deleted; the app shows an
     * error screen with a retry button instead of the login screen (signing in would start over with an empty database).
     */
    data object StorageUnavailable : SessionState
}

/** What went wrong in a way the UI can word. */
enum class UiError {
    NETWORK, INVALID_CREDENTIALS, USERNAME_TAKEN, INVALID_INVITE, RATE_LIMITED, FORBIDDEN, TOO_LARGE, INVALID_INPUT, DEVICE_LIMIT,
    SESSION_ENDED, SERVER, INSECURE_URL, UNKNOWN,

    /** `404 invalid_reset_code`: unknown, expired, used or replaced. */
    INVALID_RESET_CODE,

    /** `409 last_admin`: the change would leave the server without an active administrator. */
    LAST_ADMIN,

    /** `409 push_not_configured`: no push endpoint is registered for this device, or the server has no push allow-list. */
    PUSH_NOT_CONFIGURED,

    /** `403 invalid_credentials` from an action that asks for the password again (deleting the account). */
    WRONG_PASSWORD,

    /** `403 account_suspended`. */
    ACCOUNT_SUSPENDED,
}

fun Throwable.toUiError(): UiError = when (this) {
    is ApiException -> toUiError()
    is se.swahnchat.core.network.InvalidBaseUrlException -> UiError.INSECURE_URL
    is IOException -> UiError.NETWORK
    is IllegalArgumentException -> UiError.INVALID_INPUT
    else -> UiError.UNKNOWN
}

private fun ApiException.toUiError(): UiError = when (this) {
    is ApiException.InvalidCredentials -> UiError.INVALID_CREDENTIALS
    is ApiException.NotFound -> when (code) {
        "invalid_invite" -> UiError.INVALID_INVITE
        "invalid_reset_code" -> UiError.INVALID_RESET_CODE
        else -> UiError.UNKNOWN
    }
    is ApiException.Conflict -> when (code) {
        "username_taken" -> UiError.USERNAME_TAKEN
        "device_limit" -> UiError.DEVICE_LIMIT
        "last_admin" -> UiError.LAST_ADMIN
        "push_not_configured" -> UiError.PUSH_NOT_CONFIGURED
        else -> UiError.UNKNOWN
    }
    is ApiException.RateLimited -> UiError.RATE_LIMITED
    is ApiException.Forbidden -> when (code) {
        "invalid_credentials" -> UiError.WRONG_PASSWORD
        "account_suspended" -> UiError.ACCOUNT_SUSPENDED
        else -> UiError.FORBIDDEN
    }
    is ApiException.PayloadTooLarge -> UiError.TOO_LARGE
    is ApiException.Invalid -> UiError.INVALID_INPUT
    is ApiException.SessionEnded -> UiError.SESSION_ENDED
    is ApiException.Server -> UiError.SERVER
    else -> UiError.UNKNOWN
}

/**
 * Owns the sign-in state of the app: restores the account at start, signs in and out, reacts to a revoked session, and
 * runs the background jobs (sync, outbox) for WorkManager.
 */
@Singleton
class SessionManager @Inject constructor(
    private val engine: SwahnChatEngine,
    @ApplicationScope private val scope: CoroutineScope,
    private val prefs: AppPrefs,
    private val push: PushManager,
    private val work: WorkScheduler,
    private val notifier: Notifier,
    private val preparer: se.swahnchat.app.media.AttachmentPreparer,
    private val log: EventLog,
) {
    private val restored = MutableStateFlow(false)
    private val storageUnavailable = MutableStateFlow(false)

    val state: StateFlow<SessionState> = combine(restored, engine.account, storageUnavailable) { done, account, storageDown ->
        when {
            storageDown -> SessionState.StorageUnavailable
            !done -> SessionState.Loading
            account == null -> SessionState.SignedOut
            else -> SessionState.SignedIn(account)
        }
    }.stateIn(scope, SharingStarted.Eagerly, SessionState.Loading)

    /** Call once from Application.onCreate. */
    fun start() {
        scope.launch { restore() }
        scope.launch {
            engine.events.collect { event ->
                if (event is EngineEvent.SessionEnded) {
                    log.record(SessionEvent(SessionChange.SESSION_ENDED))
                    prefs.sessionEndedNotice = true
                    wipe()
                }
            }
        }
    }

    private suspend fun restore() {
        storageUnavailable.value = false
        try {
            if (engine.restoreSession() != null) onSignedIn()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (generateSequence<Throwable>(e) { it.cause }.any { it is PassphraseVault.TemporarilyUnavailableException }) {
                // Not a lost key: leave the database alone and let the user try again.
                Log.w(TAG, "The database key is temporarily unavailable", e)
                storageUnavailable.value = true
                return
            }
            Log.e(TAG, "Could not restore the session", e)
        }
        restored.value = true
    }

    /** Tries to open the database again after [SessionState.StorageUnavailable]. */
    fun retryRestore() {
        scope.launch { restore() }
    }

    suspend fun login(serverUrl: String, username: String, password: String, deviceName: String): UiError? =
        signIn(SessionChange.SIGNED_IN) { engine.login(serverUrl, username, password, deviceName) }

    /**
     * Signs in with a password-reset code from an administrator ("Glömt lösenordet?"). The server revokes the account's old devices and
     * this phone becomes a new device, which the other people have to approve like any new device; nothing here skips that.
     */
    suspend fun resetPassword(serverUrl: String, code: String, newPassword: String, deviceName: String): UiError? =
        signIn(SessionChange.SIGNED_IN_WITH_RESET_CODE) { engine.resetPassword(serverUrl, code, newPassword, deviceName) }

    /**
     * Deletes the account on the server and, only if the server accepted, wipes this phone as a sign-out does. Returns null on success
     * (the state then turns to signed out and the app shows the start screen) or why it did not work; nothing is wiped then.
     */
    suspend fun deleteAccount(password: String): UiError? = try {
        engine.deleteAccount(password)
        push.unregister(tellServer = false)
        afterWipe()
        // the log of an account that no longer exists is not kept (it holds only shortened hashes and event kinds, but nothing stays behind)
        log.clear()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.toUiError()
    }

    suspend fun redeem(
        serverUrl: String,
        code: String,
        username: String,
        password: String,
        displayName: String,
        deviceName: String,
    ): UiError? = signIn(SessionChange.SIGNED_IN) { engine.redeemInvite(serverUrl, code, username, password, displayName, deviceName) }

    private suspend fun signIn(change: SessionChange, block: suspend () -> Account): UiError? = try {
        block()
        log.record(SessionEvent(change))
        prefs.sessionEndedNotice = false
        onSignedIn()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.toUiError()
    }

    private fun onSignedIn() {
        work.schedulePeriodicSync()
        push.onSignedIn()
        work.enqueueOutbox()
        // after an update, a restart or a reboot: make sure the wake-up for the next scheduled message exists
        scope.launch { runCatching { work.scheduleSend(engine.nextScheduledAt()) } }
    }

    /** Signs out without being tied to a screen (the screen that asked for it disappears with the session). */
    fun logoutAsync() {
        scope.launch { logout() }
    }

    /** Signs out: tells the server (push endpoint first, then the device), wipes local data, stops all background work. */
    suspend fun logout() {
        try {
            push.unregister(tellServer = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // best effort; logout revokes the device on the server anyway
        }
        engine.logout()
        log.record(SessionEvent(SessionChange.SIGNED_OUT))
        afterWipe()
    }

    private suspend fun wipe() {
        push.unregister(tellServer = false)
        engine.logout()
        afterWipe()
    }

    private fun afterWipe() {
        work.cancelAll()
        notifier.cancelAll()
        preparer.deleteAll()
        prefs.clearAccountData()
    }

    /** Suspends until the stored account has been read. */
    suspend fun awaitRestored() {
        restored.filter { it }.first()
    }

    // --- background jobs ------------------------------------------------------------------------------------------------

    /** Pull and apply new events, send the outbox, top up keys. Used by SyncWorker and PeriodicSyncWorker. */
    suspend fun runSync(trigger: SyncTrigger = SyncTrigger.PUSH): BackgroundResult {
        awaitRestored()
        if (engine.account.value == null) return BackgroundResult.DONE
        val started = System.currentTimeMillis()
        var applied = 0
        var failure: Throwable? = null
        val result = guarded {
            try {
                applied = engine.sync()
                engine.processOutbox()
                push.uploadPendingEndpoint()
                val now = System.currentTimeMillis()
                if (applied > 0 || now - prefs.lastKeyCheck > KEY_CHECK_INTERVAL_MS) {
                    engine.ensureKeys()
                    prefs.lastKeyCheck = now
                }
                prefs.lastSyncAt = System.currentTimeMillis()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
                throw e
            }
        }
        log.record(SyncCycle(trigger, applied, ErrorClass.of(failure), ErrorClass.statusOf(failure)))
        log.record(
            WorkerRun(
                if (trigger == SyncTrigger.PERIODIC) WorkerKind.PERIODIC_SYNC else WorkerKind.SYNC,
                result == BackgroundResult.RETRY, System.currentTimeMillis() - started,
            ),
        )
        return result
    }

    /** Sends everything queued; asks to be retried while the server cannot be reached. */
    suspend fun runOutbox(): BackgroundResult {
        awaitRestored()
        if (engine.account.value == null) return BackgroundResult.DONE
        val started = System.currentTimeMillis()
        val outcome = try {
            val result = engine.processOutbox()
            // scheduled messages that are still waiting need their own wake-up (this pass may have sent the one that was due)
            work.scheduleSend(engine.nextScheduledAt())
            log.record(OutboxPass(result.sent, result.failed, result.pending, result.offline))
            if (result.offline || result.pending > 0) BackgroundResult.RETRY else BackgroundResult.DONE
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            log.record(OutboxPass(0, 0, 0, false, ErrorClass.of(e)))
            if (e.isLoggedOut) BackgroundResult.DONE else BackgroundResult.RETRY
        } catch (e: Exception) {
            log.record(OutboxPass(0, 0, 0, false, ErrorClass.of(e)))
            BackgroundResult.RETRY
        }
        log.record(WorkerRun(WorkerKind.OUTBOX, outcome == BackgroundResult.RETRY, System.currentTimeMillis() - started))
        return outcome
    }

    private suspend fun guarded(block: suspend () -> Unit): BackgroundResult = try {
        block()
        BackgroundResult.DONE
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        if (e.isLoggedOut) BackgroundResult.DONE else BackgroundResult.RETRY
    } catch (e: Exception) {
        BackgroundResult.RETRY
    }

    private companion object {
        const val TAG = "SessionManager"
        const val KEY_CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000
    }
}
