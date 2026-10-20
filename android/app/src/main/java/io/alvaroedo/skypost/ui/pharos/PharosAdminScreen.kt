package io.alvaroedo.skypost.ui.pharos

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.alvaroedo.skypost.R
import io.alvaroedo.skypost.model.AdminEntry
import io.alvaroedo.skypost.model.ChatKind
import io.alvaroedo.skypost.ui.components.LocalSnackbar
import io.alvaroedo.skypost.ui.components.PickPhone
import io.alvaroedo.skypost.ui.components.readPickedPhone
import io.alvaroedo.skypost.ui.components.label
import io.alvaroedo.skypost.ui.components.shortTime
import kotlinx.coroutines.launch

/** An open dialog: what it asks for and what its buttons do. */
private data class Ask(
    val title: Int,
    /** null = a field user (alias); otherwise a bridged network. */
    val kinds: List<ChatKind?>,
    val actions: List<Pair<Int, (AdminTarget) -> Boolean>>,
)

/** Gear beside the active Pharos: quick remote management (decision 0020). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PharosAdminScreen(vm: PharosAdminViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var ask by remember { mutableStateOf<Ask?>(null) }
    var login by remember { mutableStateOf(false) }
    val sentEncrypted = stringResource(R.string.admin_sign_in_sent)
    val p = ui.pharos
    val bridged = listOf(ChatKind.SIGNAL, ChatKind.WHATSAPP, ChatKind.MESHCORE)
    val anyone = listOf<ChatKind?>(null) + bridged
    val list = rememberLazyListState()
    // A new command: show it and, soon, its reply (the commands push them below the fold).
    val newest = ui.log.firstOrNull()?.id
    var seen by remember { mutableStateOf<Long?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(p != null, newest) {
        if (p == null) return@LaunchedEffect
        if (loaded && newest != null && newest != seen) list.animateScrollToItem(if (p.admin) 3 else 1)
        loaded = true
        seen = newest
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.admin_title, p?.card?.alias.orEmpty())) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                RoleCard(
                    admin = p?.admin == true,
                    encryptAll = ui.encryptAll,
                    onSignIn = { login = true },
                )
            }
            if (p?.admin == true) {
                item { Header(R.string.admin_commands) }
                item {
                    Command(R.drawable.ic_info, R.string.admin_status, R.string.admin_status_body, vm::status)
                    Command(R.drawable.ic_group, R.string.admin_clients, R.string.admin_clients_body, vm::clients)
                    Command(R.drawable.ic_person_add, R.string.admin_decide, R.string.admin_decide_body) {
                        ask = Ask(
                            R.string.admin_decide,
                            listOf(null),
                            listOf(
                                R.string.admin_refuse to { t -> vm.decide((t as AdminTarget.FieldUser).alias, false) },
                                R.string.admin_accept to { t -> vm.decide((t as AdminTarget.FieldUser).alias, true) },
                            ),
                        )
                    }
                    Command(R.drawable.ic_check_circle, R.string.admin_allow, R.string.admin_allow_body) {
                        ask = Ask(
                            R.string.admin_allow,
                            bridged,
                            listOf(R.string.admin_allow_action to { t -> vm.allow(t as AdminTarget.Bridged) }),
                        )
                    }
                    Command(R.drawable.ic_block, R.string.admin_block, R.string.admin_block_body) {
                        ask = Ask(
                            R.string.admin_block,
                            anyone,
                            listOf(R.string.unblock to { t -> vm.block(t, false) }, R.string.block to { t -> vm.block(t, true) }),
                        )
                    }
                    Command(R.drawable.ic_admin, R.string.admin_admins, R.string.admin_admins_body, vm::admins)
                    Command(R.drawable.ic_remove_moderator, R.string.admin_revoke, R.string.admin_revoke_body) {
                        ask = Ask(R.string.admin_revoke, anyone, listOf(R.string.admin_revoke_action to vm::revoke))
                    }
                }
            }
            item { Header(R.string.admin_replies) }
            if (ui.log.isEmpty()) {
                item { ListItem(headlineContent = { Text(stringResource(R.string.admin_replies_empty)) }) }
            }
            items(ui.log, key = { it.id }) { LogEntry(it) }
            item {
                Text(
                    stringResource(R.string.admin_ssh_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    ask?.let { a -> TargetDialog(a, vm::text, onDismiss = { ask = null }) }

    if (login) {
        LoginDialog(
            onDismiss = { login = false },
            onConfirm = { pw ->
                login = false
                if (vm.login(pw)) scope.launch { snackbar.showSnackbar(sentEncrypted) }
            },
        )
    }
}

@Composable
private fun RoleCard(admin: Boolean, encryptAll: Boolean, onSignIn: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painterResource(R.drawable.ic_admin), null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    stringResource(if (admin) R.string.admin_you_are else R.string.admin_not),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (!admin) {
                Text(stringResource(if (encryptAll) R.string.admin_sign_in_body else R.string.admin_sign_in_plain))
                Button(onClick = onSignIn, enabled = encryptAll) {
                    Icon(painterResource(R.drawable.ic_login), null)
                    Text(stringResource(R.string.admin_sign_in), Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun Header(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun Command(icon: Int, title: Int, body: Int, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(painterResource(icon), null) },
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(body)) },
    )
}

@Composable
private fun LogEntry(e: AdminEntry) {
    ListItem(
        overlineContent = { Text(shortTime(e.atMillis)) },
        headlineContent = { Text(e.command, fontFamily = FontFamily.Monospace) },
        supportingContent = {
            Text(
                e.reply ?: stringResource(R.string.admin_waiting),
                color = if (e.reply == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        },
    )
}

/** Pick a field user or a bridged network, type the alias or address, then one of the actions. */
@Composable
private fun TargetDialog(ask: Ask, preview: (AdminTarget) -> String?, onDismiss: () -> Unit) {
    var kind by remember { mutableStateOf(ask.kinds.first()) }
    var input by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val pick = rememberLauncherForActivityResult(PickPhone()) { uri ->
        uri?.let { readPickedPhone(context, it) }?.let { input = it.number; bad = false }
    }
    val target = kind?.let { AdminTarget.Bridged(it, input) } ?: AdminTarget.FieldUser(input)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(ask.title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (ask.kinds.size > 1) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ask.kinds.forEach { k ->
                            FilterChip(
                                selected = k == kind,
                                onClick = { kind = k; bad = false },
                                label = { Text(stringResource(k?.label() ?: R.string.admin_target_field)) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; bad = false },
                    label = {
                        Text(
                            stringResource(
                                when (kind) {
                                    null -> R.string.admin_alias_label
                                    ChatKind.MESHCORE -> R.string.admin_meshcore_label
                                    ChatKind.SIGNAL -> R.string.admin_signal_label
                                    else -> R.string.admin_phone_label
                                },
                            ),
                        )
                    },
                    // What goes on air, so the airtime is no surprise.
                    supportingText = {
                        Text(if (bad) stringResource(R.string.admin_target_invalid) else preview(target).orEmpty())
                    },
                    isError = bad,
                    singleLine = true,
                    trailingIcon = {
                        if (kind == ChatKind.SIGNAL || kind == ChatKind.WHATSAPP) {
                            IconButton(onClick = { pick.launch(Unit) }) {
                                Icon(painterResource(R.drawable.ic_contacts), stringResource(R.string.pick_phone_contact))
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (kind == ChatKind.WHATSAPP) KeyboardType.Phone else KeyboardType.Text,
                    ),
                )
            }
        },
        confirmButton = {
            Row {
                ask.actions.forEach { (label, action) ->
                    TextButton(onClick = { if (action(target)) onDismiss() else bad = true }, enabled = input.isNotBlank()) {
                        Text(stringResource(label))
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun LoginDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var pass by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_lock), null) },
        title = { Text(stringResource(R.string.admin_sign_in)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.admin_sign_in_body))
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text(stringResource(R.string.admin_password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(pass) }, enabled = pass.isNotBlank()) { Text(stringResource(R.string.admin_sign_in)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
