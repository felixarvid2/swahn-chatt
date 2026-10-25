package se.familychat.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.familychat.app.R
import se.familychat.app.data.ChatRepository
import se.familychat.app.data.UiError
import se.familychat.app.data.toUiError
import se.familychat.app.media.AttachmentPreparer
import se.familychat.app.ui.components.Avatar
import se.familychat.app.ui.components.DisappearingDialog
import se.familychat.app.ui.components.timerLabel
import se.familychat.app.ui.model.AvatarUi
import se.familychat.app.ui.model.MemberUi
import se.familychat.app.ui.model.UserUi
import se.familychat.core.client.FamilyChatEngine
import javax.inject.Inject

data class GroupInfoState(
    val name: String = "",
    val avatar: AvatarUi = AvatarUi(""),
    val members: List<MemberUi> = emptyList(),
    val meIsAdmin: Boolean = false,
    val addable: List<UserUi> = emptyList(),
    val busy: Boolean = false,
    val error: UiError? = null,
    val gone: Boolean = false,
    /** The disappearing-messages timer in seconds (0: off); only administrators change it in a group. */
    val timerSeconds: Long = 0,
)

@HiltViewModel
class GroupInfoViewModel @Inject constructor(
    savedState: SavedStateHandle,
    repo: ChatRepository,
    private val engine: FamilyChatEngine,
    private val preparer: AttachmentPreparer,
) : ViewModel() {
    val conversationId: String = checkNotNull(savedState["id"])
    private val busy = MutableStateFlow(false)
    private val error = MutableStateFlow<UiError?>(null)

    val state: StateFlow<GroupInfoState> = combine(
        repo.conversation(conversationId), repo.members(conversationId), repo.allUsers(engine.account.value?.userId), busy, error,
    ) { c, members, users, b, e ->
        if (c == null) return@combine GroupInfoState(gone = true)
        val name = c.name.orEmpty()
        val inGroup = members.mapTo(HashSet()) { it.userId }
        GroupInfoState(
            name = name, avatar = AvatarUi(name, c.avatarAttachmentId), members = members,
            meIsAdmin = members.any { it.isMe && it.isAdmin }, addable = users.filter { it.id !in inGroup }, busy = b, error = e,
        )
    }.let { base -> combine(base, repo.timer(conversationId)) { st, timer -> st.copy(timerSeconds = timer) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupInfoState())

    private fun run(block: suspend () -> Unit) {
        busy.value = true
        error.value = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                error.value = e.toUiError()
            } finally {
                busy.value = false
            }
        }
    }

    fun rename(name: String) = run { engine.renameConversation(conversationId, name) }
    fun setAvatar(uri: Uri) = run { engine.setConversationAvatar(conversationId, engine.uploadAvatar(preparer.avatarJpeg(uri), "image/jpeg")) }
    fun clearAvatar() = run { engine.clearConversationAvatar(conversationId) }
    fun addMembers(ids: List<String>) = run { engine.addMembers(conversationId, ids) }
    fun remove(userId: String) = run { engine.removeMember(conversationId, userId) }
    fun setAdmin(userId: String, admin: Boolean) = run { engine.setMemberRole(conversationId, userId, admin) }
    fun leave() = run { engine.leave(conversationId) }
    fun setTimer(seconds: Long) = run { engine.setDisappearingTimer(conversationId, seconds) }
    fun dismissError() { error.value = null }

    init {
        viewModelScope.launch { runCatching { engine.refreshDirectory() } }
    }
}

class GroupInfoCallbacks(
    val onBack: () -> Unit = {},
    val onRename: (String) -> Unit = {},
    val onPickAvatar: () -> Unit = {},
    val onClearAvatar: () -> Unit = {},
    val onAdd: (List<String>) -> Unit = {},
    val onRemove: (String) -> Unit = {},
    val onSetAdmin: (String, Boolean) -> Unit = { _, _ -> },
    val onLeave: () -> Unit = {},
    val onSetTimer: (Long) -> Unit = {},
    val onOpenMember: (String) -> Unit = {},
)

@Composable
fun GroupInfoScreen(onBack: () -> Unit, onLeft: () -> Unit, onOpenMember: (String) -> Unit, vm: GroupInfoViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::setAvatar) }
    LaunchedEffect(state.gone) { if (state.gone) onLeft() }
    GroupInfoContent(
        state,
        GroupInfoCallbacks(
            onBack = onBack, onRename = vm::rename,
            onPickAvatar = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onClearAvatar = vm::clearAvatar, onAdd = vm::addMembers, onRemove = vm::remove, onSetAdmin = vm::setAdmin,
            onLeave = vm::leave, onSetTimer = vm::setTimer, onOpenMember = onOpenMember,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoContent(state: GroupInfoState, cb: GroupInfoCallbacks, modifier: Modifier = Modifier) {
    var renaming by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var timerOpen by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<MemberUi?>(null) }
    val errorText = errorTexts()
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.group_info)) }, navigationIcon = { BackButton(cb.onBack) }) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item(key = "header") {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(state.avatar, size = 96.dp)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                        Text(state.name, style = MaterialTheme.typography.headlineSmall)
                        if (state.meIsAdmin) {
                            IconButton(onClick = { renaming = true }, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.action_rename_group))
                            }
                        }
                    }
                    if (state.meIsAdmin) {
                        Row {
                            TextButton(onClick = cb.onPickAvatar, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_change_picture)) }
                            if (state.avatar.attachmentId != null) {
                                TextButton(onClick = cb.onClearAvatar, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_remove_picture)) }
                            }
                        }
                    }
                    if (state.busy) CircularProgressIndicator(Modifier.padding(top = 8.dp).size(24.dp), strokeWidth = 2.dp)
                    state.error?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                }
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { timerOpen = true }.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Timer, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.padding(start = 16.dp)) {
                        Text(stringResource(R.string.disappearing_title), style = MaterialTheme.typography.titleMedium)
                        Text(timerLabel(state.timerSeconds), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider()
                Text(
                    pluralStringResource(R.plurals.members_count, state.members.size, state.members.size),
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                )
                if (state.meIsAdmin && state.addable.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { adding = true }.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.PersonAdd, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.add_members), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }
            items(state.members, key = { it.userId }) { member ->
                MemberRow(member, canManage = state.meIsAdmin && !member.isMe, cb = cb, onRemove = { removing = member })
            }
            item(key = "leave") {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { leaving = true }.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.leave_group), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp))
                }
            }
        }
    }

    if (timerOpen) {
        DisappearingDialog(
            current = state.timerSeconds, canChange = state.meIsAdmin,
            onSelect = { cb.onSetTimer(it); timerOpen = false }, onDismiss = { timerOpen = false },
        )
    }
    if (renaming) {
        var text by remember { mutableStateOf(state.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.action_rename_group)) },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(64) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    label = { Text(stringResource(R.string.group_name)) },
                )
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = { cb.onRename(text); renaming = false }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (adding) {
        var picked by remember { mutableStateOf(setOf<String>()) }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringResource(R.string.add_members)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(state.addable, key = { it.id }) { u ->
                        UserRow(u, onClick = { picked = if (u.id in picked) picked - u.id else picked + u.id }) {
                            Checkbox(checked = u.id in picked, onCheckedChange = null)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = picked.isNotEmpty(), onClick = { cb.onAdd(picked.toList()); adding = false }) { Text(stringResource(R.string.action_add)) }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text(stringResource(R.string.leave_group)) },
            text = { Text(stringResource(R.string.leave_group_text)) },
            confirmButton = { TextButton(onClick = { leaving = false; cb.onLeave() }) { Text(stringResource(R.string.leave_group)) } },
            dismissButton = { TextButton(onClick = { leaving = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    removing?.let { m ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.remove_member_title, m.name)) },
            text = { Text(stringResource(R.string.remove_member_text)) },
            confirmButton = { TextButton(onClick = { cb.onRemove(m.userId); removing = null }) { Text(stringResource(R.string.action_remove)) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun MemberRow(member: MemberUi, canManage: Boolean, cb: GroupInfoCallbacks, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(enabled = !member.isMe) { cb.onOpenMember(member.userId) }.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(member.avatar, size = 44.dp)
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(if (member.isMe) stringResource(R.string.member_you, member.name) else member.name, style = MaterialTheme.typography.titleMedium)
            if (member.isAdmin) Text(stringResource(R.string.role_admin), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        if (canManage) {
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.member_actions, member.name))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(if (member.isAdmin) R.string.demote_admin else R.string.promote_admin)) },
                        onClick = { menu = false; cb.onSetAdmin(member.userId, !member.isAdmin) },
                    )
                    DropdownMenuItem(text = { Text(stringResource(R.string.remove_from_group)) }, onClick = { menu = false; onRemove() })
                }
            }
        }
    }
}
