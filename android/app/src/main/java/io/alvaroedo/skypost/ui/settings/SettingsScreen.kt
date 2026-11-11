package io.alvaroedo.skypost.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.alvaroedo.skypost.BuildConfig
import io.alvaroedo.skypost.R
import io.alvaroedo.skypost.data.Repository
import io.alvaroedo.skypost.radio.RadioModel
import io.alvaroedo.skypost.ui.components.GroupLabel
import io.alvaroedo.skypost.ui.components.LocalSnackbar
import io.alvaroedo.skypost.ui.components.SettingsCard
import io.alvaroedo.skypost.ui.components.SettingsTileRow
import io.alvaroedo.skypost.ui.components.SubTopBar
import io.alvaroedo.skypost.ui.components.openUrl
import io.alvaroedo.skypost.ui.onboarding.PassphraseDialog
import kotlinx.coroutines.launch

private val PING_CHOICES = listOf(0, 30, 60, 120)

private const val SOURCE_URL = "https://code.alvaroedo.io/alvaroemtnez/Skypost"

/**
 * Settings as MeshConnect lays them out: groups of rounded cards, each row an
 * icon disc, a title and what it is set to. Pharos and Radio open the same
 * cards as the chat list's top bar.
 */
@Composable
fun SettingsScreen(
    repo: Repository,
    onMyCode: () -> Unit,
    onPharos: () -> Unit,
    onSosContacts: () -> Unit,
    onRadio: () -> Unit,
    onBlocked: () -> Unit,
    onBack: () -> Unit,
) {
    val identity by repo.identity.collectAsStateWithLifecycle()
    val settings by repo.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var editProfile by remember { mutableStateOf(false) }
    var pickPing by remember { mutableStateOf(false) }
    var confirmEncryptAll by remember { mutableStateOf(false) }
    var exportPass by remember { mutableStateOf<String?>(null) }
    var askExportPass by remember { mutableStateOf(false) }
    var restoreFrom by remember { mutableStateOf<Uri?>(null) }
    val saved = stringResource(R.string.backup_saved)
    val failed = stringResource(R.string.backup_wrong_passphrase)
    val restored = stringResource(R.string.backup_restored)

    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val pass = exportPass
        exportPass = null
        if (uri != null && pass != null) {
            scope.launch {
                val data = repo.exportBackup(pass)
                context.contentResolver.openOutputStream(uri)?.use { it.write(data) }
                snackbar.showSnackbar(saved)
            }
        }
    }
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { restoreFrom = it }

    val pharos by repo.pharos.collectAsStateWithLifecycle()
    val active = pharos.firstOrNull { it.active && !it.blocked }
    val customRadio = stringResource(R.string.radio_custom)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { SubTopBar(stringResource(R.string.settings), onBack) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            GroupLabel(stringResource(R.string.settings_profile))
            SettingsCard {
                SettingsTileRow(
                    title = identity?.card?.alias.orEmpty(),
                    subtitle = identity?.card?.callsign ?: stringResource(R.string.no_callsign),
                    icon = R.drawable.ic_person,
                    onClick = { editProfile = true },
                )
                SettingsTileRow(
                    title = stringResource(R.string.my_code),
                    subtitle = stringResource(R.string.my_code_settings_body),
                    icon = R.drawable.ic_qr_code,
                    onClick = onMyCode,
                )
            }

            GroupLabel(stringResource(R.string.settings_safety))
            SettingsCard {
                val n = settings.sosChats?.size
                SettingsTileRow(
                    title = stringResource(R.string.sos_contacts),
                    subtitle = if (n == null) {
                        stringResource(R.string.sos_contacts_auto)
                    } else {
                        pluralStringResource(R.plurals.sos_contacts_count, n, n)
                    },
                    icon = R.drawable.ic_sos,
                    onClick = onSosContacts,
                )
                SettingsTileRow(
                    title = stringResource(R.string.encrypt_all),
                    subtitle = stringResource(R.string.encrypt_all_body),
                    icon = R.drawable.ic_lock,
                    onClick = { if (settings.encryptAll) repo.setEncryptAll(false) else confirmEncryptAll = true },
                    trailing = {
                        Switch(
                            checked = settings.encryptAll,
                            onCheckedChange = { on -> if (on) confirmEncryptAll = true else repo.setEncryptAll(false) },
                        )
                    },
                )
                SettingsTileRow(
                    title = stringResource(R.string.blocked_list),
                    subtitle = stringResource(R.string.blocked_list_body),
                    icon = R.drawable.ic_block,
                    onClick = onBlocked,
                )
            }

            GroupLabel(stringResource(R.string.settings_link))
            SettingsCard {
                SettingsTileRow(
                    title = stringResource(R.string.pharos_title),
                    subtitle = active?.let { listOfNotNull(it.card.alias, it.card.callsign).joinToString(" · ") }
                        ?: stringResource(R.string.pharos_none),
                    icon = R.drawable.ic_cell_tower,
                    onClick = onPharos,
                )
                SettingsTileRow(
                    title = stringResource(R.string.ping_interval),
                    subtitle = pingLabel(settings.pingIntervalMin),
                    icon = R.drawable.ic_schedule,
                    onClick = { pickPing = true },
                )
                SettingsTileRow(
                    title = stringResource(R.string.settings_radio),
                    subtitle = when (settings.radio) {
                        null -> stringResource(R.string.radio_none_chosen)
                        RadioModel.CUSTOM -> settings.customRadio.name.ifBlank { customRadio }
                        else -> settings.radio?.fullName
                    },
                    icon = R.drawable.ic_radio,
                    onClick = onRadio,
                )
            }

            GroupLabel(stringResource(R.string.settings_backup))
            SettingsCard {
                SettingsTileRow(
                    title = stringResource(R.string.backup_export),
                    subtitle = stringResource(R.string.backup_export_body),
                    icon = R.drawable.ic_backup,
                    onClick = { askExportPass = true },
                )
                SettingsTileRow(
                    title = stringResource(R.string.backup_restore),
                    subtitle = stringResource(R.string.backup_restore_body),
                    icon = R.drawable.ic_restore,
                    onClick = { openDoc.launch(arrayOf("*/*")) },
                )
            }

            GroupLabel(stringResource(R.string.settings_about))
            SettingsCard {
                SettingsTileRow(
                    title = stringResource(R.string.app_name),
                    subtitle = stringResource(R.string.about_body, BuildConfig.VERSION_NAME),
                    icon = R.drawable.ic_info,
                    onClick = null,
                )
                SettingsTileRow(
                    title = stringResource(R.string.about_source),
                    subtitle = SOURCE_URL,
                    icon = R.drawable.ic_code,
                    onClick = { openUrl(context, SOURCE_URL) },
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (editProfile) {
        identity?.let { id ->
            ProfileDialog(
                alias = id.card.alias,
                callsign = id.card.callsign.orEmpty(),
                validAlias = repo::validAlias,
                validCallsign = repo::validCallsign,
                onDismiss = { editProfile = false },
                onSave = { a, c -> repo.updateProfile(a, c.ifEmpty { null }); editProfile = false },
            )
        }
    }

    if (confirmEncryptAll) {
        AlertDialog(
            onDismissRequest = { confirmEncryptAll = false },
            icon = { Icon(painterResource(R.drawable.ic_lock), null) },
            title = { Text(stringResource(R.string.encrypt_all_confirm_title)) },
            text = { Text(stringResource(R.string.encrypt_confirm_body)) },
            confirmButton = {
                Button(
                    onClick = { confirmEncryptAll = false; repo.setEncryptAll(true) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text(stringResource(R.string.activate)) }
            },
            dismissButton = { TextButton(onClick = { confirmEncryptAll = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (pickPing) {
        AlertDialog(
            onDismissRequest = { pickPing = false },
            title = { Text(stringResource(R.string.ping_interval)) },
            text = {
                Column {
                    Text(stringResource(R.string.ping_note), style = MaterialTheme.typography.bodyMedium)
                    PING_CHOICES.forEach { m ->
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(selected = settings.pingIntervalMin == m, role = Role.RadioButton) {
                                    repo.setPingInterval(m)
                                    pickPing = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = settings.pingIntervalMin == m, onClick = null)
                            Text(pingLabel(m), Modifier.padding(start = 12.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickPing = false }) { Text(stringResource(R.string.close)) } },
        )
    }

    if (askExportPass) {
        PassphraseDialog(
            title = stringResource(R.string.backup_export),
            confirm = stringResource(R.string.go_ahead),
            body = stringResource(R.string.backup_passphrase_body),
            repeat = true,
            onDismiss = { askExportPass = false },
            onConfirm = { pass ->
                askExportPass = false
                exportPass = pass
                // skypost-20261021003512.skb: sorts by date, year to seconds.
                val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                createDoc.launch("skypost-$stamp.skb")
            },
        )
    }

    restoreFrom?.let { uri ->
        PassphraseDialog(
            title = stringResource(R.string.backup_restore),
            confirm = stringResource(R.string.restore),
            body = stringResource(R.string.backup_restore_warning),
            onDismiss = { restoreFrom = null },
            onConfirm = { pass ->
                restoreFrom = null
                scope.launch {
                    val data = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    val ok = data != null && repo.restoreBackup(data, pass)
                    snackbar.showSnackbar(if (ok) restored else failed)
                }
            },
        )
    }
}

@Composable
private fun pingLabel(min: Int): String =
    if (min == 0) stringResource(R.string.ping_off) else pluralStringResource(R.plurals.ping_every, min, min)

@Composable
private fun ProfileDialog(
    alias: String,
    callsign: String,
    validAlias: (String) -> Boolean,
    validCallsign: (String) -> Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var a by remember { mutableStateOf(alias) }
    var c by remember { mutableStateOf(callsign) }
    val ok = validAlias(a) && (c.isEmpty() || validCallsign(c))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_profile)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = a,
                    onValueChange = { a = it.trim().take(16) },
                    label = { Text(stringResource(R.string.alias)) },
                    isError = !validAlias(a),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = c,
                    onValueChange = { c = it.trim().uppercase().take(10) },
                    label = { Text(stringResource(R.string.callsign_optional)) },
                    isError = c.isNotEmpty() && !validCallsign(c),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                )
                Text(stringResource(R.string.profile_note), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(a, c) }, enabled = ok) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
