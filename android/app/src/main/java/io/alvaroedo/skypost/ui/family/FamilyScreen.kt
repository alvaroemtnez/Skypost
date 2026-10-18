package io.alvaroedo.skypost.ui.family

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.alvaroedo.skypost.R
import io.alvaroedo.skypost.model.DeliveryState
import io.alvaroedo.skypost.radio.RadioState

@Composable
fun FamilyScreen(
    onOpenAdvanced: () -> Unit,
    vm: FamilyViewModel = viewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var confirmHelp by remember { mutableStateOf(false) }
    var composing by remember { mutableStateOf(false) }
    var showInbox by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    Scaffold(topBar = { StatusStrip(ui, onLongPress = onOpenAdvanced) }) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BigButton(stringResource(R.string.btn_ok), colors.primaryContainer, colors.onPrimaryContainer) { vm.sendOk() }
            BigButton(stringResource(R.string.btn_help), colors.errorContainer, colors.onErrorContainer) { confirmHelp = true }
            BigButton(stringResource(R.string.btn_message), colors.secondaryContainer, colors.onSecondaryContainer) { composing = true }
            BigButton(stringResource(R.string.btn_inbox), colors.tertiaryContainer, colors.onTertiaryContainer) { showInbox = true }
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(ui.outgoing, key = { it.id }) { item ->
                    ListItem(
                        headlineContent = { Text(item.label) },
                        supportingContent = { Text(stateLabel(item.state)) },
                    )
                }
            }
        }
    }

    if (confirmHelp) {
        AlertDialog(
            onDismissRequest = { confirmHelp = false },
            title = { Text(stringResource(R.string.help_confirm_title)) },
            text = { Text(stringResource(R.string.help_confirm_body)) },
            confirmButton = {
                Button(onClick = { vm.sendHelp(); confirmHelp = false }) { Text(stringResource(R.string.send)) }
            },
            dismissButton = { TextButton(onClick = { confirmHelp = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (composing) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { composing = false },
            title = { Text(stringResource(R.string.btn_message)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(160) },
                    placeholder = { Text(stringResource(R.string.message_hint)) },
                )
            },
            confirmButton = {
                Button(onClick = { vm.sendText(text); composing = false }) { Text(stringResource(R.string.send)) }
            },
            dismissButton = { TextButton(onClick = { composing = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (showInbox) {
        AlertDialog(
            onDismissRequest = { showInbox = false },
            title = { Text(stringResource(R.string.btn_inbox)) },
            text = {
                if (ui.inbox.isEmpty()) {
                    Text(stringResource(R.string.inbox_empty))
                } else {
                    LazyColumn {
                        items(ui.inbox, key = { it.id }) { m ->
                            ListItem(headlineContent = { Text(m.text) }, supportingContent = { Text(m.from) })
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showInbox = false }) { Text("OK") } },
        )
    }
}

@Composable
private fun BigButton(label: String, container: Color, content: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(88.dp),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
    ) {
        Text(label, style = MaterialTheme.typography.headlineSmall)
    }
}

/** Radio, band and GPS-time status. Long-press opens the advanced screen. */
@Composable
private fun StatusStrip(ui: FamilyUiState, onLongPress: () -> Unit) {
    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPress() }) },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val radio = if (ui.radio == RadioState.READY) R.string.radio_ready else R.string.radio_disconnected
            Text(stringResource(radio))
            Text(stringResource(R.string.band_now, ui.band))
            if (!ui.timeFix) Text(stringResource(R.string.no_time_fix))
        }
    }
}

@Composable
private fun stateLabel(s: DeliveryState): String = stringResource(
    when (s) {
        DeliveryState.QUEUED -> R.string.state_queued
        DeliveryState.SENT -> R.string.state_sent
        DeliveryState.HEARD_BY_BASE -> R.string.state_heard
        DeliveryState.DELIVERED -> R.string.state_delivered
        DeliveryState.READ -> R.string.state_read
        DeliveryState.FAILED -> R.string.state_failed
    },
)
