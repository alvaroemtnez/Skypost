package io.alvaroedo.skypost.ui.advanced

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.alvaroedo.skypost.R

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
                title = { Text(stringResource(R.string.advanced_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.advanced_encryption)) },
                supportingContent = { Text(stringResource(R.string.advanced_encryption_body)) },
                trailingContent = { Switch(checked = emergencyEncryption, onCheckedChange = onEmergencyEncryptionChange) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.advanced_provision)) },
                supportingContent = { Text(stringResource(R.string.pending_phase2)) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.advanced_radio)) },
                supportingContent = { Text(stringResource(R.string.advanced_radio_body)) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.advanced_band)) },
                supportingContent = { Text(stringResource(R.string.advanced_band_body)) },
            )
        }
    }
}
