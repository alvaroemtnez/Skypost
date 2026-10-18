package io.alvaroedo.skypost.ui.advanced

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Advanced mode, for the licensed operator. Phase 2: behind a PIN.
 * Planned: provisioning by QR, radio selection, band/lane override, the
 * emergency-encryption toggle (with a clear warning), propagation log export.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedScreen(
    emergencyEncryption: Boolean,
    onEmergencyEncryptionChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Avanzado") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Volver") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            ListItem(
                headlineContent = { Text("Modo emergencia: cifrado") },
                supportingContent = {
                    Text("Solo en una emergencia real o con carga ficticia. Por defecto: texto firmado.")
                },
                trailingContent = { Switch(checked = emergencyEncryption, onCheckedChange = onEmergencyEncryptionChange) },
            )
            ListItem(headlineContent = { Text("Provisionar con QR") }, supportingContent = { Text("Pendiente (fase 2)") })
            ListItem(headlineContent = { Text("Radio") }, supportingContent = { Text("QDX / G90 · pendiente (fase 2)") })
            ListItem(headlineContent = { Text("Banda y velocidad") }, supportingContent = { Text("Automático") })
        }
    }
}
