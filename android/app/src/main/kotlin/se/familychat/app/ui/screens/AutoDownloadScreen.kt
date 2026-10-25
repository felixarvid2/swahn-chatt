package se.familychat.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import se.familychat.app.R
import se.familychat.app.data.AppPrefs
import se.familychat.app.data.AutoDownloadMode
import se.familychat.app.data.AutoDownloadSettings
import javax.inject.Inject

@HiltViewModel
class AutoDownloadViewModel @Inject constructor(private val prefs: AppPrefs) : ViewModel() {
    val settings: StateFlow<AutoDownloadSettings> = prefs.autoDownload
    fun set(kind: String, mode: AutoDownloadMode) = prefs.setAutoDownload(kind, mode)
}

@Composable
fun AutoDownloadScreen(onBack: () -> Unit, vm: AutoDownloadViewModel = hiltViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    AutoDownloadContent(settings, onBack, vm::set)
}

internal fun autoDownloadLabel(mode: AutoDownloadMode): Int = when (mode) {
    AutoDownloadMode.ALWAYS -> R.string.auto_download_always
    AutoDownloadMode.WIFI_ONLY -> R.string.auto_download_wifi
    AutoDownloadMode.NEVER -> R.string.auto_download_never
}

/** The kinds in the order of the screen: attachment kind (as in messages) and its title. */
private val KINDS = listOf(
    "image" to R.string.auto_download_images,
    "voice" to R.string.auto_download_voice,
    "video" to R.string.auto_download_video,
    "file" to R.string.auto_download_files,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoDownloadContent(
    settings: AutoDownloadSettings,
    onBack: () -> Unit,
    onChange: (kind: String, mode: AutoDownloadMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.auto_download)) }, navigationIcon = { BackButton(onBack) }) },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            Text(
                stringResource(R.string.auto_download_explainer), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp),
            )
            for ((kind, title) in KINDS) {
                Text(
                    stringResource(title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                )
                Column(Modifier.selectableGroup()) {
                    for (mode in AutoDownloadMode.entries) {
                        val selected = settings.modeFor(kind) == mode
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected, role = Role.RadioButton) { onChange(kind, mode) }
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Text(stringResource(autoDownloadLabel(mode)), modifier = Modifier.padding(start = 16.dp))
                        }
                    }
                }
            }
            Text(
                stringResource(R.string.auto_download_footer), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp),
            )
        }
    }
}
