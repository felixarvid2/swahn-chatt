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

/** What a message notification reveals (also on the lock screen). */
enum class NotificationPrivacy {
    /** Sender and message text (default). */
    FULL,

    /** Who wrote, but not what: every message reads "Nytt meddelande". */
    NAME_ONLY,

    /** Neither: "Nytt meddelande" from the app. */
    HIDDEN,
}

/** How long the app may stay in the background before it asks to be unlocked again. */
enum class LockTimeout(val millis: Long) {
    IMMEDIATELY(0),
    MINUTE_1(60_000),
    MINUTES_5(5 * 60_000),
    MINUTES_30(30 * 60_000),
}

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

    private inline fun <reified E : Enum<E>> enumPref(key: String, default: E): E =
        runCatching { enumValueOf<E>(prefs.getString(key, default.name).orEmpty()) }.getOrDefault(default)

    private val _notificationPrivacy = MutableStateFlow(enumPref(KEY_NOTIF_PRIVACY, NotificationPrivacy.FULL))
    val notificationPrivacy: StateFlow<NotificationPrivacy> = _notificationPrivacy.asStateFlow()

    fun setNotificationPrivacy(value: NotificationPrivacy) {
        _notificationPrivacy.value = value
        prefs.edit().putString(KEY_NOTIF_PRIVACY, value.name).apply()
    }

    private val _blockScreenshots = MutableStateFlow(prefs.getBoolean(KEY_BLOCK_SCREENSHOTS, false))

    /** FLAG_SECURE on the window: no screenshots, no preview in the recent apps list. */
    val blockScreenshots: StateFlow<Boolean> = _blockScreenshots.asStateFlow()

    fun setBlockScreenshots(value: Boolean) {
        _blockScreenshots.value = value
        prefs.edit().putBoolean(KEY_BLOCK_SCREENSHOTS, value).apply()
    }

    private val _appLockEnabled = MutableStateFlow(prefs.getBoolean(KEY_LOCK_ENABLED, false))
    val appLockEnabled: StateFlow<Boolean> = _appLockEnabled.asStateFlow()

    fun setAppLockEnabled(value: Boolean) {
        _appLockEnabled.value = value
        prefs.edit().putBoolean(KEY_LOCK_ENABLED, value).apply()
    }

    private val _lockTimeout = MutableStateFlow(enumPref(KEY_LOCK_TIMEOUT, LockTimeout.IMMEDIATELY))
    val lockTimeout: StateFlow<LockTimeout> = _lockTimeout.asStateFlow()

    fun setLockTimeout(value: LockTimeout) {
        _lockTimeout.value = value
        prefs.edit().putString(KEY_LOCK_TIMEOUT, value.name).apply()
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
        const val KEY_NOTIF_PRIVACY = "notification_privacy"
        const val KEY_BLOCK_SCREENSHOTS = "block_screenshots"
        const val KEY_LOCK_ENABLED = "app_lock_enabled"
        const val KEY_LOCK_TIMEOUT = "app_lock_timeout"
        const val KEY_NOTIF_ASKED = "notif_permission_asked"
    }
}
