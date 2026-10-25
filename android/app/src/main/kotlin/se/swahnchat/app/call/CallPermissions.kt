package se.swahnchat.app.call

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Whether an incoming call may take over the screen. Android 14 lets the user switch the permission off for apps that
 * were not installed with it granted; before that it is always on. The notification is posted with a full-screen intent
 * either way (the system needs one for a call-style notification); this only decides whether the settings offer the switch.
 */
fun canShowFullScreenCalls(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
        context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

/** Opens the system page for "full-screen notifications" of this app (Android 14+); the app's own settings page before that. */
fun openFullScreenIntentSettings(context: Context) {
    val packageUri = Uri.fromParts("package", context.packageName, null)
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
    }
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
