package se.familychat.app.data

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** The speeds the voice bubble toggles through. */
object VoiceSpeed {
    const val NORMAL = 1f
    val STEPS = listOf(NORMAL, 1.5f, 2f)

    /** [speed] if it is one of the steps, else normal speed (a damaged preference file must not leave a voice unplayable). */
    fun of(speed: Float): Float = STEPS.firstOrNull { it == speed } ?: NORMAL

    /** 1 -> 1.5 -> 2 -> 1. */
    fun next(speed: Float): Float = STEPS[(STEPS.indexOf(of(speed)) + 1) % STEPS.size]
}

/**
 * Non-secret settings (server address typed before login, theme, push bookkeeping). Everything secret lives in the
 * encrypted database. Reads never throw: a damaged preference file just yields the defaults.
 */
@Singleton
class AppPrefs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("familychat_prefs", Context.MODE_PRIVATE)

    private val _serverUrl = MutableStateFlow(prefs.getString(KEY_SERVER, "").orEmpty())

    /** What the user typed as the server address (normalised only when used). */
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    fun setServerUrl(url: String) {
        _serverUrl.value = url
        prefs.edit().putString(KEY_SERVER, url).apply()
    }

    private val _theme = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.SYSTEM.name).orEmpty()) }.getOrDefault(ThemeMode.SYSTEM),
    )
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    fun setTheme(mode: ThemeMode) {
        _theme.value = mode
        prefs.edit().putString(KEY_THEME, mode.name).apply()
    }

    private val _voiceSpeed = MutableStateFlow(VoiceSpeed.of(prefs.getFloat(KEY_VOICE_SPEED, VoiceSpeed.NORMAL)))

    /** Playback speed of voice messages (1, 1.5 or 2), the same for all of them. */
    val voiceSpeed: StateFlow<Float> = _voiceSpeed.asStateFlow()

    fun setVoiceSpeed(speed: Float) {
        val s = VoiceSpeed.of(speed)
        _voiceSpeed.value = s
        prefs.edit().putFloat(KEY_VOICE_SPEED, s).apply()
    }

    /** The endpoint the distributor gave us. */
    var pushEndpoint: String?
        get() = prefs.getString(KEY_PUSH_ENDPOINT, null)
        set(v) { prefs.edit().putString(KEY_PUSH_ENDPOINT, v).apply() }

    /** The endpoint the server already knows (null: upload pending). */
    var pushEndpointUploaded: String?
        get() = prefs.getString(KEY_PUSH_UPLOADED, null)
        set(v) { prefs.edit().putString(KEY_PUSH_UPLOADED, v).apply() }

    var lastKeyCheck: Long
        get() = prefs.getLong(KEY_KEY_CHECK, 0L)
        set(v) { prefs.edit().putLong(KEY_KEY_CHECK, v).apply() }

    /** Set when the server ended our session (revoked device, changed password elsewhere); shown once on the login screen. */
    var sessionEndedNotice: Boolean
        get() = prefs.getBoolean(KEY_SESSION_ENDED, false)
        set(v) { prefs.edit().putBoolean(KEY_SESSION_ENDED, v).apply() }

    var notificationPermissionAsked: Boolean
        get() = prefs.getBoolean(KEY_NOTIF_ASKED, false)
        set(v) { prefs.edit().putBoolean(KEY_NOTIF_ASKED, v).apply() }

    private companion object {
        const val KEY_SERVER = "server_url"
        const val KEY_THEME = "theme"
        const val KEY_VOICE_SPEED = "voice_speed"
        const val KEY_PUSH_ENDPOINT = "push_endpoint"
        const val KEY_PUSH_UPLOADED = "push_endpoint_uploaded"
        const val KEY_KEY_CHECK = "last_key_check"
        const val KEY_SESSION_ENDED = "session_ended"
        const val KEY_NOTIF_ASKED = "notif_permission_asked"
    }
}
