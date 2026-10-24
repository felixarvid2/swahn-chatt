package se.familychat.app

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import se.familychat.app.data.AppPrefs
import se.familychat.app.platform.security.AppLock
import javax.inject.Inject
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import se.familychat.app.data.ThemeMode
import se.familychat.app.ui.components.LocalAvatarLoader
import se.familychat.app.ui.nav.AppRoot
import se.familychat.app.ui.nav.AppViewModel
import se.familychat.app.ui.theme.FamilyChatTheme

@AndroidEntryPoint
class MainActivity : FragmentActivity() { // FragmentActivity: BiometricPrompt needs one
    @Inject lateinit var prefs: AppPrefs
    @Inject lateinit var lock: AppLock

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // FLAG_SECURE (no screenshots, blank preview in the recent apps list): by the setting, and always while locked
        lifecycleScope.launch(Dispatchers.Main.immediate) {
            combine(prefs.blockScreenshots, lock.locked) { block, locked -> windowSecure(block, locked) }.collect { secure ->
                if (secure) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        enableEdgeToEdge()
        setContent {
            val vm: AppViewModel = hiltViewModel()
            val theme by vm.theme.collectAsStateWithLifecycle()
            val dark = when (theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            val loader: suspend (String) -> java.io.File? = remember(vm) { { id -> vm.avatarFile(id) } }
            FamilyChatTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalAvatarLoader provides loader) { AppRoot(vm) }
            }
        }
    }

    /** Everything the app starts for a result or to show something (picker, file viewer, share sheet) is not "leaving the app". */
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        lock.expectExternalReturn()
        super.startActivityForResult(intent, requestCode, options)
    }
}

/** Whether the window is secure (FLAG_SECURE): the user asked for it, or the app is locked. */
fun windowSecure(blockScreenshots: Boolean, locked: Boolean): Boolean = blockScreenshots || locked
