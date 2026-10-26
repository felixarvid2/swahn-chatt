package se.swahnchat.app

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import se.swahnchat.app.data.AppPrefs
import se.swahnchat.app.data.ThemeMode
import se.swahnchat.app.platform.notify.Notifier
import se.swahnchat.app.platform.security.AppLock
import se.swahnchat.app.ui.nav.AppViewModel
import se.swahnchat.app.ui.screens.LockScreen
import se.swahnchat.app.ui.screens.ShareScreen
import se.swahnchat.app.ui.screens.ShareViewModel
import se.swahnchat.app.ui.theme.SwahnChatTheme
import javax.inject.Inject

/**
 * The target for "Share" in other apps (text, pictures, videos, audio, PDFs and any other file). The only exported entry
 * besides [MainActivity], with intent filters for `SEND` and `SEND_MULTIPLE` only.
 *
 * Everything that arrives is untrusted (see [se.swahnchat.app.media.ShareIntake]). While the app lock is on and locked, or
 * while nobody is signed in, no conversation is shown: only the lock screen or a hint. The picker hands the share over in
 * memory and opens the conversation through [MainActivity], whose composer then holds the text and files.
 */
@AndroidEntryPoint
class ShareReceiverActivity : FragmentActivity() {
    @Inject lateinit var prefs: AppPrefs
    @Inject lateinit var lock: AppLock
    private val vm: ShareViewModel by viewModels()

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(se.swahnchat.app.AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch(Dispatchers.Main.immediate) {
            combine(prefs.blockScreenshots, lock.locked) { block, locked -> windowSecure(block, locked) }.collect { secure ->
                if (secure) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        vm.start(intent) // the sender's access to its files ends with this activity: copy now
        enableEdgeToEdge()
        setContent {
            val app: AppViewModel = hiltViewModel()
            val theme by app.theme.collectAsStateWithLifecycle()
            val accent by app.accent.collectAsStateWithLifecycle()
            val highContrast by app.highContrast.collectAsStateWithLifecycle()
            val dark = when (theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            SwahnChatTheme(darkTheme = dark, accent = accent, highContrast = highContrast) {
                val locked by vm.locked.collectAsStateWithLifecycle()
                val signedIn by vm.signedIn.collectAsStateWithLifecycle()
                val state by vm.state.collectAsStateWithLifecycle()
                val items by vm.items.collectAsStateWithLifecycle()
                when {
                    locked -> LockScreen(onUnlocked = { lock.unlock() })
                    signedIn == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    signedIn == false -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.share_not_signed_in), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(32.dp))
                    }
                    else -> {
                        // Chosen in the share sheet as a "direct share" target: the shortcut id is the conversation id. It is only
                        // taken if it is one of this account's conversations (the extra comes from outside).
                        val shortcutTarget = remember { intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID) }
                        LaunchedEffect(shortcutTarget, state, items) {
                            val known = shortcutTarget != null && items?.any { it.id == shortcutTarget } == true
                            if (known && vm.choose(shortcutTarget!!)) openConversation(shortcutTarget)
                        }
                        ShareScreen(
                            state = state, items = items,
                            onChoose = { id -> if (vm.choose(id)) openConversation(id) },
                            onClose = { vm.discard(); finish() },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onDestroy() {
        if (isFinishing) vm.discard()
        super.onDestroy()
    }

    private fun openConversation(conversationId: String) {
        val open = Intent(Intent.ACTION_VIEW, Notifier.deepLink(conversationId), this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(open)
        finish()
    }
}
