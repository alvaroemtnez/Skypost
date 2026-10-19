package io.alvaroedo.skypost.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import io.alvaroedo.skypost.ui.components.SettingRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.alvaroedo.skypost.R
import io.alvaroedo.skypost.radio.RadioState
import io.alvaroedo.skypost.ui.components.RadioIndicator

/** Radio connection and settings. Placeholder until the phase 2 radio service. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioSettingsScreen(state: RadioState, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_radio)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                SettingRow(
                    leadingContent = { RadioIndicator(state, onClick = {}) },
                    headlineContent = {
                        Text(
                            stringResource(
                                when (state) {
                                    RadioState.DISCONNECTED, RadioState.CONNECTING -> R.string.radio_disconnected
                                    RadioState.AUDIO_ONLY -> R.string.radio_audio_only
                                    RadioState.READY, RadioState.TRANSMITTING -> R.string.radio_ready
                                },
                            ),
                        )
                    },
                    supportingContent = {
                        Text(
                            stringResource(
                                when (state) {
                                    RadioState.DISCONNECTED, RadioState.CONNECTING -> R.string.radio_desc_none
                                    RadioState.AUDIO_ONLY -> R.string.radio_desc_audio
                                    RadioState.READY, RadioState.TRANSMITTING -> R.string.radio_desc_ready
                                },
                            ),
                        )
                    },
                )
                SettingRow(
                    leadingContent = { Icon(painterResource(R.drawable.ic_radio), null) },
                    headlineContent = { Text(stringResource(R.string.settings_radio_rig)) },
                    supportingContent = { Text(stringResource(R.string.pending_phase2)) },
                )
                SettingRow(
                    leadingContent = { Icon(painterResource(R.drawable.ic_info), null) },
                    headlineContent = { Text(stringResource(R.string.settings_band)) },
                    supportingContent = { Text(stringResource(R.string.settings_band_body)) },
                )
                Text(
                    stringResource(R.string.radio_usb_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}
