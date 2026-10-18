package es.nvislink

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import es.nvislink.ui.advanced.AdvancedScreen
import es.nvislink.ui.family.FamilyScreen
import es.nvislink.ui.family.FamilyViewModel
import es.nvislink.ui.theme.NvislinkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { NvislinkTheme { AppRoot() } }
    }
}

@Composable
private fun AppRoot(vm: FamilyViewModel = viewModel()) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    var encrypt by rememberSaveable { mutableStateOf(vm.emergencyEncryption) }
    if (advanced) {
        AdvancedScreen(
            emergencyEncryption = encrypt,
            onEmergencyEncryptionChange = { encrypt = it; vm.emergencyEncryption = it },
            onBack = { advanced = false },
        )
    } else {
        FamilyScreen(onOpenAdvanced = { advanced = true }, vm = vm)
    }
}
