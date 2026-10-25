package se.swahnchat.app.ui.screens

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.LifecycleResumeEffect
import se.swahnchat.app.R
import se.swahnchat.app.platform.security.Authenticator

/**
 * The app lock. Shows nothing but the lock: no conversation, name or message is composed behind it. Asks for the device
 * authentication each time the app comes to the front.
 */
@Composable
fun LockScreen(onUnlocked: () -> Unit) {
    val activity = LocalActivity.current as? FragmentActivity
    var prompting by remember { mutableStateOf(false) }
    val title = stringResource(R.string.lock_prompt_title)
    val subtitle = stringResource(R.string.lock_prompt_subtitle)
    fun ask() {
        if (activity == null || prompting) return
        prompting = true
        Authenticator.prompt(activity, title, subtitle) { ok ->
            prompting = false
            if (ok) onUnlocked()
        }
    }
    LifecycleResumeEffect(Unit) { ask(); onPauseOrDispose {} }
    LockContent(onUnlock = ::ask)
}

@Composable
fun LockContent(onUnlock: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp))
        Text(
            stringResource(R.string.lock_text), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp),
        )
        Button(onClick = onUnlock, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.lock_unlock)) }
    }
}
