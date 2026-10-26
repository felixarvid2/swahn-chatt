package se.swahnchat.app.platform.push

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.UnifiedPush
import se.swahnchat.app.data.AppPrefs
import se.swahnchat.app.di.ApplicationScope
import se.swahnchat.app.diagnostics.ErrorClass
import se.swahnchat.app.diagnostics.EventLog
import se.swahnchat.core.client.SwahnChatEngine
import javax.inject.Inject
import javax.inject.Singleton

/** A UnifiedPush distributor app that is installed (for example ntfy). */
data class Distributor(val packageName: String, val label: String)

enum class PushStatus {
    /** No distributor app is installed. */
    NO_DISTRIBUTOR,

    /** Distributors exist but none was chosen yet. */
    NOT_CHOSEN,

    /** Registered with the distributor, waiting for its endpoint or for the server to accept it. */
    REGISTERING,

    /** The server knows our endpoint. */
    ACTIVE,
    FAILED,
}

/**
 * UnifiedPush: registers with the user's distributor, hands the resulting endpoint to the server (`PUT /devices/me/push`)
 * and removes it on logout. The push message itself carries no content; it only starts a sync (see
 * [SwahnChatPushService]).
 */
@Singleton
class PushManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: SwahnChatEngine,
    private val prefs: AppPrefs,
    @ApplicationScope private val scope: CoroutineScope,
    private val log: EventLog,
) {
    private val _status = MutableStateFlow(computeStatus())
    val status: StateFlow<PushStatus> = _status.asStateFlow()

    /** The installed distributor apps. */
    fun distributors(): List<Distributor> {
        val pm = context.packageManager
        return UnifiedPush.getDistributors(context).map { pkg ->
            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (e: PackageManager.NameNotFoundException) {
                pkg
            }
            Distributor(pkg, label)
        }
    }

    fun selectedDistributor(): String? = UnifiedPush.getSavedDistributor(context)

    /** Uses the chosen (or the only/default) distributor and registers this device with it. Call after login. */
    fun onSignedIn() {
        UnifiedPush.tryUseCurrentOrDefaultDistributor(context) { success ->
            if (success) register() else refreshStatus()
        }
    }

    /** The user picked [packageName] in the settings. */
    fun select(packageName: String) {
        UnifiedPush.saveDistributor(context, packageName)
        register()
    }

    private fun register() {
        UnifiedPush.register(context, INSTANCE, null, null)
        refreshStatus(registering = true)
    }

    /** The distributor delivered an endpoint: tell the server. */
    fun onNewEndpoint(url: String) {
        prefs.pushEndpoint = url
        scope.launch { uploadPendingEndpoint() }
    }

    /** Uploads the endpoint if the server does not have this one yet; safe to call any time (workers do). */
    suspend fun uploadPendingEndpoint() {
        val endpoint = prefs.pushEndpoint ?: return
        if (prefs.pushEndpointUploaded == endpoint) return
        if (engine.account.value == null) return
        try {
            engine.registerPush(endpoint)
            prefs.pushEndpointUploaded = endpoint
            log.record(se.swahnchat.app.diagnostics.PushRegistration(uploaded = true))
            refreshStatus()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.record(se.swahnchat.app.diagnostics.PushRegistration(uploaded = false, error = ErrorClass.of(e)))
            Log.w(TAG, "Could not send the push endpoint to the server yet: ${e.javaClass.simpleName}")
        }
    }

    fun onRegistrationFailed(reason: FailedReason) {
        Log.w(TAG, "Push registration failed: $reason")
        _status.value = PushStatus.FAILED
    }

    fun onUnregistered() {
        prefs.pushEndpoint = null
        prefs.pushEndpointUploaded = null
        refreshStatus()
    }

    /** Logout: forget the endpoint on the server (while we still have a token) and leave the distributor. */
    suspend fun unregister(tellServer: Boolean) {
        if (tellServer && prefs.pushEndpointUploaded != null) {
            try {
                engine.clearPush()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // the server drops the endpoint with the device on logout anyway
            }
        }
        if (selectedDistributor() != null) {
            UnifiedPush.unregister(context, INSTANCE)
            UnifiedPush.removeDistributor(context)
        }
        prefs.pushEndpoint = null
        prefs.pushEndpointUploaded = null
        refreshStatus()
    }

    fun refreshStatus(registering: Boolean = false) {
        _status.value = if (registering) PushStatus.REGISTERING else computeStatus()
    }

    private fun computeStatus(): PushStatus = when {
        UnifiedPush.getDistributors(context).isEmpty() -> PushStatus.NO_DISTRIBUTOR
        UnifiedPush.getSavedDistributor(context) == null -> PushStatus.NOT_CHOSEN
        prefs.pushEndpoint != null && prefs.pushEndpoint == prefs.pushEndpointUploaded -> PushStatus.ACTIVE
        else -> PushStatus.REGISTERING
    }

    companion object {
        private const val TAG = "PushManager"
        const val INSTANCE = "default"
    }
}
