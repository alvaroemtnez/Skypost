package io.alvaroedo.skypost.ui.pharos

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.alvaroedo.skypost.R
import io.alvaroedo.skypost.model.AdminCommand
import io.alvaroedo.skypost.model.AdminEntry
import io.alvaroedo.skypost.model.AdminHow
import io.alvaroedo.skypost.model.AdminOutcome
import io.alvaroedo.skypost.model.AdminReply
import io.alvaroedo.skypost.model.AdminTarget
import io.alvaroedo.skypost.model.ChatKind
import io.alvaroedo.skypost.model.PharosSwitch
import io.alvaroedo.skypost.model.Registration
import io.alvaroedo.skypost.model.RequestEntry
import io.alvaroedo.skypost.model.Who
import io.alvaroedo.skypost.ui.components.LocalSnackbar
import io.alvaroedo.skypost.ui.components.PickPhone
import io.alvaroedo.skypost.ui.components.label
import io.alvaroedo.skypost.ui.components.readPickedPhone
import io.alvaroedo.skypost.ui.components.shortTime
import io.alvaroedo.skypost.ui.theme.extraColors
import kotlinx.coroutines.launch

/** A target dialog: what it asks for and what its buttons send. */
private data class Ask(
    val title: Int,
    /** null = a field user (alias); otherwise a bridged network. */
    val kinds: List<ChatKind?>,
    val actions: List<Pair<Int, (AdminTarget) -> AdminCommand?>>,
)

/** Gear beside the active Pharos: quick remote management (decision 0021). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PharosAdminScreen(vm: PharosAdminViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var ask by remember { mutableStateOf<Ask?>(null) }
    var login by remember { mutableStateOf(false) }
    var showRequests by remember { mutableStateOf(false) }
    /** A command waiting for the personal-data warning. */
    var warnFor by remember { mutableStateOf<(() -> Unit)?>(null) }
    var confirmSwitch by remember { mutableStateOf<PharosSwitch?>(null) }
    val sentEncrypted = stringResource(R.string.admin_sign_in_sent)
    val p = ui.pharos
    val bridged = listOf(ChatKind.SIGNAL, ChatKind.WHATSAPP, ChatKind.MESHCORE)
    val anyone = listOf<ChatKind?>(null) + bridged
    val list = rememberLazyListState()

    /** Send, after the warning if it would put names or numbers on air in plain. */
    val guarded: (AdminCommand) -> Unit = { c ->
        if (vm.needsWarning(c)) warnFor = { vm.send(c) } else vm.send(c)
    }
    // A new command: show it and, soon, its reply. Not for status and
    // switches: their answer shows in the switches themselves.
    val newest = ui.log.firstOrNull()?.takeUnless { it.command is AdminCommand.Status || it.command is AdminCommand.Set }?.id
    var seen by remember { mutableStateOf<Long?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(p != null, newest) {
        if (p == null) return@LaunchedEffect
        if (loaded && newest != null && newest != seen && !showRequests) {
            list.animateScrollToItem(if (p.admin) 4 else 1)
        }
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
                RoleCard(admin = p?.admin == true, encryptAll = ui.encryptAll, onSignIn = { login = true })
            }
            if (p?.admin == true) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Header(R.string.admin_switches, Modifier.weight(1f))
                        IconButton(onClick = { vm.send(AdminCommand.Status) }) {
                            Icon(painterResource(R.drawable.ic_refresh), stringResource(R.string.admin_refresh))
                        }
                    }
                }
                item {
                    Switches(ui.switches) { s, on ->
                        if (on && (s == PharosSwitch.EMERGENCY || s == PharosSwitch.STEALTH)) {
                            confirmSwitch = s
                        } else {
                            vm.send(AdminCommand.Set(s, on))
                        }
                    }
                }
                item {
                    Header(R.string.admin_commands)
                    Command(R.drawable.ic_person_add, R.string.admin_requests, R.string.admin_requests_body) {
                        if (vm.needsWarning(AdminCommand.Requests)) {
                            warnFor = { showRequests = true; vm.send(AdminCommand.Requests) }
                        } else {
                            showRequests = true
                            vm.send(AdminCommand.Requests)
                        }
                    }
                    Command(R.drawable.ic_info, R.string.admin_status, R.string.admin_status_body) {
                        vm.send(AdminCommand.Status)
                    }
                    Command(R.drawable.ic_group, R.string.admin_clients, R.string.admin_clients_body) {
                        guarded(AdminCommand.Clients)
                    }
                    Command(R.drawable.ic_check_circle, R.string.admin_allow, R.string.admin_allow_body) {
                        ask = Ask(
                            R.string.admin_allow,
                            listOf(ChatKind.SIGNAL, ChatKind.WHATSAPP),
                            listOf(R.string.admin_allow_action to { t -> (t as? AdminTarget.Contact)?.let { AdminCommand.Allow(it) } }),
                        )
                    }
                    Command(R.drawable.ic_block, R.string.admin_block, R.string.admin_block_body) {
                        ask = Ask(
                            R.string.admin_block,
                            anyone,
                            listOf(
                                R.string.unblock to { t -> AdminCommand.Block(t, false) },
                                R.string.block to { t -> AdminCommand.Block(t, true) },
                            ),
                        )
                    }
                    Command(R.drawable.ic_admin, R.string.admin_admins, R.string.admin_admins_body) {
                        guarded(AdminCommand.Admins)
                    }
                    Command(R.drawable.ic_person_add, R.string.admin_add, R.string.admin_add_body) {
                        ask = Ask(R.string.admin_add, anyone, listOf(R.string.admin_add_action to { t -> AdminCommand.AddAdmin(t) }))
                    }
                    Command(R.drawable.ic_remove_moderator, R.string.admin_revoke, R.string.admin_revoke_body) {
                        ask = Ask(R.string.admin_revoke, anyone, listOf(R.string.admin_revoke_action to { t -> AdminCommand.RemoveAdmin(t) }))
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

    ask?.let { a ->
        TargetDialog(
            a,
            vm::target,
            onSend = { c ->
                ask = null
                guarded(c)
            },
            onDismiss = { ask = null },
        )
    }

    if (showRequests) {
        RequestsDialog(
            requests = ui.requests,
            loading = ui.requestsLoading,
            onDecide = { num, accept -> vm.send(if (accept) AdminCommand.Accept(num) else AdminCommand.Refuse(num)) },
            onDismiss = { showRequests = false },
        )
    }

    warnFor?.let { go ->
        AlertDialog(
            onDismissRequest = { warnFor = null },
            icon = { Icon(painterResource(R.drawable.ic_error), null) },
            title = { Text(stringResource(R.string.privacy_title)) },
            text = { Text(stringResource(R.string.privacy_body)) },
            confirmButton = { TextButton(onClick = { warnFor = null; go() }) { Text(stringResource(R.string.privacy_send)) } },
            dismissButton = { TextButton(onClick = { warnFor = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    confirmSwitch?.let { s ->
        val emergency = s == PharosSwitch.EMERGENCY
        AlertDialog(
            onDismissRequest = { confirmSwitch = null },
            icon = { Icon(painterResource(if (emergency) R.drawable.ic_lock else R.drawable.ic_radar), null) },
            title = { Text(stringResource(if (emergency) R.string.emergency_on_title else R.string.stealth_on_title)) },
            text = { Text(stringResource(if (emergency) R.string.emergency_on_body else R.string.stealth_on_body)) },
            confirmButton = {
                Button(
                    onClick = { confirmSwitch = null; vm.send(AdminCommand.Set(s, true)) },
                    colors = if (emergency) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.extraColors.emergency,
                            contentColor = MaterialTheme.extraColors.onEmergency,
                        )
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                ) { Text(stringResource(R.string.activate)) }
            },
            dismissButton = { TextButton(onClick = { confirmSwitch = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

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
private fun Header(text: Int, modifier: Modifier = Modifier) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
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

private fun PharosSwitch.title(): Int = when (this) {
    PharosSwitch.OPEN -> R.string.switch_open
    PharosSwitch.REQUESTS -> R.string.switch_requests
    PharosSwitch.LOGIN -> R.string.switch_login
    PharosSwitch.EMERGENCY -> R.string.switch_emergency
    PharosSwitch.STEALTH -> R.string.switch_stealth
}

private fun PharosSwitch.body(): Int = when (this) {
    PharosSwitch.OPEN -> R.string.switch_open_body
    PharosSwitch.REQUESTS -> R.string.switch_requests_body
    PharosSwitch.LOGIN -> R.string.switch_login_body
    PharosSwitch.EMERGENCY -> R.string.switch_emergency_body
    PharosSwitch.STEALTH -> R.string.switch_stealth_body
}

/** The switches as last heard; unknown until the first status. */
@Composable
private fun Switches(known: Set<PharosSwitch>?, onFlip: (PharosSwitch, Boolean) -> Unit) {
    if (known == null) {
        ListItem(headlineContent = { Text(stringResource(R.string.admin_switches_unknown)) })
        return
    }
    Column {
        PharosSwitch.entries.forEach { s ->
            val on = s in known
            ListItem(
                modifier = Modifier.clickable { onFlip(s, !on) },
                headlineContent = { Text(stringResource(s.title())) },
                supportingContent = { Text(stringResource(s.body())) },
                trailingContent = { Switch(checked = on, onCheckedChange = { onFlip(s, it) }) },
            )
        }
    }
}

@Composable
private fun Who.shown(): String = when (this) {
    Who.RADIO -> stringResource(R.string.who_radio)
    Who.SIGNAL -> stringResource(ChatKind.SIGNAL.label())
    Who.WHATSAPP -> stringResource(ChatKind.WHATSAPP.label())
    Who.MESHCORE -> stringResource(ChatKind.MESHCORE.label())
}

@Composable
private fun AdminTarget.shown(): String = when (this) {
    is AdminTarget.FieldUser -> "@$alias"
    is AdminTarget.Contact -> "${stringResource(kind.label())} " + if (address.all { it.isDigit() }) "+$address" else address
    is AdminTarget.MeshCore -> "${stringResource(ChatKind.MESHCORE.label())} $prefix"
}

@Composable
private fun AdminCommand?.shown(): String = when (this) {
    null -> stringResource(R.string.cmd_notice)
    AdminCommand.Status -> stringResource(R.string.admin_status)
    AdminCommand.Requests -> stringResource(R.string.admin_requests)
    is AdminCommand.Accept -> stringResource(R.string.cmd_accept, num)
    is AdminCommand.Refuse -> stringResource(R.string.cmd_refuse, num)
    AdminCommand.Clients -> stringResource(R.string.admin_clients)
    AdminCommand.Admins -> stringResource(R.string.admin_admins)
    is AdminCommand.Block -> stringResource(if (on) R.string.cmd_block else R.string.cmd_unblock, target.shown())
    is AdminCommand.Allow -> stringResource(R.string.cmd_allow, target.shown())
    is AdminCommand.AddAdmin -> stringResource(R.string.cmd_add_admin, target.shown())
    is AdminCommand.RemoveAdmin -> stringResource(R.string.cmd_remove_admin, target.shown())
    is AdminCommand.Set -> stringResource(if (on) R.string.cmd_set_on else R.string.cmd_set_off, stringResource(switch.title()))
    AdminCommand.Login -> stringResource(R.string.admin_sign_in)
}

private fun AdminOutcome.text(): Int = when (this) {
    AdminOutcome.OK -> R.string.out_ok
    AdminOutcome.NOT_ADMIN -> R.string.out_not_admin
    AdminOutcome.UNKNOWN -> R.string.out_unknown
    AdminOutcome.WRONG_PASSWORD -> R.string.out_wrong_password
    AdminOutcome.PASSWORD_IN_CLEAR -> R.string.out_password_clear
    AdminOutcome.LAST_ADMIN -> R.string.out_last_admin
    AdminOutcome.LOGIN_OFF -> R.string.out_login_off
    AdminOutcome.LOCKED -> R.string.out_locked
    AdminOutcome.REFUSED -> R.string.out_refused
}

private fun Registration.short(): Int = when (this) {
    Registration.REGISTERED -> R.string.client_registered
    Registration.PENDING -> R.string.client_pending
    Registration.REFUSED -> R.string.client_refused
    Registration.NEEDS_CODE, Registration.NOT_REGISTERED -> R.string.client_unregistered
}

private fun AdminHow.text(): Int = when (this) {
    AdminHow.SSH -> R.string.how_ssh
    AdminHow.BY_ADMIN -> R.string.how_by_admin
    AdminHow.PASSWORD -> R.string.how_password
}

/** A reply in the app's language. */
@Composable
private fun AdminReply.shown(): String = when (this) {
    is AdminReply.Done -> listOfNotNull(stringResource(outcome.text()), subject).joinToString(": ")
    is AdminReply.Status -> {
        val sw = switches.map { stringResource(it.title()) }.joinToString().ifEmpty { "—" }
        stringResource(R.string.reply_status, registered, requests, listening, queued, contacts) + "\n" +
            stringResource(R.string.reply_switches_on, sw)
    }
    is AdminReply.Requests ->
        if (list.isEmpty()) {
            stringResource(R.string.admin_requests_none)
        } else {
            list.map { "#${it.num} · ${it.who.shown()} · ${it.name}" }.joinToString("\n")
        }
    is AdminReply.Clients -> list.map {
        val heard = it.heardMin?.let { m -> stringResource(R.string.heard_min_ago, m) } ?: stringResource(R.string.heard_never)
        "@${it.alias} · ${stringResource(it.status.short())} · $heard"
    }.joinToString("\n")
    is AdminReply.Admins -> list.map { "${it.name} · ${it.who.shown()} · ${stringResource(it.how.text())}" }.joinToString("\n")
    is AdminReply.NewRequests ->
        stringResource(R.string.reply_new_requests, networks.map { it.shown() }.joinToString(), pending)
    is AdminReply.SwitchesChanged ->
        stringResource(R.string.reply_switches_changed, switches.map { stringResource(it.title()) }.joinToString().ifEmpty { "—" })
    is AdminReply.AdminChanged ->
        stringResource(if (added) R.string.reply_admin_added else R.string.reply_admin_removed, "$name (${who.shown()})")
}

@Composable
private fun LogEntry(e: AdminEntry) {
    ListItem(
        overlineContent = { Text(shortTime(e.atMillis)) },
        headlineContent = { Text(e.command.shown()) },
        supportingContent = {
            Text(
                e.reply?.shown() ?: stringResource(R.string.admin_waiting),
                color = if (e.reply == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        },
    )
}

/** Pending requests: fetched from the Pharos, each accepted or refused here. */
@Composable
private fun RequestsDialog(
    requests: List<RequestEntry>?,
    loading: Boolean,
    onDecide: (Int, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.admin_requests)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (loading) {
                    Text(stringResource(R.string.admin_requests_loading))
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                }
                val list = requests.orEmpty()
                if (!loading && list.isEmpty()) Text(stringResource(R.string.admin_requests_none))
                list.forEachIndexed { i, r ->
                    if (i > 0) HorizontalDivider()
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text(r.name, style = MaterialTheme.typography.titleMedium)
                        Text("#${r.num} · ${r.who.shown()}", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onDecide(r.num, false) }) { Text(stringResource(R.string.admin_refuse)) }
                            Button(onClick = { onDecide(r.num, true) }) { Text(stringResource(R.string.admin_accept)) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

/** Pick a field user or a network, type the alias or address, then one of the actions. */
@Composable
private fun TargetDialog(
    ask: Ask,
    parse: (ChatKind?, String) -> AdminTarget?,
    onSend: (AdminCommand) -> Unit,
    onDismiss: () -> Unit,
) {
    var kind by remember { mutableStateOf(ask.kinds.first()) }
    var input by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val pick = rememberLauncherForActivityResult(PickPhone()) { uri ->
        uri?.let { readPickedPhone(context, it) }?.let { input = it.number; bad = false }
    }
    val target = parse(kind, input)
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
                    supportingText = {
                        Text(if (bad) stringResource(R.string.admin_target_invalid) else target?.shown().orEmpty())
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
                ask.actions.forEach { (label, make) ->
                    TextButton(
                        onClick = { target?.let(make)?.let(onSend) ?: run { bad = true } },
                        enabled = input.isNotBlank(),
                    ) { Text(stringResource(label)) }
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
