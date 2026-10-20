package io.alvaroedo.skypost.ui.identity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.alvaroedo.skypost.R
import io.alvaroedo.skypost.ui.components.LocalSnackbar
import io.alvaroedo.skypost.ui.components.QrCode
import io.alvaroedo.skypost.ui.components.SafetyNumber
import io.alvaroedo.skypost.ui.components.copyToClipboard
import io.alvaroedo.skypost.ui.components.share
import kotlinx.coroutines.launch

/**
 * A `skypost:` code to scan in person. For a group invitation [secret] is set:
 * the code admits whoever has it, so copying and sharing ask first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScreen(
    title: String,
    heading: String,
    uri: String,
    onBack: () -> Unit,
    detail: String? = null,
    safetyNumber: String? = null,
    secret: Boolean = false,
    /** What the code is for; defaults to "show it so they can write to you". */
    body: String? = null,
) {
    val context = LocalContext.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.copied)
    var confirm by remember { mutableStateOf<(() -> Unit)?>(null) }
    val guarded: (() -> Unit) -> Unit = { action -> if (secret) confirm = action else action() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(heading, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            detail?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            QrCode(uri, Modifier.widthIn(max = 320.dp).fillMaxWidth())
            if (secret) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                    Text(
                        stringResource(R.string.group_invite_warning),
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            } else {
                Text(body ?: stringResource(R.string.my_code_body), textAlign = TextAlign.Center)
            }
            safetyNumber?.let {
                Text(stringResource(R.string.safety_number), style = MaterialTheme.typography.labelLarge)
                SafetyNumber(it)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    guarded {
                        copyToClipboard(context, title, uri)
                        scope.launch { snackbar.showSnackbar(copied) }
                    }
                }) {
                    Icon(painterResource(R.drawable.ic_content_copy), null)
                    Text(stringResource(R.string.copy), Modifier.padding(start = 8.dp))
                }
                OutlinedButton(onClick = { guarded { share(context, uri) } }) {
                    Icon(painterResource(R.drawable.ic_share), null)
                    Text(stringResource(R.string.share), Modifier.padding(start = 8.dp))
                }
            }
        }
    }

    confirm?.let { action ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.group_share_confirm_title)) },
            text = { Text(stringResource(R.string.group_invite_warning)) },
            confirmButton = { TextButton(onClick = { confirm = null; action() }) { Text(stringResource(R.string.go_ahead)) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
