package se.swahnchat.app.platform.security

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Device authentication for the app lock: a strong biometric (fingerprint, face) or the screen lock (PIN, pattern,
 * password). `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` is not supported by the system before Android 11, so on Android 9 and
 * 10 a weak-or-better biometric is accepted instead (a fingerprint sensor of class 3 counts), on Android 8 only the screen lock.
 */
object Authenticator {
    fun authenticators(sdk: Int = Build.VERSION.SDK_INT): Int = when {
        sdk >= Build.VERSION_CODES.R -> BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        sdk >= Build.VERSION_CODES.P -> BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        else -> DEVICE_CREDENTIAL
    }

    /** True if the phone has a screen lock or biometric the lock can use (it cannot be turned on otherwise). */
    fun isAvailable(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(authenticators()) == BiometricManager.BIOMETRIC_SUCCESS

    fun prompt(activity: FragmentActivity, title: String, subtitle: String?, onResult: (success: Boolean) -> Unit) {
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(false)
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .apply { if (subtitle != null) setSubtitle(subtitle) }
            .setAllowedAuthenticators(authenticators())
            .build()
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback).authenticate(info)
    }
}
