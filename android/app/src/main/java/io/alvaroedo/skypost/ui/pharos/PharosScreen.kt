package io.alvaroedo.skypost.ui.pharos

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.alvaroedo.skypost.R
import io.alvaroedo.skypost.model.ChatKind
import io.alvaroedo.skypost.model.Discovered
import io.alvaroedo.skypost.model.Pharos
import io.alvaroedo.skypost.model.PharosAccess
import io.alvaroedo.skypost.model.Registration
import io.alvaroedo.skypost.ui.components.LocalSnackbar
import io.alvaroedo.skypost.ui.components.SafetyNumber
import io.alvaroedo.skypost.ui.components.VerifiedBadge
import io.alvaroedo.skypost.ui.components.VerifyControls
import io.alvaroedo.skypost.ui.components.clipboardText
import io.alvaroedo.skypost.ui.components.heardAgo
import io.alvaroedo.skypost.ui.components.label
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PharosScreen(
    vm: PharosViewModel,
    onCode: (String?) -> Boolean,
    onScan: () -> Unit,
    onShowCode: (key: String) -> Unit,
    onAdmin: (key: String) -> Unit,
    onScanVerify: (key: String) -> Unit,
    onBack: () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var details by remember { mutableStateOf<String?>(null) }
    /** Asking for an access code: to trust a discovered Pharos, or to register with a trusted one. */
    var codeFor by remember { mutableStateOf<Pair<Discovered?, String>?>(null) }
    val notACode = stringResource(R.string.code_not_recognised)
    val answered = stringResource(R.string.ping_ok)
    val silent = stringResource(R.string.ping_silent)
    val offer: (String?) -> Unit = { if (!onCode(it)) scope.launch { snackbar.showSnackbar(notACode) } }

    LaunchedEffect(ui.lastPing, ui.pinging) {
        val r = ui.lastPing
        if (!ui.pinging && r != null) snackbar.showSnackbar(if (r) answered else silent)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pharos_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { ActiveCard(ui.list.firstOrNull { it.active }, ui.pinging, vm::ping, onAdmin) }
            item { Section(R.string.pharos_trusted) }
            if (ui.list.isEmpty()) {
                item { ListItem(headlineContent = { Text(stringResource(R.string.pharos_trusted_empty)) }) }
            }
            items(ui.list, key = { it.card.key }) { p ->
                ListItem(
                    modifier = Modifier.clickable { details = p.card.key },
                    leadingContent = { RadioButton(selected = p.active, onClick = { vm.setActive(p.card.key) }) },
                    headlineContent = { Text(listOfNotNull(p.card.alias, p.card.callsign).joinToString(" · ")) },
                    supportingContent = {
                        val v = stringResource(if (p.verified) R.string.verified else R.string.not_verified)
                        Text("$v · ${heardAgo(p.lastHeardMillis)}")
                    },
                    trailingContent = { if (p.verified) VerifiedBadge() },
                )
            }
            item {
                HorizontalDivider()
                ListItem(
                    modifier = Modifier.clickable(onClick = onScan),
                    leadingContent = { Icon(painterResource(R.drawable.ic_qr_code_scanner), null) },
                    headlineContent = { Text(stringResource(R.string.pharos_add_qr)) },
                    supportingContent = { Text(stringResource(R.string.pharos_add_qr_body)) },
                )
                ListItem(
                    modifier = Modifier.clickable { offer(clipboardText(context)) },
                    leadingContent = { Icon(painterResource(R.drawable.ic_content_paste), null) },
                    headlineContent = { Text(stringResource(R.string.paste_code)) },
                )
                ListItem(
                    modifier = Modifier.clickable(enabled = !ui.discovering, onClick = vm::discover),
                    leadingContent = { Icon(painterResource(R.drawable.ic_radar), null) },
                    headlineContent = { Text(stringResource(R.string.pharos_discover)) },
                    supportingContent = { Text(stringResource(R.string.pharos_discover_body)) },
                )
                if (ui.discovering) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            }
            ui.found?.let { found ->
                item { Section(R.string.pharos_found) }
                if (found.isEmpty()) item { ListItem(headlineContent = { Text(stringResource(R.string.pharos_found_none)) }) }
                items(found, key = { "found-" + it.card.key }) { d ->
                    ListItem(
                        headlineContent = { Text(listOfNotNull(d.card.alias, d.card.callsign).joinToString(" · ")) },
                        supportingContent = {
                            val bridges = d.bridges.map { stringResource(it.label()) }.joinToString()
                            Column {
                                Text(stringResource(R.string.pharos_found_detail, d.snrDb, bridges))
                                Text(stringResource(d.access.label()))
                            }
                        },
                        trailingContent = {
                            if (d.trusted) {
                                Text(stringResource(R.string.pharos_already_trusted), style = MaterialTheme.typography.labelLarge)
                            } else if (d.access.open) {
                                TextButton(onClick = { vm.trust(d, null) }) { Text(stringResource(R.string.trust)) }
                            } else {
                                Column(horizontalAlignment = Alignment.End) {
                                    if (d.access.requests) {
                                        TextButton(onClick = { vm.trust(d, null) }) { Text(stringResource(R.string.request_access)) }
                                    }
                                    if (d.access.code) {
                                        TextButton(onClick = { codeFor = d to d.card.key }) { Text(stringResource(R.string.have_code)) }
                                    }
                                }
                            }
                        },
                    )
                }
                item {
                    Text(
                        stringResource(R.string.pharos_tofu_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }

    ui.list.firstOrNull { it.card.key == details }?.let { p ->
        PharosDetails(
            p,
            onDismiss = { details = null },
            onActivate = { vm.setActive(p.card.key); details = null },
            onVerify = { vm.setVerified(p.card.key, it) },
            onScan = { details = null; onScanVerify(p.card.key) },
            onEnterCode = { codeFor = null to p.card.key; details = null },
            onRequestAccess = { vm.requestAccess(p.card.key); details = null },
            onRemove = { vm.remove(p.card.key); details = null },
            onBlock = { vm.block(p.card.key); details = null },
            onShowCode = { details = null; onShowCode(p.card.key) },
        )
    }

    codeFor?.let { (found, key) ->
        AccessCodeDialog(
            onDismiss = { codeFor = null },
            onConfirm = { code ->
                codeFor = null
                if (found != null) vm.trust(found, code) else vm.register(key, code)
            },
        )
    }
}

@Composable
private fun AccessCodeDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.access_code)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.access_code_body))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text(stringResource(R.string.access_code)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(code) }, enabled = code.isNotBlank()) { Text(stringResource(R.string.go_ahead)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun PharosAccess.label(): Int = when {
    open -> R.string.pharos_access_open
    requests && code -> R.string.pharos_access_requests_code
    requests -> R.string.pharos_access_requests
    code -> R.string.pharos_access_code
    else -> R.string.pharos_access_closed
}

@Composable
private fun Section(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun ActiveCard(p: Pharos?, pinging: Boolean, onPing: () -> Unit, onAdmin: (key: String) -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painterResource(R.drawable.ic_cell_tower), null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    p?.let { listOfNotNull(it.card.alias, it.card.callsign).joinToString(" · ") }
                        ?: stringResource(R.string.pharos_none),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                if (p != null) {
                    if (p.verified) VerifiedBadge()
                    IconButton(onClick = { onAdmin(p.card.key) }) {
                        Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.pharos_admin))
                    }
                }
            }
            if (p != null) {
                Text(heardAgo(p.lastHeardMillis), style = MaterialTheme.typography.bodyLarge)
                p.snrDb?.let { Text(stringResource(R.string.pharos_snr, it), style = MaterialTheme.typography.bodyMedium) }
                Text(stringResource(p.registration.label()), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    p.bridges.sortedBy { it.ordinal }.forEach { k: ChatKind ->
                        AssistChip(onClick = {}, label = { Text(stringResource(k.label())) })
                    }
                }
                Button(onClick = onPing, enabled = !pinging) {
                    Text(stringResource(if (pinging) R.string.pinging else R.string.ping_now))
                }
                Text(
                    stringResource(R.string.ping_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(stringResource(R.string.pharos_none_body))
            }
        }
    }
}

private fun Registration.label(): Int = when (this) {
    Registration.REGISTERED -> R.string.registration_ok
    Registration.PENDING -> R.string.registration_pending
    Registration.REFUSED -> R.string.registration_refused
    Registration.NEEDS_CODE -> R.string.registration_needs_code
    Registration.NOT_REGISTERED -> R.string.registration_none
}

@Composable
private fun PharosDetails(
    p: Pharos,
    onDismiss: () -> Unit,
    onActivate: () -> Unit,
    onVerify: (Boolean) -> Unit,
    onScan: () -> Unit,
    onEnterCode: () -> Unit,
    onRequestAccess: () -> Unit,
    onRemove: () -> Unit,
    onBlock: () -> Unit,
    onShowCode: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(listOfNotNull(p.card.alias, p.card.callsign).joinToString(" · ")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (p.verified) R.string.pharos_verified_body else R.string.pharos_unverified_body))
                Text(stringResource(R.string.safety_number), style = MaterialTheme.typography.labelLarge)
                SafetyNumber(p.safetyNumber, Modifier.fillMaxWidth())
                Text(stringResource(p.registration.label()), style = MaterialTheme.typography.bodyMedium)
                if (p.registration == Registration.NEEDS_CODE) {
                    OutlinedButton(onClick = onEnterCode) { Text(stringResource(R.string.access_code_enter)) }
                }
                if (p.registration in setOf(Registration.NEEDS_CODE, Registration.REFUSED, Registration.NOT_REGISTERED)) {
                    OutlinedButton(onClick = onRequestAccess) { Text(stringResource(R.string.request_access)) }
                }
                VerifyControls(p.verified, onScan, onVerify)
                if (!p.active) OutlinedButton(onClick = onActivate) { Text(stringResource(R.string.use_this_pharos)) }
                OutlinedButton(onClick = onShowCode) {
                    Icon(painterResource(R.drawable.ic_qr_code), null)
                    Text(stringResource(R.string.show_qr), Modifier.padding(start = 8.dp))
                }
                OutlinedButton(onClick = onBlock) {
                    Icon(painterResource(R.drawable.ic_block), null)
                    Text(stringResource(R.string.pharos_block), Modifier.padding(start = 8.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        dismissButton = { TextButton(onClick = onRemove) { Text(stringResource(R.string.remove)) } },
    )
}
