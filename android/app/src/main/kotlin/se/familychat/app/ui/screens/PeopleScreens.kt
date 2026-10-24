package se.familychat.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import se.familychat.app.R
import se.familychat.app.data.ChatRepository
import se.familychat.app.data.UiError
import se.familychat.app.data.toUiError
import se.familychat.app.media.AttachmentPreparer
import se.familychat.app.ui.components.Avatar
import se.familychat.app.ui.model.AvatarUi
import se.familychat.app.ui.model.UserUi
import se.familychat.core.client.FamilyChatEngine
import javax.inject.Inject

private fun List<UserUi>.filterBy(query: String): List<UserUi> {
    val q = query.trim().lowercase()
    return if (q.isEmpty()) this else filter { it.name.lowercase().contains(q) || it.username.lowercase().contains(q) }
}

// =====================================================================================================================
// New conversation
// =====================================================================================================================

@HiltViewModel
class NewConversationViewModel @Inject constructor(
    repo: ChatRepository,
    private val engine: FamilyChatEngine,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val _busy = MutableStateFlow(false)
    private val _error = MutableStateFlow<UiError?>(null)

    val state: StateFlow<NewConversationState> = combine(
        repo.allUsers(engine.account.value?.userId), query, _busy, _error,
    ) { users, q, busy, error -> NewConversationState(users.filterBy(q), q, busy, error, loaded = true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NewConversationState())

    init {
        // pick up people who joined since the last sync
        viewModelScope.launch { runCatching { engine.refreshDirectory() } }
    }

    fun setQuery(v: String) { query.value = v }

    fun open(userId: String, onReady: (String) -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                onReady(engine.createDirect(userId).id)
            } catch (e: Exception) {
                _error.value = e.toUiError()
            } finally {
                _busy.value = false
            }
        }
    }
}

data class NewConversationState(
    val users: List<UserUi> = emptyList(),
    val query: String = "",
    val busy: Boolean = false,
    val error: UiError? = null,
    val loaded: Boolean = false,
)

@Composable
fun NewConversationScreen(onBack: () -> Unit, onNewGroup: () -> Unit, onOpen: (String) -> Unit, vm: NewConversationViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    NewConversationContent(state, onBack, onNewGroup, vm::setQuery, { id -> vm.open(id, onOpen) })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewConversationContent(
    state: NewConversationState,
    onBack: () -> Unit,
    onNewGroup: () -> Unit,
    onQuery: (String) -> Unit,
    onUser: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val errorText = errorTexts()
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.new_conversation)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SearchField(state.query, onQuery)
            state.error?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
            if (state.busy) CircularProgressIndicator(Modifier.padding(16.dp).align(Alignment.CenterHorizontally))
            LazyColumn(Modifier.fillMaxSize()) {
                item(key = "new-group") {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onNewGroup).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer, androidx.compose.foundation.shape.CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Filled.Group, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                        Text(stringResource(R.string.new_group), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp))
                    }
                }
                items(state.users, key = { it.id }) { u -> UserRow(u, onClick = { onUser(u.id) }) }
                if (state.loaded && state.users.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            stringResource(R.string.no_users_found), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
    }
}

@Composable
internal fun SearchField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        placeholder = { Text(stringResource(R.string.search_people)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onChange("") }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_clear))
                }
            }
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
internal fun UserRow(user: UserUi, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(user.avatar, size = 48.dp)
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(user.name, style = MaterialTheme.typography.titleMedium)
            Text("@${user.username}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

// =====================================================================================================================
// Group creation
// =====================================================================================================================

data class GroupCreateState(
    val name: String = "",
    val avatarUri: Uri? = null,
    val users: List<UserUi> = emptyList(),
    val query: String = "",
    val selected: Set<String> = emptySet(),
    val busy: Boolean = false,
    val error: UiError? = null,
    val showNameError: Boolean = false,
) {
    val canCreate: Boolean get() = name.trim().length in 1..64 && selected.isNotEmpty() && !busy
}

@HiltViewModel
class GroupCreateViewModel @Inject constructor(
    repo: ChatRepository,
    private val engine: FamilyChatEngine,
    private val preparer: AttachmentPreparer,
) : ViewModel() {
    private val name = MutableStateFlow("")
    private val avatar = MutableStateFlow<Uri?>(null)
    private val query = MutableStateFlow("")
    private val selected = MutableStateFlow<Set<String>>(emptySet())
    private val busy = MutableStateFlow(false)
    private val error = MutableStateFlow<UiError?>(null)

    private val form = combine(name, avatar, query, selected) { n, a, q, s -> arrayOf<Any?>(n, a, q, s) }

    val state: StateFlow<GroupCreateState> = combine(repo.allUsers(engine.account.value?.userId), form, busy, error) { users, f, b, e ->
        @Suppress("UNCHECKED_CAST")
        GroupCreateState(f[0] as String, f[1] as Uri?, users.filterBy(f[2] as String), f[2] as String, f[3] as Set<String>, b, e)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupCreateState())

    fun setName(v: String) { name.value = v.take(64) }
    fun setAvatar(uri: Uri?) { avatar.value = uri }
    fun setQuery(v: String) { query.value = v }
    fun toggle(id: String) { selected.update { if (id in it) it - id else it + id } }

    fun create(onCreated: (String) -> Unit) {
        val s = state.value
        if (!s.canCreate) return
        busy.value = true
        error.value = null
        viewModelScope.launch {
            try {
                val avatarId = avatar.value?.let { engine.uploadAvatar(preparer.avatarJpeg(it), "image/jpeg") }
                onCreated(engine.createGroup(s.name, s.selected.toList(), avatarId).id)
            } catch (e: Exception) {
                error.value = e.toUiError()
            } finally {
                busy.value = false
            }
        }
    }
}

@Composable
fun GroupCreateScreen(onBack: () -> Unit, onCreated: (String) -> Unit, vm: GroupCreateViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { vm.setAvatar(it) }
    GroupCreateContent(
        state, onBack, vm::setName, { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        { vm.setAvatar(null) }, vm::setQuery, vm::toggle, { vm.create(onCreated) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupCreateContent(
    state: GroupCreateState,
    onBack: () -> Unit,
    onName: (String) -> Unit,
    onPickAvatar: () -> Unit,
    onClearAvatar: () -> Unit,
    onQuery: (String) -> Unit,
    onToggle: (String) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val errorText = errorTexts()
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.new_group)) }, navigationIcon = { BackButton(onBack) }) },
        bottomBar = {
            Column(Modifier.padding(16.dp)) {
                state.error?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp)) }
                Button(onClick = onCreate, enabled = state.canCreate, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Text(stringResource(R.string.create_group))
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item(key = "head") {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    AvatarPicker(state.name, state.avatarUri, onPickAvatar, onClearAvatar)
                    OutlinedTextField(
                        value = state.name, onValueChange = onName, singleLine = true,
                        label = { Text(stringResource(R.string.group_name)) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.weight(1f).padding(start = 16.dp),
                    )
                }
                Text(
                    pluralSelected(state.selected.size), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp),
                )
                SearchField(state.query, onQuery)
            }
            items(state.users, key = { it.id }) { u ->
                UserRow(u, onClick = { onToggle(u.id) }) {
                    Checkbox(checked = u.id in state.selected, onCheckedChange = null)
                }
            }
        }
    }
}

@Composable
private fun pluralSelected(n: Int): String = androidx.compose.ui.res.pluralStringResource(R.plurals.selected_members, n, n)

@Composable
internal fun AvatarPicker(name: String, uri: Uri?, onPick: () -> Unit, onClear: () -> Unit) {
    Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
        val label = stringResource(if (uri == null) R.string.action_choose_picture else R.string.action_change_picture)
        Box(
            Modifier.size(72.dp).clickable(onClickLabel = label, onClick = onPick),
            contentAlignment = Alignment.Center,
        ) {
            if (uri != null) {
                coil.compose.AsyncImage(
                    model = uri, contentDescription = label, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.size(72.dp).clip(androidx.compose.foundation.shape.CircleShape),
                )
            } else {
                Box(
                    Modifier.size(72.dp).background(MaterialTheme.colorScheme.secondaryContainer, androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Group, contentDescription = label, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(32.dp))
                }
            }
        }
        if (uri != null) {
            IconButton(onClick = onClear, modifier = Modifier.align(Alignment.TopEnd).size(48.dp)) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_remove_picture), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}
