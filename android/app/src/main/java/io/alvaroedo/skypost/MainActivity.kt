package io.alvaroedo.skypost

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.rememberCoroutineScope
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
import io.alvaroedo.skypost.ui.scan.ScanScreen
import io.alvaroedo.skypost.radio.RadioLink
import io.alvaroedo.skypost.ui.settings.BlockedScreen
import io.alvaroedo.skypost.ui.settings.RadioSettingsScreen
import io.alvaroedo.skypost.ui.settings.SettingsScreen
import io.alvaroedo.skypost.ui.settings.SosContactsScreen
import io.alvaroedo.skypost.ui.theme.SkypostTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    private val repo: Repository by lazy { (application as SkypostApp).repository }
    private val appVm: AppViewModel by viewModels { viewModelFactory { initializer { AppViewModel(repo) } } }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleLink(intent)
        val radio = (application as SkypostApp).radio
        setContent { SkypostTheme { AppRoot(repo, radio, appVm) } }
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

@Serializable
data object SosContactsRoute : NavKey

@Serializable
data object ScanRoute : NavKey

@Serializable
data object RadioRoute : NavKey

@Serializable
data object ArchivedRoute : NavKey

@Serializable
data object BlockedRoute : NavKey

private const val SLIDE_MS = 250

/** Push: the new screen slides in from the right over the old one. */
private fun pushTransform(): ContentTransform =
    slideInHorizontally(tween(SLIDE_MS)) { it } togetherWith slideOutHorizontally(tween(SLIDE_MS)) { -it / 4 }

/**
 * Back: the top screen slides out to the right, uncovering the one below
 * (like Signal and WhatsApp). Predictive back is off in the manifest, so this
 * runs when the gesture is released; if a system forces predictive back, the
 * same slide follows the finger instead of the default shrinking card.
 */
private fun popTransform(): ContentTransform = ContentTransform(
    slideInHorizontally(tween(SLIDE_MS)) { -it / 4 },
    slideOutHorizontally(tween(SLIDE_MS)) { it },
    targetContentZIndex = -1f,
)

/** Replace the new-chat steps with the chat they opened. */
private fun NavBackStack<NavKey>.openChat(id: String) {
    removeAll { it is NewChat || it is NewChatOf }
    add(ChatRoute(id))
}

@Composable
private fun AppRoot(repo: Repository, radioLink: RadioLink, appVm: AppViewModel) {
    val identity by appVm.identity.collectAsStateWithLifecycle()
    val radio by radioLink.state.collectAsStateWithLifecycle()
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
            transitionSpec = { pushTransform() },
            popTransitionSpec = { popTransform() },
            predictivePopTransitionSpec = { popTransform() },
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
                        onSosSettings = { backStack.add(SosContactsRoute) },
                        radio = radio,
                        onRadio = { backStack.add(RadioRoute) },
                        onArchived = { backStack.add(ArchivedRoute) },
                    )
                }
                entry<ArchivedRoute> {
                    ChatListScreen(
                        vm = viewModel { ChatListViewModel(repo, archived = true) },
                        onSos = appVm::sos,
                        onOpenChat = { backStack.add(ChatRoute(it)) },
                        onNewChat = {},
                        onPharos = {},
                        onMyCode = {},
                        onSettings = {},
                        onSosSettings = {},
                        radio = radio,
                        onRadio = {},
                        onArchived = {},
                        archived = true,
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                entry<BlockedRoute> {
                    BlockedScreen(repo = repo, onBack = { backStack.removeLastOrNull() })
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
                        onScan = { backStack.add(ScanRoute) },
                        onOpenChat = { backStack.openChat(it) },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                entry<PharosRoute> {
                    PharosScreen(
                        vm = viewModel { PharosViewModel(repo) },
                        onCode = appVm::offer,
                        onScan = { backStack.add(ScanRoute) },
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
                        onSosContacts = { backStack.add(SosContactsRoute) },
                        onRadio = { backStack.add(RadioRoute) },
                        onBlocked = { backStack.add(BlockedRoute) },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                entry<RadioRoute> {
                    RadioSettingsScreen(radio, onBack = { backStack.removeLastOrNull() })
                }
                entry<SosContactsRoute> {
                    SosContactsScreen(repo = repo, onBack = { backStack.removeLastOrNull() })
                }
                entry<ScanRoute> {
                    val notACode = stringResource(R.string.code_not_recognised)
                    val scope = rememberCoroutineScope()
                    ScanScreen(
                        onResult = { text ->
                            backStack.removeLastOrNull()
                            if (!appVm.offer(text)) scope.launch { snackbar.showSnackbar(notACode) }
                        },
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
