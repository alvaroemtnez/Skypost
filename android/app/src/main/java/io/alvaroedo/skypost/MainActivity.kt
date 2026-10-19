package io.alvaroedo.skypost

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.alvaroedo.skypost.data.Repository
import io.alvaroedo.skypost.model.ChatKind
import io.alvaroedo.skypost.ui.AppViewModel
import io.alvaroedo.skypost.ui.chat.ChatScreen
import io.alvaroedo.skypost.ui.chat.ChatViewModel
import io.alvaroedo.skypost.ui.chats.ChatListScreen
import io.alvaroedo.skypost.ui.chats.ChatListViewModel
import io.alvaroedo.skypost.ui.components.ImportDialog
import io.alvaroedo.skypost.ui.components.LocalSnackbar
import io.alvaroedo.skypost.ui.identity.QrScreen
import io.alvaroedo.skypost.ui.newchat.NewChatOfScreen
import io.alvaroedo.skypost.ui.newchat.NewChatScreen
import io.alvaroedo.skypost.ui.newchat.NewChatViewModel
import io.alvaroedo.skypost.ui.onboarding.OnboardingScreen
import io.alvaroedo.skypost.ui.pharos.PharosScreen
import io.alvaroedo.skypost.ui.pharos.PharosViewModel
import io.alvaroedo.skypost.ui.settings.SettingsScreen
import io.alvaroedo.skypost.ui.theme.SkypostTheme
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    private val repo: Repository by lazy { (application as SkypostApp).repository }
    private val appVm: AppViewModel by viewModels { viewModelFactory { initializer { AppViewModel(repo) } } }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleLink(intent)
        setContent { SkypostTheme { AppRoot(repo, appVm) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    /** `skypost:` links (a scanned QR opened with the camera app). */
    private fun handleLink(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) appVm.offer(intent.dataString)
    }
}

/** Navigation 3 destinations. Saved with the back stack, so keep them small. */
@Serializable
data object Chats : NavKey

@Serializable
data class ChatRoute(val id: String) : NavKey

@Serializable
data object NewChat : NavKey

@Serializable
data class NewChatOf(val kind: String) : NavKey

@Serializable
data object PharosRoute : NavKey

@Serializable
data object MyCode : NavKey

@Serializable
data class GroupInvite(val uri: String, val name: String) : NavKey

@Serializable
data object SettingsRoute : NavKey

/** Replace the new-chat steps with the chat they opened. */
private fun NavBackStack<NavKey>.openChat(id: String) {
    removeAll { it is NewChat || it is NewChatOf }
    add(ChatRoute(id))
}

@Composable
private fun AppRoot(repo: Repository, appVm: AppViewModel) {
    val identity by appVm.identity.collectAsStateWithLifecycle()
    val pending by appVm.pendingImport.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CompositionLocalProvider(LocalSnackbar provides snackbar) {
        val me = identity
        if (me == null) {
            OnboardingScreen(repo)
            return@CompositionLocalProvider
        }
        // The chat list is the root, so system back from it leaves the app.
        val backStack = rememberNavBackStack(Chats)
        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = entryProvider {
                entry<Chats> {
                    ChatListScreen(
                        vm = viewModel { ChatListViewModel(repo) },
                        onSos = appVm::sos,
                        onOpenChat = { backStack.add(ChatRoute(it)) },
                        onNewChat = { backStack.add(NewChat) },
                        onPharos = { backStack.add(PharosRoute) },
                        onMyCode = { backStack.add(MyCode) },
                        onSettings = { backStack.add(SettingsRoute) },
                    )
                }
                entry<ChatRoute> { key ->
                    ChatScreen(
                        vm = viewModel { ChatViewModel(repo, key.id) },
                        onInvite = { uri, name -> backStack.add(GroupInvite(uri, name)) },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                entry<NewChat> {
                    NewChatScreen(
                        vm = viewModel { NewChatViewModel(repo) },
                        onKind = { backStack.add(NewChatOf(it.name)) },
                        onPharos = { backStack.add(PharosRoute) },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                entry<NewChatOf> { key ->
                    NewChatOfScreen(
                        kind = ChatKind.valueOf(key.kind),
                        vm = viewModel { NewChatViewModel(repo) },
                        onCode = appVm::offer,
                        onOpenChat = { backStack.openChat(it) },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                entry<PharosRoute> {
                    PharosScreen(
                        vm = viewModel { PharosViewModel(repo) },
                        onCode = appVm::offer,
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                entry<MyCode> {
                    QrScreen(
                        title = stringResource(R.string.my_code),
                        heading = me.card.alias,
                        detail = me.card.callsign,
                        uri = me.uri,
                        safetyNumber = me.safetyNumber,
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                entry<GroupInvite> { key ->
                    QrScreen(
                        title = stringResource(R.string.group_invite),
                        heading = key.name,
                        uri = key.uri,
                        secret = true,
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                entry<SettingsRoute> {
                    SettingsScreen(
                        repo = repo,
                        onMyCode = { backStack.add(MyCode) },
                        onPharos = { backStack.add(PharosRoute) },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
            },
        )
        pending?.let { s ->
            ImportDialog(
                scanned = s,
                onConfirm = { appVm.confirmImport()?.let { backStack.openChat(it) } },
                onDismiss = appVm::dismissImport,
            )
        }
    }
}
