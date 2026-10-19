package io.alvaroedo.skypost.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.alvaroedo.skypost.R
import io.alvaroedo.skypost.model.Auth
import io.alvaroedo.skypost.model.ChatKind
import io.alvaroedo.skypost.model.ChatMessage
import io.alvaroedo.skypost.ui.components.AuthIcon
import io.alvaroedo.skypost.ui.components.DeliveryIcon
import io.alvaroedo.skypost.ui.components.LocalSnackbar
import io.alvaroedo.skypost.ui.components.SafetyNumber
import io.alvaroedo.skypost.ui.components.label
import io.alvaroedo.skypost.ui.components.shortTime

/** Seconds per JS8 frame at Normal speed, for the airtime estimate. */
private const val NORMAL_FRAME_SECS = 15

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onInvite: (uri: String, name: String) -> Unit, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val chat = ui.chat
    var menu by remember { mutableStateOf(false) }
    var confirmEncrypt by remember { mutableStateOf(false) }
    var showSafety by remember { mutableStateOf(false) }
    var quick by remember { mutableStateOf(false) }

    LaunchedEffect(ui.messages.size) { vm.markRead() }

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbar.current) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                    }
                },
                title = {
                    Column {
                        Text(chat?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        chat?.let {
                            val kind = stringResource(it.kind.label())
                            val sub = listOfNotNull(
                                kind,
                                it.detail,
                                ui.via?.let { v -> stringResource(R.string.via_pharos, v) },
                            ).joinToString(" · ")
                            Text(sub, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        }
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.more_options))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            // Greyed out while Settings turns encryption on for every chat.
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(stringResource(R.string.emergency_encryption))
                                        if (ui.encryptAll) {
                                            Text(
                                                stringResource(R.string.encrypt_all_on),
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                    }
                                },
                                enabled = !ui.encryptAll,
                                leadingIcon = { Icon(painterResource(R.drawable.ic_lock), null) },
                                trailingIcon = { Checkbox(checked = ui.encrypted, onCheckedChange = null, enabled = !ui.encryptAll) },
                                onClick = {
                                    menu = false
                                    if (chat?.encrypted == true) vm.setEncrypted(false) else confirmEncrypt = true
                                },
                            )
                            if (chat?.kind == ChatKind.GROUP && chat.ref != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.group_invite)) },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_qr_code), null) },
                                    onClick = { menu = false; onInvite(chat.ref, chat.title) },
                                )
                            }
                            if (ui.safetyNumber != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.safety_number)) },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_key), null) },
                                    onClick = { menu = false; showSafety = true },
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            Composer(
                draft = ui.draft,
                frames = ui.frames,
                encrypted = ui.encrypted,
                onDraft = vm::setDraft,
                onSend = vm::send,
                onQuick = { quick = true },
                modifier = Modifier.navigationBarsPadding().imePadding(),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (ui.encrypted) {
                Banner(
                    R.drawable.ic_lock,
                    stringResource(if (ui.encryptAll) R.string.encrypted_banner_all else R.string.encrypted_banner),
                )
            }
            if (ui.noPharos) Banner(R.drawable.ic_cell_tower, stringResource(R.string.no_pharos_banner))
            LazyColumn(
                modifier = Modifier.weight(1f),
                reverseLayout = true,
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(ui.messages.asReversed(), key = { it.id }) { m ->
                    Bubble(m, showSender = chat?.kind == ChatKind.GROUP)
                }
            }
        }
    }

    if (quick) {
        ModalBottomSheet(onDismissRequest = { quick = false }) {
            QuickMessages(
                phrases = vm.phrases(),
                onStatusOk = { quick = false; vm.sendStatusOk() },
                onPosition = { quick = false; vm.sendPosition() },
                onPhrase = { quick = false; vm.sendPhrase(it) },
            )
        }
    }

    if (confirmEncrypt) {
        AlertDialog(
            onDismissRequest = { confirmEncrypt = false },
            icon = { Icon(painterResource(R.drawable.ic_lock), null) },
            title = { Text(stringResource(R.string.encrypt_confirm_title)) },
            text = { Text(stringResource(R.string.encrypt_confirm_body)) },
            confirmButton = {
                Button(
                    onClick = { confirmEncrypt = false; vm.setEncrypted(true) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text(stringResource(R.string.activate)) }
            },
            dismissButton = { TextButton(onClick = { confirmEncrypt = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (showSafety && ui.safetyNumber != null) {
        AlertDialog(
            onDismissRequest = { showSafety = false },
            title = { Text(stringResource(R.string.safety_number)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.safety_number_body, chat?.title.orEmpty()))
                    SafetyNumber(ui.safetyNumber.orEmpty(), Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { showSafety = false }) { Text(stringResource(R.string.close)) } },
        )
    }
}

@Composable
private fun Banner(icon: Int, text: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(painterResource(icon), contentDescription = null)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Bubble(m: ChatMessage, showSender: Boolean) {
    val colors = MaterialTheme.colorScheme
    val (bg, fg) = when {
        m.sos -> colors.errorContainer to colors.onErrorContainer
        m.outgoing -> colors.primaryContainer to colors.onPrimaryContainer
        else -> colors.surfaceContainerHigh to colors.onSurface
    }
    val shape = if (m.outgoing) {
        RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
    } else {
        RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalArrangement = if (m.outgoing) Arrangement.End else Arrangement.Start,
    ) {
        Surface(color = bg, contentColor = fg, shape = shape, modifier = Modifier.widthIn(max = 320.dp)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (showSender && !m.outgoing && m.sender != null) {
                    Text(m.sender, style = MaterialTheme.typography.labelLarge, color = colors.primary)
                }
                Text(m.text, style = MaterialTheme.typography.bodyLarge)
                Row(
                    Modifier.align(Alignment.End).padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(shortTime(m.atMillis), style = MaterialTheme.typography.labelSmall)
                    if (m.outgoing) {
                        if (m.auth == Auth.ENCRYPTED) AuthIcon(Auth.ENCRYPTED, tint = fg)
                        DeliveryIcon(m.delivery, tint = fg)
                    } else {
                        AuthIcon(m.auth, tint = fg)
                    }
                }
            }
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    frames: Int,
    encrypted: Boolean,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onQuick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(tonalElevation = 3.dp, modifier = modifier) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onQuick) {
                Icon(painterResource(R.drawable.ic_add), stringResource(R.string.quick_messages))
            }
            OutlinedTextField(
                value = draft,
                onValueChange = onDraft,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.message_hint)) },
                leadingIcon = if (encrypted) {
                    { Icon(painterResource(R.drawable.ic_lock), stringResource(R.string.auth_encrypted)) }
                } else {
                    null
                },
                supportingText = if (frames > 0) {
                    { Text(airtime(frames)) }
                } else {
                    null
                },
                maxLines = 5,
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = onSend, enabled = draft.isNotBlank()) {
                Icon(painterResource(R.drawable.ic_send), stringResource(R.string.send))
            }
        }
    }
}

/** "≈ 6 tramas · 1 min 30 s" at Normal speed. */
@Composable
private fun airtime(frames: Int): String {
    val secs = frames * NORMAL_FRAME_SECS
    val time = if (secs < 60) {
        stringResource(R.string.duration_s, secs)
    } else {
        stringResource(R.string.duration_min_s, secs / 60, secs % 60)
    }
    return stringResource(R.string.airtime, pluralStringResource(R.plurals.frames, frames, frames), time)
}

@Composable
private fun QuickMessages(phrases: List<String>, onStatusOk: () -> Unit, onPosition: () -> Unit, onPhrase: (String) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            ListItem(
                modifier = Modifier.clickable(onClick = onStatusOk),
                leadingContent = { Icon(painterResource(R.drawable.ic_check_circle), null) },
                headlineContent = { Text(stringResource(R.string.quick_ok)) },
                supportingContent = { Text(stringResource(R.string.quick_ok_body)) },
            )
            ListItem(
                modifier = Modifier.clickable(onClick = onPosition),
                leadingContent = { Icon(painterResource(R.drawable.ic_my_location), null) },
                headlineContent = { Text(stringResource(R.string.quick_position)) },
            )
            HorizontalDivider()
            Text(
                stringResource(R.string.quick_phrases),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(16.dp),
            )
        }
        items(phrases) { p ->
            ListItem(modifier = Modifier.clickable { onPhrase(p) }, headlineContent = { Text(p) })
        }
    }
}
