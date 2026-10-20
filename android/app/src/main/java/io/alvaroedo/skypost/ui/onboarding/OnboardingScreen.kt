package io.alvaroedo.skypost.ui.onboarding

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.alvaroedo.skypost.R
import io.alvaroedo.skypost.data.Repository
import io.alvaroedo.skypost.ui.components.LocalSnackbar
import kotlinx.coroutines.launch

/** First run: create an identity (primary) or restore a backup (secondary). */
@Composable
fun OnboardingScreen(repo: Repository) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var restoreFrom by remember { mutableStateOf<Uri?>(null) }
    val pickBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { restoreFrom = it }
    val snackbar = LocalSnackbar.current
    val context = LocalContext.current
    // Outside the dialog: closing it must not cancel the restore.
    val scope = rememberCoroutineScope()

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()
                .verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_cell_tower),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(72.dp),
            )
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.onboarding_body), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(32.dp))
            if (creating) {
                CreateIdentityForm(repo, onCancel = { creating = false })
            } else {
                Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text(stringResource(R.string.onboarding_create))
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { pickBackup.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Text(stringResource(R.string.onboarding_restore))
                }
            }
        }
    }

    restoreFrom?.let { uri ->
        val failed = stringResource(R.string.backup_wrong_passphrase)
        PassphraseDialog(
            title = stringResource(R.string.onboarding_restore),
            confirm = stringResource(R.string.restore),
            body = stringResource(R.string.backup_restore_warning),
            onDismiss = { restoreFrom = null },
            onConfirm = { pass ->
                restoreFrom = null
                scope.launch {
                    val data = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (data == null || !repo.restoreBackup(data, pass)) snackbar.showSnackbar(failed)
                }
            },
        )
    }
}

@Composable
private fun CreateIdentityForm(repo: Repository, onCancel: () -> Unit) {
    var alias by rememberSaveable { mutableStateOf("") }
    var callsign by rememberSaveable { mutableStateOf("") }
    val aliasOk = repo.validAlias(alias)
    val callOk = callsign.isEmpty() || repo.validCallsign(callsign)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = alias,
            onValueChange = { alias = it.trim().take(16) },
            label = { Text(stringResource(R.string.alias)) },
            supportingText = { Text(stringResource(R.string.alias_help)) },
            isError = alias.isNotEmpty() && !aliasOk,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = callsign,
            onValueChange = { callsign = it.trim().uppercase().take(10) },
            label = { Text(stringResource(R.string.callsign_optional)) },
            supportingText = { Text(stringResource(R.string.callsign_help)) },
            isError = !callOk,
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { repo.createIdentity(alias, callsign.ifEmpty { null }) },
            enabled = aliasOk && callOk,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Text(stringResource(R.string.onboarding_create))
        }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
    }
}

/**
 * Asks for a backup passphrase (at least 8 characters). [repeat]: a second
 * field must match (when choosing one, for an export).
 */
@Composable
fun PassphraseDialog(
    title: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    body: String? = null,
    repeat: Boolean = false,
) {
    var pass by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val mismatch = repeat && again.isNotEmpty() && again != pass
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                body?.let { Text(it) }
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text(stringResource(R.string.passphrase)) },
                    supportingText = { Text(stringResource(R.string.passphrase_help)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                if (repeat) {
                    OutlinedTextField(
                        value = again,
                        onValueChange = { again = it },
                        label = { Text(stringResource(R.string.passphrase_repeat)) },
                        supportingText = { if (mismatch) Text(stringResource(R.string.passphrase_mismatch)) },
                        isError = mismatch,
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(pass) },
                enabled = pass.length >= 8 && (!repeat || again == pass),
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
