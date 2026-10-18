package io.alvaroedo.skypost

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
import io.alvaroedo.skypost.ui.advanced.AdvancedScreen
import io.alvaroedo.skypost.ui.family.FamilyScreen
import io.alvaroedo.skypost.ui.family.FamilyViewModel
import io.alvaroedo.skypost.ui.theme.SkypostTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SkypostTheme { AppRoot() } }
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
