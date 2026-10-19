package io.alvaroedo.skypost.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import io.alvaroedo.skypost.ui.components.LocalSnackbar
import io.alvaroedo.skypost.ui.components.SettingRow
import io.alvaroedo.skypost.ui.components.openUrl
import io.alvaroedo.skypost.ui.onboarding.PassphraseDialog
import kotlinx.coroutines.launch

private val PING_CHOICES = listOf(0, 30, 60, 120)

private const val SOURCE_URL = "https://code.alvaroedo.io/alvaroemtnez/Skypost"

@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
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
                Section(R.string.settings_profile)
                SettingRow(
                    modifier = Modifier.clickable { editProfile = true },
                    leadingContent = { Icon(painterResource(R.drawable.ic_person), null) },
                    headlineContent = { Text(identity?.card?.alias.orEmpty()) },
                    supportingContent = {
                        Text(identity?.card?.callsign ?: stringResource(R.string.no_callsign))
                    },
                )
                SettingRow(
                    modifier = Modifier.clickable(onClick = onMyCode),
                    leadingContent = { Icon(painterResource(R.drawable.ic_qr_code), null) },
                    headlineContent = { Text(stringResource(R.string.my_code)) },
                    supportingContent = { Text(stringResource(R.string.my_code_settings_body)) },
                )

                Section(R.string.settings_safety)
                SettingRow(
                    modifier = Modifier.clickable(onClick = onSosContacts),
                    leadingContent = { Icon(painterResource(R.drawable.ic_sos), null) },
                    headlineContent = { Text(stringResource(R.string.sos_contacts)) },
                    supportingContent = {
                        val n = settings.sosChats?.size
                        Text(
                            if (n == null) {
                                stringResource(R.string.sos_contacts_auto)
                            } else {
                                pluralStringResource(R.plurals.sos_contacts_count, n, n)
                            },
                        )
                    },
                )
                SettingRow(
                    modifier = Modifier.clickable {
                        if (settings.encryptAll) repo.setEncryptAll(false) else confirmEncryptAll = true
                    },
                    leadingContent = { Icon(painterResource(R.drawable.ic_lock), null) },
                    headlineContent = { Text(stringResource(R.string.encrypt_all)) },
                    supportingContent = { Text(stringResource(R.string.encrypt_all_body)) },
                    trailingContent = {
                        Switch(
                            checked = settings.encryptAll,
                            onCheckedChange = { on -> if (on) confirmEncryptAll = true else repo.setEncryptAll(false) },
                        )
                    },
                )

                SettingRow(
                    modifier = Modifier.clickable(onClick = onBlocked),
                    leadingContent = { Icon(painterResource(R.drawable.ic_block), null) },
                    headlineContent = { Text(stringResource(R.string.blocked_list)) },
                    supportingContent = { Text(stringResource(R.string.blocked_list_body)) },
                )

                Section(R.string.pharos_title)
                SettingRow(
                    modifier = Modifier.clickable(onClick = onPharos),
                    leadingContent = { Icon(painterResource(R.drawable.ic_cell_tower), null) },
                    headlineContent = { Text(stringResource(R.string.pharos_manage)) },
                )
                SettingRow(
                    modifier = Modifier.clickable { pickPing = true },
                    leadingContent = { Icon(painterResource(R.drawable.ic_schedule), null) },
                    headlineContent = { Text(stringResource(R.string.ping_interval)) },
                    supportingContent = { Text(pingLabel(settings.pingIntervalMin)) },
                )

                Section(R.string.settings_radio)
                SettingRow(
                    modifier = Modifier.clickable(onClick = onRadio),
                    leadingContent = { Icon(painterResource(R.drawable.ic_radio), null) },
                    headlineContent = { Text(stringResource(R.string.settings_radio_rig)) },
                    supportingContent = { Text(stringResource(R.string.settings_band_body)) },
                )

                Section(R.string.settings_backup)
                SettingRow(
                    modifier = Modifier.clickable { askExportPass = true },
                    leadingContent = { Icon(painterResource(R.drawable.ic_backup), null) },
                    headlineContent = { Text(stringResource(R.string.backup_export)) },
                    supportingContent = { Text(stringResource(R.string.backup_export_body)) },
                )
                SettingRow(
                    modifier = Modifier.clickable { openDoc.launch(arrayOf("*/*")) },
                    leadingContent = { Icon(painterResource(R.drawable.ic_restore), null) },
                    headlineContent = { Text(stringResource(R.string.backup_restore)) },
                    supportingContent = { Text(stringResource(R.string.backup_restore_body)) },
                )

                Section(R.string.settings_about)
                SettingRow(
                    leadingContent = { Icon(painterResource(R.drawable.ic_info), null) },
                    headlineContent = { Text(stringResource(R.string.app_name)) },
                    supportingContent = { Text(stringResource(R.string.about_body, BuildConfig.VERSION_NAME)) },
                )
                SettingRow(
                    modifier = Modifier.clickable { openUrl(context, SOURCE_URL) },
                    leadingContent = { Icon(painterResource(R.drawable.ic_code), null) },
                    headlineContent = { Text(stringResource(R.string.about_source)) },
                    supportingContent = { Text(SOURCE_URL) },
                )
            }
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
            onDismiss = { askExportPass = false },
            onConfirm = { pass ->
                askExportPass = false
                exportPass = pass
                createDoc.launch("skypost-copia.skb")
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
private fun Section(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

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
