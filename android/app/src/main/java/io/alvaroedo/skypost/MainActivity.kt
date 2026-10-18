package io.alvaroedo.skypost

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.alvaroedo.skypost.ui.advanced.AdvancedScreen
import io.alvaroedo.skypost.ui.family.FamilyScreen
import io.alvaroedo.skypost.ui.family.FamilyViewModel
import io.alvaroedo.skypost.ui.theme.SkypostTheme
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SkypostTheme { AppRoot() } }
    }
}

/** Navigation 3 destinations. Saved with the back stack, so keep them small. */
@Serializable
data object Family : NavKey

@Serializable
data object Advanced : NavKey

@Composable
private fun AppRoot(vm: FamilyViewModel = viewModel()) {
    // Family mode is the root, so system back from it leaves the app.
    val backStack = rememberNavBackStack(Family)
    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        // Screens added later get their own saved state and ViewModels;
        // FamilyViewModel is activity-scoped because both screens share it.
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entry<Family> {
                FamilyScreen(onOpenAdvanced = { backStack.add(Advanced) }, vm = vm)
            }
            entry<Advanced> {
                val ui by vm.ui.collectAsStateWithLifecycle()
                AdvancedScreen(
                    emergencyEncryption = ui.emergencyEncryption,
                    onEmergencyEncryptionChange = vm::setEmergencyEncryption,
                    onBack = { backStack.removeLastOrNull() },
                )
            }
        },
    )
}
