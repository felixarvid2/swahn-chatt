package se.swahnchat.app.ui.screens

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import se.swahnchat.app.data.UiError
import se.swahnchat.app.data.toUiError
import se.swahnchat.app.qr.InviteQr
import se.swahnchat.core.client.SwahnChatEngine
import se.swahnchat.core.model.AdminStats
import se.swahnchat.core.model.AdminUser
import se.swahnchat.core.model.AuditEvent
import se.swahnchat.core.model.CreatedInvitation
import se.swahnchat.core.model.Invitation
import javax.inject.Inject

enum class AdminTab { USERS, INVITATIONS, STATUS, LOG }

/** A part of the screen that is read from the server: not asked yet, being read, read, or the server could not be asked. */
sealed interface Loadable<out T> {
    data object Idle : Loadable<Nothing>
    data object Loading : Loadable<Nothing>
    data object Failed : Loadable<Nothing>
    data class Ready<T>(val value: T) : Loadable<T>
}

/**
 * The code of a password reset, shown once. It lives in this view model's memory only: not in a saved state, not in a preference, not in
 * the database and not in the log; [AdminViewModel.dismissResetCode] and the end of the view model drop it.
 */
@Immutable
class ResetCodeUi(val userName: String, val code: String, val expiresAt: Long)

@Immutable
data class AdminUiState(
    val tab: AdminTab = AdminTab.USERS,
    val me: String = "",
    val users: Loadable<List<AdminUser>> = Loadable.Idle,
    val invitations: Loadable<List<Invitation>> = Loadable.Idle,
    val stats: Loadable<AdminStats> = Loadable.Idle,
    val audit: Loadable<List<AuditEvent>> = Loadable.Idle,
    val auditHasMore: Boolean = false,
    val auditLoadingMore: Boolean = false,
    val busy: Boolean = false,
    val error: UiError? = null,
    val resetCode: ResetCodeUi? = null,
    val invitation: CreatedInvitation? = null,
    val invitationQr: String? = null,
)

/** Rows per page of the audit log. */
const val AUDIT_PAGE = 50

@HiltViewModel
class AdminViewModel @Inject constructor(private val engine: SwahnChatEngine) : ViewModel() {
    private val _state = MutableStateFlow(AdminUiState(me = engine.account.value?.userId.orEmpty()))
    val state: StateFlow<AdminUiState> = _state.asStateFlow()

    init {
        loadUsers()
    }

    fun selectTab(tab: AdminTab) {
        _state.update { it.copy(tab = tab, error = null) }
        when (tab) {
            AdminTab.USERS -> if (_state.value.users == Loadable.Idle) loadUsers()
            AdminTab.INVITATIONS -> if (_state.value.invitations == Loadable.Idle) loadInvitations()
            AdminTab.STATUS -> loadStats()
            AdminTab.LOG -> {
                if (_state.value.users == Loadable.Idle) loadUsers() // the names of the people in the log
                if (_state.value.audit == Loadable.Idle) loadAudit()
            }
        }
    }

    fun loadUsers() {
        _state.update { it.copy(users = if (it.users is Loadable.Ready) it.users else Loadable.Loading) }
        viewModelScope.launch {
            _state.update { it.copy(users = fetch { engine.adminUsers() }) }
        }
    }

    fun loadInvitations() {
        _state.update { it.copy(invitations = if (it.invitations is Loadable.Ready) it.invitations else Loadable.Loading) }
        viewModelScope.launch {
            _state.update { it.copy(invitations = fetch { engine.invitations().sortedByDescending { i -> i.createdAt } }) }
        }
    }

    fun loadStats() {
        _state.update { it.copy(stats = if (it.stats is Loadable.Ready) it.stats else Loadable.Loading) }
        viewModelScope.launch {
            _state.update { it.copy(stats = fetch { engine.adminStats() }) }
        }
    }

    fun loadAudit() {
        _state.update { it.copy(audit = if (it.audit is Loadable.Ready) it.audit else Loadable.Loading) }
        viewModelScope.launch {
            val page = fetch { engine.adminAudit(null, AUDIT_PAGE) }
            _state.update { it.copy(audit = page, auditHasMore = (page as? Loadable.Ready)?.value?.size == AUDIT_PAGE) }
        }
    }

    /** The next, older page: its `before_id` is the smallest id seen so far. */
    fun loadMoreAudit() {
        val current = (_state.value.audit as? Loadable.Ready)?.value ?: return
        if (_state.value.auditLoadingMore || current.isEmpty()) return
        _state.update { it.copy(auditLoadingMore = true) }
        viewModelScope.launch {
            try {
                val older = engine.adminAudit(current.minOf { it.id }, AUDIT_PAGE)
                _state.update {
                    it.copy(audit = Loadable.Ready(current + older), auditHasMore = older.size == AUDIT_PAGE, auditLoadingMore = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(auditLoadingMore = false, error = e.toUiError()) }
            }
        }
    }

    private suspend fun <T> fetch(block: suspend () -> T): Loadable<T> = try {
        Loadable.Ready(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Loadable.Failed
    }

    /** Runs one change on the server; the lists that it affects are read again afterwards. */
    private fun act(after: () -> Unit = ::loadUsers, block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                block()
                after()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.toUiError()) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun suspendUser(userId: String) = act { engine.suspendUser(userId) }
    fun unsuspendUser(userId: String) = act { engine.unsuspendUser(userId) }

    /** `409 last_admin` shows as [UiError.LAST_ADMIN]. */
    fun setAdmin(userId: String, admin: Boolean) = act { engine.setUserAdmin(userId, admin) }
    fun revokeDevice(userId: String, deviceId: String) = act { engine.revokeUserDevice(userId, deviceId) }
    /** [adminPassword] is the administrator's own password, sent once and not kept. */
    fun removeUser(userId: String, adminPassword: String) = act { engine.removeUser(userId, adminPassword) }

    /** Asks the server for a reset code and keeps it for the dialog only. */
    fun createResetCode(user: AdminUser, adminPassword: String) = act(after = {}) {
        val created = engine.issuePasswordReset(user.id, adminPassword)
        _state.update { it.copy(resetCode = ResetCodeUi(user.displayName, created.code, created.expiresAt)) }
    }

    fun dismissResetCode() = _state.update { it.copy(resetCode = null) }

    fun createInvitation(note: String, hours: Int, admin: Boolean) = act(after = ::loadInvitations) {
        val inv = engine.createInvitation(note.trim().ifEmpty { null }, hours, admin)
        val server = engine.account.value?.serverUrl
        _state.update { it.copy(invitation = inv, invitationQr = server?.let { s -> InviteQr.payload(s, inv.code) }) }
    }

    fun dismissInvitation() = _state.update { it.copy(invitation = null, invitationQr = null) }
    fun revokeInvitation(id: String) = act(after = ::loadInvitations) { engine.revokeInvitation(id) }
    fun dismissError() = _state.update { it.copy(error = null) }

    override fun onCleared() {
        // the code is not kept: the state goes with the view model, and nothing else ever held it
        _state.update { it.copy(resetCode = null, invitation = null, invitationQr = null) }
    }
}
