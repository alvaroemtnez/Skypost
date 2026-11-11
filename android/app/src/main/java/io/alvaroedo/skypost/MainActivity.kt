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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
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
import io.alvaroedo.skypost.model.VerifyResult
import io.alvaroedo.skypost.ui.AppViewModel
import io.alvaroedo.skypost.ui.chat.ChatScreen
import io.alvaroedo.skypost.ui.chat.ChatViewModel
import io.alvaroedo.skypost.ui.chats.ChatListScreen
import io.alvaroedo.skypost.ui.chats.ChatListViewModel
import io.alvaroedo.skypost.ui.components.ImportDialog
import io.alvaroedo.skypost.ui.components.LocalSnackbar
import io.alvaroedo.skypost.ui.identity.QrDialog
import io.alvaroedo.skypost.ui.identity.QrPopup
import io.alvaroedo.skypost.ui.newchat.NewChatSheet
import io.alvaroedo.skypost.ui.newchat.NewChatViewModel
import io.alvaroedo.skypost.ui.onboarding.OnboardingScreen
import io.alvaroedo.skypost.ui.pharos.PharosAdminScreen
import io.alvaroedo.skypost.ui.pharos.PharosAdminViewModel
import io.alvaroedo.skypost.ui.pharos.PharosSheet
import io.alvaroedo.skypost.ui.pharos.PharosViewModel
import io.alvaroedo.skypost.ui.scan.ScanScreen
import io.alvaroedo.skypost.location.Locator
import io.alvaroedo.skypost.radio.RadioLink
import io.alvaroedo.skypost.ui.settings.BlockedScreen
import io.alvaroedo.skypost.ui.settings.RadioSheet
import io.alvaroedo.skypost.ui.settings.SettingsScreen
import io.alvaroedo.skypost.ui.settings.SosContactsScreen
import io.alvaroedo.skypost.ui.theme.SkypostTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    private val repo: Repository by lazy { (application as SkypostApp).repository }
    private val appVm: AppViewModel by viewModels { viewModelFactory { initializer { AppViewModel(repo, (application as SkypostApp).locator) } } }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleLink(intent)
        val radio = (application as SkypostApp).radio
        val locator = (application as SkypostApp).locator
        setContent { SkypostTheme { AppRoot(repo, radio, locator, appVm) } }
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

/** Scan someone's QR in person to verify the contact or Pharos [key]. */
@Serializable
data class ScanVerify(val key: String) : NavKey

@Serializable
data class PharosAdminRoute(val key: String) : NavKey

@Serializable
data object SettingsRoute : NavKey

@Serializable
data object SosContactsRoute : NavKey

@Serializable
data object ScanRoute : NavKey

@Serializable
data object ArchivedRoute : NavKey

@Serializable
data object BlockedRoute : NavKey

/** The cards that open over the screens (bottom sheets), one at a time. */
private enum class AppSheet { PHAROS, RADIO, NEW_CHAT }

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

/** Open a chat (from a card or an import): no duplicate on top. */
private fun NavBackStack<NavKey>.openChat(id: String) {
    if (lastOrNull() != ChatRoute(id)) add(ChatRoute(id))
}

@Composable
private fun AppRoot(repo: Repository, radioLink: RadioLink, locator: Locator, appVm: AppViewModel) {
    val identity by appVm.identity.collectAsStateWithLifecycle()
    val radio by radioLink.state.collectAsStateWithLifecycle()
    val pending by appVm.pendingImport.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    // Outlives the screens: a scanner pops itself before its snackbar shows.
    val rootScope = rememberCoroutineScope()
    CompositionLocalProvider(LocalSnackbar provides snackbar) {
        val me = identity
        if (me == null) {
            OnboardingScreen(repo)
            return@CompositionLocalProvider
        }
        // The chat list is the root, so system back from it leaves the app.
        val backStack = rememberNavBackStack(Chats)
        var sheet by rememberSaveable { mutableStateOf<AppSheet?>(null) }
        var qr by remember { mutableStateOf<QrPopup?>(null) }
        val settings by repo.settings.collectAsStateWithLifecycle()
        val contacts by repo.contacts.collectAsStateWithLifecycle()
        val pharos by repo.pharos.collectAsStateWithLifecycle()
        val myCodeTitle = stringResource(R.string.my_code)
        val showQrTitle = stringResource(R.string.show_qr)
        val inviteTitle = stringResource(R.string.group_invite)
        val res = LocalResources.current
        val showMyCode = {
            qr = QrPopup(myCodeTitle, me.card.alias, me.uri, detail = me.card.callsign, safetyNumber = me.safetyNumber)
        }
        /** QR of a contact or Pharos, to pass it on. */
        val showCard: (String) -> Unit = { key ->
            val card = contacts.firstOrNull { it.card.key == key }?.let { it.card to it.safetyNumber }
                ?: pharos.firstOrNull { it.card.key == key }?.let { it.card to it.safetyNumber }
            card?.let { (c, safety) ->
                qr = QrPopup(showQrTitle, c.alias, repo.uriOf(c), detail = c.callsign, safetyNumber = safety, body = res.getString(R.string.card_code_body, c.alias))
            }
        }
        val go: (NavKey) -> Unit = { key ->
            sheet = null
            backStack.add(key)
        }
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
                        onNewChat = { sheet = AppSheet.NEW_CHAT },
                        onPharos = { sheet = AppSheet.PHAROS },
                        onMyCode = showMyCode,
                        onSettings = { backStack.add(SettingsRoute) },
                        onSosSettings = { backStack.add(SosContactsRoute) },
                        radio = radio,
                        onRadio = { sheet = AppSheet.RADIO },
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
                        vm = viewModel { ChatViewModel(repo, key.id, locator) },
                        onInvite = { uri, name -> qr = QrPopup(inviteTitle, name, uri, secret = true) },
                        onShowCode = showCard,
                        onScanVerify = { backStack.add(ScanVerify(it)) },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                entry<PharosAdminRoute> { key ->
                    PharosAdminScreen(
                        vm = viewModel { PharosAdminViewModel(repo, key.key) },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                entry<SettingsRoute> {
                    SettingsScreen(
                        repo = repo,
                        onMyCode = showMyCode,
                        onPharos = { sheet = AppSheet.PHAROS },
                        onSosContacts = { backStack.add(SosContactsRoute) },
                        onRadio = { sheet = AppSheet.RADIO },
                        onBlocked = { backStack.add(BlockedRoute) },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                entry<SosContactsRoute> {
                    SosContactsScreen(repo = repo, onBack = { backStack.removeLastOrNull() })
                }
                entry<ScanVerify> { key ->
                    val messages = mapOf(
                        VerifyResult.VERIFIED to stringResource(R.string.verify_ok),
                        VerifyResult.OTHER_KEY to stringResource(R.string.verify_other_key),
                        VerifyResult.NOT_A_CODE to stringResource(R.string.code_not_recognised),
                    )
                    ScanScreen(
                        onResult = { text ->
                            backStack.removeLastOrNull()
                            val r = repo.verifyByScan(key.key, text)
                            rootScope.launch { snackbar.showSnackbar(messages.getValue(r)) }
                        },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                entry<ScanRoute> {
                    val notACode = stringResource(R.string.code_not_recognised)
                    ScanScreen(
                        onResult = { text ->
                            backStack.removeLastOrNull()
                            if (!appVm.offer(text, inPerson = true)) rootScope.launch { snackbar.showSnackbar(notACode) }
                        },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
            },
        )
        when (sheet) {
            AppSheet.PHAROS -> PharosSheet(
                vm = viewModel { PharosViewModel(repo) },
                onDismiss = { sheet = null },
                onCode = appVm::offer,
                onScan = { go(ScanRoute) },
                onShowCode = showCard,
                onAdmin = { go(PharosAdminRoute(it)) },
                onScanVerify = { go(ScanVerify(it)) },
            )
            AppSheet.RADIO -> RadioSheet(
                state = radio,
                settings = settings,
                onSetRadio = repo::setRadio,
                onDismiss = { sheet = null },
            )
            AppSheet.NEW_CHAT -> NewChatSheet(
                vm = viewModel { NewChatViewModel(repo) },
                onDismiss = { sheet = null },
                onCode = appVm::offer,
                onScan = { go(ScanRoute) },
                onPharos = { sheet = AppSheet.PHAROS },
                onOpenChat = { id ->
                    sheet = null
                    backStack.openChat(id)
                },
            )
            null -> {}
        }
        qr?.let { QrDialog(it, onDismiss = { qr = null }) }
        pending?.let { s ->
            ImportDialog(
                scanned = s.scanned,
                inPerson = s.inPerson,
                onConfirm = {
                    appVm.confirmImport()?.let {
                        sheet = null
                        backStack.openChat(it)
                    }
                },
                onDismiss = appVm::dismissImport,
            )
        }
    }
}
