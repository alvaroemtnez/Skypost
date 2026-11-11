package io.alvaroedo.skypost.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.alvaroedo.skypost.R
import io.alvaroedo.skypost.model.Settings
import io.alvaroedo.skypost.radio.CatProtocol
import io.alvaroedo.skypost.radio.CustomRadio
import io.alvaroedo.skypost.radio.RadioModel
import io.alvaroedo.skypost.radio.RadioState
import io.alvaroedo.skypost.ui.components.IconDisc
import io.alvaroedo.skypost.ui.components.InfoTile
import io.alvaroedo.skypost.ui.components.SheetHeader
import io.alvaroedo.skypost.ui.components.SheetHeading
import io.alvaroedo.skypost.ui.components.label

/**
 * The radio card, from the top bar's radio button or Settings: what is
 * connected and how, and which radio it is (the known ones by their short
 * name, or Custom with its CAT set by hand). The connection itself arrives
 * with the phase 2 radio service.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RadioSheet(
    state: RadioState,
    settings: Settings,
    onSetRadio: (RadioModel?, CustomRadio) -> Unit,
    onDismiss: () -> Unit,
) {
    val model = settings.radio
    val custom = settings.customRadio
    val connected = state == RadioState.AUDIO_ONLY || state == RadioState.READY || state == RadioState.TRANSMITTING
    val customLabel = stringResource(R.string.radio_custom)
    val name = when (model) {
        null -> stringResource(R.string.radio_none_chosen)
        RadioModel.CUSTOM -> custom.name.ifBlank { customLabel }
        else -> model.fullName
    }
    val cat = if (model == RadioModel.CUSTOM) custom.cat else model?.cat

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            SheetHeader(
                title = name,
                subtitle = stringResource(state.label()),
                mark = { IconDisc(R.drawable.ic_radio, size = 56.dp, highlighted = connected) },
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoTile(
                    stringResource(R.string.radio_connection),
                    stringResource(
                        when (state) {
                            RadioState.DISCONNECTED, RadioState.CONNECTING -> R.string.radio_link_none
                            RadioState.AUDIO_ONLY -> R.string.radio_link_audio
                            RadioState.READY, RadioState.TRANSMITTING -> R.string.radio_link_cat
                        },
                    ),
                    Modifier.weight(1f),
                )
                InfoTile(
                    stringResource(R.string.radio_cat),
                    cat?.let { stringResource(it.label()) } ?: "—",
                    Modifier.weight(1f),
                )
            }
            Text(
                stringResource(
                    when (state) {
                        RadioState.DISCONNECTED, RadioState.CONNECTING -> R.string.radio_desc_none
                        RadioState.AUDIO_ONLY -> R.string.radio_desc_audio
                        RadioState.READY, RadioState.TRANSMITTING -> R.string.radio_desc_ready
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
            )

            SheetHeading(stringResource(R.string.radio_pick))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RadioModel.entries.forEach { m ->
                    FilterChip(
                        selected = model == m,
                        onClick = { onSetRadio(if (model == m) null else m, custom) },
                        label = { Text(if (m == RadioModel.CUSTOM) customLabel else m.shortName) },
                    )
                }
            }
            if (model == RadioModel.CUSTOM) CustomRadioForm(custom) { onSetRadio(RadioModel.CUSTOM, it) }

            SheetHeading(stringResource(R.string.settings_band))
            Text(stringResource(R.string.settings_band_body), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.radio_usb_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

/** A radio Skypost does not know: a short name for the top bar, its CAT and speed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomRadioForm(custom: CustomRadio, onChange: (CustomRadio) -> Unit) {
    var name by remember { mutableStateOf(custom.name) }
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it.take(CustomRadio.MAX_NAME)
                onChange(custom.copy(name = name))
            },
            label = { Text(stringResource(R.string.radio_custom_name)) },
            supportingText = { Text(stringResource(R.string.radio_custom_name_help, CustomRadio.MAX_NAME)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.radio_cat), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CatProtocol.entries.forEach { c ->
                FilterChip(
                    selected = custom.cat == c,
                    onClick = { onChange(custom.copy(cat = c)) },
                    label = { Text(stringResource(c.label())) },
                )
            }
        }
        if (custom.cat != CatProtocol.NONE) {
            Text(stringResource(R.string.radio_baud), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CustomRadio.BAUDS.forEach { b ->
                    FilterChip(selected = custom.baud == b, onClick = { onChange(custom.copy(baud = b)) }, label = { Text(b.toString()) })
                }
            }
        }
    }
}

private fun CatProtocol.label(): Int = when (this) {
    CatProtocol.NONE -> R.string.cat_none
    CatProtocol.KENWOOD -> R.string.cat_kenwood
    CatProtocol.CIV -> R.string.cat_civ
}
