package se.familychat.app.ui.nav

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import se.familychat.app.R
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import se.familychat.app.ui.screens.LockScreen
import androidx.activity.ComponentActivity
import android.content.Intent
import androidx.activity.compose.LocalActivity
import androidx.core.util.Consumer
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import se.familychat.app.data.SessionState
import se.familychat.app.ui.components.InsecureBanner
import se.familychat.app.ui.screens.ContactScreen
import se.familychat.app.ui.screens.ConversationNav
import se.familychat.app.ui.screens.ConversationScreen
import se.familychat.app.ui.screens.ConversationsScreen
import se.familychat.app.ui.screens.GroupCreateScreen
import se.familychat.app.ui.screens.GroupInfoScreen
import se.familychat.app.ui.screens.LoginScreen
import se.familychat.app.ui.screens.MediaViewerScreen
import se.familychat.app.ui.screens.NewConversationScreen
import se.familychat.app.ui.screens.ServerSettingsScreen
import se.familychat.app.ui.screens.SearchScreen
import se.familychat.app.ui.screens.SettingsScreen

private const val JUMP_KEY = "jump_to_message"

object Routes {
    const val SERVER = "server"
    const val LOGIN = "login"
    const val CONVERSATIONS = "conversations"
    const val CONVERSATION = "conversation/{id}?jump={jump}"
    const val SEARCH = "search?conversation={conversation}"
    const val NEW = "new"
    const val NEW_GROUP = "new-group"
    const val GROUP_INFO = "group/{id}"
    const val SETTINGS = "settings"
    const val CONTACT = "contact/{userId}"
    const val MEDIA = "media/{messageId}/{attachmentId}"

    /** [jump]: a message to scroll to on opening (from the search). */
    fun conversation(id: String, jump: String? = null) = if (jump == null) "conversation/$id" else "conversation/$id?jump=$jump"
    fun search(conversationId: String? = null) = if (conversationId == null) "search" else "search?conversation=$conversationId"
    fun groupInfo(id: String) = "group/$id"
    fun contact(userId: String) = "contact/$userId"
    fun media(messageId: String, attachmentId: String) = "media/$messageId/$attachmentId"
}

/** The window: a persistent red strip on top while the connection is insecure, then the app. */
@Composable
fun AppFrame(insecure: Boolean, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (insecure) {
            // The strip sits under the status bar; screens below must not pad for the status bar a second time.
            InsecureBanner(Modifier.windowInsetsPadding(WindowInsets.statusBars))
        }
        Box(Modifier.weight(1f).then(if (insecure) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier)) { content() }
    }
}

@Composable
fun AppRoot(vm: AppViewModel = hiltViewModel()) {
    val session by vm.session.collectAsStateWithLifecycle()
    val insecure by vm.insecure.collectAsStateWithLifecycle()
    val locked by vm.locked.collectAsStateWithLifecycle()
    // kept above the NavHost so that the back stack survives the lock screen replacing the app
    val nav = rememberNavController()
    // singleTop: a tap on a notification while the app is open arrives as a new intent; the NavHost does not look at those.
    // While the app is locked the link waits until the app is shown again.
    var pendingLink by remember { mutableStateOf<Intent?>(null) }
    val lockedNow by rememberUpdatedState(locked)
    val activity = LocalActivity.current as? ComponentActivity
    DisposableEffect(activity, nav) {
        val listener = Consumer<Intent> { intent -> if (lockedNow) pendingLink = intent else nav.handleDeepLink(intent) }
        activity?.addOnNewIntentListener(listener)
        onDispose { activity?.removeOnNewIntentListener(listener) }
    }
    AppFrame(insecure) {
        when {
            session == SessionState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            session == SessionState.StorageUnavailable -> StorageUnavailableScreen(onRetry = vm::retryStorage)
            locked -> LockScreen(onUnlocked = vm::unlock)
            else -> {
                AppNavHost(nav, signedIn = session is SessionState.SignedIn)
                LaunchedEffect(pendingLink) {
                    pendingLink?.let { nav.handleDeepLink(it); pendingLink = null }
                }
            }
        }
    }
}

@Composable
private fun AppNavHost(nav: androidx.navigation.NavHostController, signedIn: Boolean) {
    // fixed at first composition; later sign-ins and sign-outs navigate (below) instead of rebuilding the graph
    val start = remember { if (signedIn) Routes.CONVERSATIONS else Routes.LOGIN }
    // Follow the session: a revoked device or a logout leads to the login screen, a sign-in to the conversations.
    LaunchedEffect(signedIn) {
        val route = nav.currentDestination?.route
        if (!signedIn && route != Routes.LOGIN && route != Routes.SERVER) {
            nav.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } }
        } else if (signedIn && (route == Routes.LOGIN || route == Routes.SERVER)) {
            nav.navigate(Routes.CONVERSATIONS) { popUpTo(0) { inclusive = true } }
        }
    }
    NavHost(nav, startDestination = start) {
        composable(Routes.SERVER) { ServerSettingsScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.LOGIN) { LoginScreen(onServerSettings = { nav.navigate(Routes.SERVER) }) }
        composable(Routes.CONVERSATIONS) {
            ConversationsScreen(
                onOpen = { nav.navigate(Routes.conversation(it)) },
                onNew = { nav.navigate(Routes.NEW) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onSearch = { nav.navigate(Routes.search()) },
            )
        }
        composable(
            Routes.SEARCH,
            arguments = listOf(navArgument("conversation") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            val inConversation = entry.arguments?.getString("conversation") != null
            SearchScreen(
                onBack = { nav.popBackStack() },
                onOpenHit = { conversationId, messageId ->
                    if (inConversation) {
                        // back to the open conversation, which scrolls to the message
                        nav.previousBackStackEntry?.savedStateHandle?.set(JUMP_KEY, messageId)
                        nav.popBackStack()
                    } else {
                        nav.navigate(Routes.conversation(conversationId, messageId))
                    }
                },
            )
        }
        composable(
            Routes.CONVERSATION,
            arguments = listOf(
                navArgument("id") { type = NavType.StringType },
                navArgument("jump") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
            deepLinks = listOf(navDeepLink { uriPattern = "familychat://conversation/{id}" }),
        ) { entry ->
            // a message to show: from the global search (route argument) or from the search inside this conversation (result of that screen)
            val fromArgument = remember(entry) { entry.arguments?.getString("jump") }
            val handled = remember(entry) { mutableStateOf(false) }
            val fromSearch by entry.savedStateHandle.getStateFlow<String?>(JUMP_KEY, null).collectAsStateWithLifecycle()
            ConversationScreen(
                jumpTo = fromSearch ?: fromArgument.takeIf { !handled.value },
                onJumpHandled = { handled.value = true; entry.savedStateHandle[JUMP_KEY] = null },
                nav = ConversationNav(
                    onSearch = { nav.navigate(Routes.search(it)) },
                    onBack = { nav.popBackStack() },
                    onOpenMedia = { m, a -> nav.navigate(Routes.media(m, a)) },
                    onOpenGroupInfo = { nav.navigate(Routes.groupInfo(it)) },
                    onOpenContact = { nav.navigate(Routes.contact(it)) },
                ),
            )
        }
        composable(Routes.NEW) {
            NewConversationScreen(
                onBack = { nav.popBackStack() },
                onNewGroup = { nav.navigate(Routes.NEW_GROUP) },
                onOpen = { id -> nav.navigate(Routes.conversation(id)) { popUpTo(Routes.CONVERSATIONS) } },
            )
        }
        composable(Routes.NEW_GROUP) {
            GroupCreateScreen(
                onBack = { nav.popBackStack() },
                onCreated = { id -> nav.navigate(Routes.conversation(id)) { popUpTo(Routes.CONVERSATIONS) } },
            )
        }
        composable(Routes.GROUP_INFO, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
            GroupInfoScreen(
                onBack = { nav.popBackStack() },
                onLeft = { nav.popBackStack(Routes.CONVERSATIONS, inclusive = false) },
                onOpenMember = { nav.navigate(Routes.contact(it)) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() }, onOpenSafety = { nav.navigate(Routes.contact(it)) })
        }
        composable(Routes.CONTACT, arguments = listOf(navArgument("userId") { type = NavType.StringType })) {
            ContactScreen(onBack = { nav.popBackStack() })
        }
        composable(
            Routes.MEDIA,
            arguments = listOf(navArgument("messageId") { type = NavType.StringType }, navArgument("attachmentId") { type = NavType.StringType }),
        ) { MediaViewerScreen(onBack = { nav.popBackStack() }) }
    }
}


/** The key store did not answer. Nothing was deleted; trying again is safe. */
@Composable
fun StorageUnavailableScreen(onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.storage_unavailable_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.storage_unavailable_text), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp),
        )
        Button(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_retry)) }
    }
}
