package se.swahnchat.app.diagnostics

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** What the phone says about the app's permissions and power settings; a seam so that the screens' tests need no real phone. */
interface DeviceStatus {
    /** Whether notifications from the app are shown (the permission on Android 13+ and the system switch). */
    fun notificationsAllowed(): Boolean

    /** Whether the app may run in the background without being held back to save battery (`isIgnoringBatteryOptimizations`). */
    fun batteryUnrestricted(): Boolean

    /** True on Android 13+, where the notification permission has to be asked for. */
    fun needsNotificationPermission(): Boolean
}

@Singleton
class AndroidDeviceStatus @Inject constructor(@ApplicationContext private val context: Context) : DeviceStatus {
    override fun notificationsAllowed(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    override fun batteryUnrestricted(): Boolean = BatteryOptimization.isIgnoring(context)

    override fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
}

/**
 * The battery-optimisation exemption. Push and calls can arrive late when the system holds the app back; the exemption lets the
 * wake-up from the push distributor reach it at once. The app asks only on request (never at start), and the permission is not one a
 * Play Store listing would accept: this app is not distributed there.
 */
object BatteryOptimization {
    fun isIgnoring(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** The system's one-tap dialog for this app. */
    fun requestIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

    /** The list of apps with their battery setting; used when the one-tap dialog is not there. */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /**
     * Opens the dialog, or the settings list if the phone has no handler for it. Returns false if neither could be opened.
     * Never throws: the vendors' settings apps differ.
     */
    fun open(context: Context): Boolean {
        return listOf(requestIntent(context), settingsIntent()).any { intent ->
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (e: android.content.ActivityNotFoundException) {
                false
            } catch (e: SecurityException) {
                false
            }
        }
    }
}
