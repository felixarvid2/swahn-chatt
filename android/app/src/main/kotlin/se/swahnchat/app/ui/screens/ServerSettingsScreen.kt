package se.swahnchat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.swahnchat.app.R
import se.swahnchat.app.data.ServerCheck
import se.swahnchat.app.ui.components.isInsecureUrl
import se.swahnchat.app.ui.theme.LocalStatusColors

@Composable
fun ServerSettingsScreen(onBack: () -> Unit, vm: ServerSettingsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    ServerSettingsContent(state, onBack, vm::setUrl, vm::test)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSettingsContent(
    state: ServerSettingsUiState,
    onBack: () -> Unit,
    onUrl: (String) -> Unit,
    onTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.connection_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        ) {
            Field(
                state.url, onUrl, R.string.server_address, KeyboardType.Uri, supporting = R.string.server_address_hint,
                capitalization = KeyboardCapitalization.None,
            )
            Button(onClick = onTest, enabled = !state.testing && state.url.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                if (state.testing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                else Text(stringResource(R.string.test_connection))
            }
            state.result?.let { ResultCard(it) }
            if (isInsecureUrl(state.url)) {
                Notice(
                    Icons.Filled.Warning, stringResource(R.string.insecure_url_title), stringResource(R.string.insecure_url_text),
                    error = true,
                )
            } else {
                Text(stringResource(R.string.tls_explainer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ResultCard(result: ServerCheck) {
    when (result) {
        is ServerCheck.Reachable -> Notice(
            Icons.Filled.CheckCircle,
            stringResource(R.string.connection_ok),
            stringResource(if (result.encrypted) R.string.connection_ok_encrypted else R.string.connection_ok_unencrypted, result.normalizedUrl),
            error = !result.encrypted,
        )
        ServerCheck.InvalidUrl -> Notice(Icons.Filled.ErrorOutline, stringResource(R.string.connection_failed), stringResource(R.string.check_invalid_url), error = true)
        ServerCheck.InsecureNotAllowed -> Notice(Icons.Filled.ErrorOutline, stringResource(R.string.connection_failed), stringResource(R.string.error_insecure_url), error = true)
        ServerCheck.Unreachable -> Notice(Icons.Filled.ErrorOutline, stringResource(R.string.connection_failed), stringResource(R.string.check_unreachable), error = true)
        ServerCheck.TlsProblem -> Notice(Icons.Filled.ErrorOutline, stringResource(R.string.connection_failed), stringResource(R.string.check_tls_problem), error = true)
        is ServerCheck.UnexpectedAnswer -> Notice(Icons.Filled.ErrorOutline, stringResource(R.string.connection_failed), stringResource(R.string.check_unexpected, result.status), error = true)
    }
}

@Composable
internal fun Notice(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String, error: Boolean) {
    val container = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val content = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(container).padding(12.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = if (error) LocalStatusColors.current.failed else content)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = content)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = content)
        }
    }
}
