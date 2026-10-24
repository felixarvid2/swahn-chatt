package se.familychat.app

import android.os.Bundle
import androidx.activity.ComponentActivity
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
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
}
