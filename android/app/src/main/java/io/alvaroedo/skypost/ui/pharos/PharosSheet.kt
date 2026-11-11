package io.alvaroedo.skypost.ui.pharos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.alvaroedo.skypost.R
import io.alvaroedo.skypost.model.Pharos
import io.alvaroedo.skypost.model.Discovered
import io.alvaroedo.skypost.model.PharosAccess
import io.alvaroedo.skypost.model.Registration
import io.alvaroedo.skypost.ui.components.IconDisc
import io.alvaroedo.skypost.ui.components.InfoTile
import io.alvaroedo.skypost.ui.components.QuickAction
import io.alvaroedo.skypost.ui.components.SafetyNumber
import io.alvaroedo.skypost.ui.components.SheetActionRow
import io.alvaroedo.skypost.ui.components.SheetHeader
import io.alvaroedo.skypost.ui.components.SheetHeading
import io.alvaroedo.skypost.ui.components.SignalBars
import io.alvaroedo.skypost.ui.components.VerifiedBadge
import io.alvaroedo.skypost.ui.components.VerifyControls
import io.alvaroedo.skypost.ui.components.clipboardText
import io.alvaroedo.skypost.ui.components.heardAgo
import io.alvaroedo.skypost.ui.components.label
import io.alvaroedo.skypost.ui.components.pharosBars
import io.alvaroedo.skypost.ui.components.recentSnr

/**
 * The Pharos card, over whatever screen opened it (the top bar's signal
 * button, or Settings), like MeshConnect's node card: who it is, a row of
 * quick actions with Manage standing out (it opens the admin screen), facts
 * as tiles, then the other trusted Pharos and ways to add one.
 *
 * [onCode] takes a pasted code (false if it is not one); the others close
 * the card and go somewhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PharosSheet(
    vm: PharosViewModel,
    onDismiss: () -> Unit,
    onCode: (String?) -> Boolean,
    onScan: () -> Unit,
    onShowCode: (key: String) -> Unit,
    onAdmin: (key: String) -> Unit,
    onScanVerify: (key: String) -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var details by remember { mutableStateOf<String?>(null) }
    /** Asking for an access code: to trust a discovered Pharos, or to register with a trusted one. */
    var codeFor by remember { mutableStateOf<Pair<Discovered?, String>?>(null) }
    var notACode by remember { mutableStateOf(false) }
    val active = ui.list.firstOrNull { it.active }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.navigationBarsPadding().padding(horizontal = 16.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp)) {
            item {
                if (active != null) {
                    ActivePart(
                        p = active,
                        pinging = ui.pinging,
                        lastPing = ui.lastPing,
                        onPing = vm::ping,
                        onAdmin = { onAdmin(active.card.key) },
                        onShowCode = { onShowCode(active.card.key) },
                        onDetails = { details = active.card.key },
                        onEnterCode = { codeFor = null to active.card.key },
                        onRequestAccess = { vm.requestAccess(active.card.key) },
                    )
                } else {
                    SheetHeader(
                        title = stringResource(R.string.pharos_none),
                        subtitle = null,
                        mark = { IconDisc(R.drawable.ic_cell_tower, size = 56.dp) },
                    )
                    Text(
                        stringResource(R.string.pharos_none_body),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            item { SheetHeading(stringResource(R.string.pharos_trusted)) }
            if (ui.list.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.pharos_trusted_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                    )
                }
            }
            items(ui.list, key = { it.card.key }) { p ->
                val v = stringResource(if (p.verified) R.string.verified else R.string.not_verified)
                SheetActionRow(
                    icon = R.drawable.ic_cell_tower,
                    label = listOfNotNull(p.card.alias, p.card.callsign).joinToString(" · "),
                    detail = "$v · ${heardAgo(p.lastHeardMillis)}",
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (p.verified) VerifiedBadge()
                            RadioButton(selected = p.active, onClick = { vm.setActive(p.card.key) })
                        }
                    },
                    onClick = { details = p.card.key },
                )
            }
            item {
                SheetHeading(stringResource(R.string.pharos_add))
                SheetActionRow(
                    R.drawable.ic_qr_code_scanner,
                    stringResource(R.string.pharos_add_qr),
                    detail = stringResource(R.string.pharos_add_qr_body),
                    onClick = onScan,
                )
                SheetActionRow(
                    R.drawable.ic_content_paste,
                    stringResource(R.string.paste_code),
                    detail = if (notACode) stringResource(R.string.code_not_recognised) else null,
                ) { notACode = !onCode(clipboardText(context)) }
                SheetActionRow(
                    R.drawable.ic_radar,
                    stringResource(R.string.pharos_discover),
                    detail = stringResource(R.string.pharos_discover_body),
                    enabled = !ui.discovering,
                    onClick = vm::discover,
                )
                if (ui.discovering) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 8.dp))
            }
            ui.found?.let { found ->
                item { SheetHeading(stringResource(R.string.pharos_found)) }
                if (found.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.pharos_found_none),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        )
                    }
                }
                items(found, key = { "found-" + it.card.key }) { d -> FoundRow(d, vm, onCode = { codeFor = d to d.card.key }) }
                item {
                    Text(
                        stringResource(R.string.pharos_tofu_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
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

/** The active Pharos: header, quick actions (Manage stands out), facts. */
@Composable
private fun ActivePart(
    p: Pharos,
    pinging: Boolean,
    lastPing: Boolean?,
    onPing: () -> Unit,
    onAdmin: () -> Unit,
    onShowCode: () -> Unit,
    onDetails: () -> Unit,
    onEnterCode: () -> Unit,
    onRequestAccess: () -> Unit,
) {
    val snr = recentSnr(p)
    SheetHeader(
        title = p.card.alias,
        subtitle = listOfNotNull(p.card.callsign, heardAgo(p.lastHeardMillis)).joinToString(" · "),
        mark = { IconDisc(R.drawable.ic_cell_tower, size = 56.dp, highlighted = true) },
        trailing = { if (p.verified) VerifiedBadge() },
    )
    Row(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        QuickAction(R.drawable.ic_sensors, stringResource(if (pinging) R.string.pinging else R.string.ping_short), enabled = !pinging, onClick = onPing)
        // Manage is the one that leads to a whole screen: filled, so it stands out.
        QuickAction(R.drawable.ic_tune, stringResource(R.string.manage), on = true, onClick = onAdmin)
        QuickAction(R.drawable.ic_qr_code, stringResource(R.string.qr_short), onClick = onShowCode)
        QuickAction(R.drawable.ic_verified, stringResource(R.string.verify_short), on = false, onClick = onDetails)
    }
    lastPing?.takeIf { !pinging }?.let { ok ->
        Text(
            stringResource(if (ok) R.string.ping_ok else R.string.ping_silent),
            style = MaterialTheme.typography.labelLarge,
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
        )
    }
    Spacer(Modifier.height(12.dp))
    // Two short facts side by side; the bridges, which run long, get a line of their own.
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        InfoTile(
            stringResource(R.string.pharos_hears_you),
            snr?.let { stringResource(R.string.db_value, it) } ?: stringResource(R.string.pharos_not_heard),
            Modifier.weight(1f),
            fit = true,
            leading = { SignalBars(pharosBars(snr), live = snr != null) },
        )
        InfoTile(
            stringResource(R.string.pharos_registration),
            stringResource(p.registration.shortLabel()),
            Modifier.weight(1f),
            fit = true,
            valueColor = if (p.registration == Registration.REFUSED) MaterialTheme.colorScheme.error else Color.Unspecified,
        )
    }
    Spacer(Modifier.height(8.dp))
    InfoTile(
        stringResource(R.string.pharos_bridges),
        p.bridges.sortedBy { it.ordinal }.map { stringResource(it.label()) }.joinToString(" · ").ifEmpty { "—" },
        Modifier.fillMaxWidth(),
    )
    if (p.registration == Registration.NEEDS_CODE) {
        SheetActionRow(R.drawable.ic_key, stringResource(R.string.access_code_enter), onClick = onEnterCode)
    }
    if (p.registration in setOf(Registration.NEEDS_CODE, Registration.REFUSED, Registration.NOT_REGISTERED)) {
        SheetActionRow(R.drawable.ic_person_add, stringResource(R.string.request_access), onClick = onRequestAccess)
    }
    Text(
        stringResource(R.string.ping_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
    )
}

/** A Pharos that answered a discovery: trust it, ask for access, or give a code. */
@Composable
private fun FoundRow(d: Discovered, vm: PharosViewModel, onCode: () -> Unit) {
    val bridges = d.bridges.map { stringResource(it.label()) }.joinToString()
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(listOfNotNull(d.card.alias, d.card.callsign).joinToString(" · "), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.pharos_found_detail, d.snrDb, bridges),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(d.access.label()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            d.trusted -> Text(stringResource(R.string.pharos_already_trusted), style = MaterialTheme.typography.labelLarge)
            d.access.open -> TextButton(onClick = { vm.trust(d, null) }) { Text(stringResource(R.string.trust)) }
            else -> Column(horizontalAlignment = Alignment.End) {
                if (d.access.requests) {
                    TextButton(onClick = { vm.trust(d, null) }) { Text(stringResource(R.string.request_access)) }
                }
                if (d.access.code) {
                    TextButton(onClick = onCode) { Text(stringResource(R.string.have_code)) }
                }
            }
        }
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

/** The registration in a few words, for the card's tile. */
private fun Registration.shortLabel(): Int = when (this) {
    Registration.REGISTERED -> R.string.registration_ok
    Registration.PENDING -> R.string.registration_pending_short
    Registration.REFUSED -> R.string.registration_refused_short
    Registration.NEEDS_CODE -> R.string.registration_needs_code_short
    Registration.NOT_REGISTERED -> R.string.registration_none
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
        dismissButton = {
            TextButton(onClick = onRemove) { Text(stringResource(R.string.remove), color = MaterialTheme.colorScheme.error) }
        },
    )
}
