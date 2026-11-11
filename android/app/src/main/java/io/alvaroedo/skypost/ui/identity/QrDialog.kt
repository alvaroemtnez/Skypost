package io.alvaroedo.skypost.ui.identity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.alvaroedo.skypost.R
import io.alvaroedo.skypost.ui.components.QrCode
import io.alvaroedo.skypost.ui.components.SafetyNumber
import io.alvaroedo.skypost.ui.components.copyToClipboard
import io.alvaroedo.skypost.ui.components.share

/** A code to show in a popup: my own, a contact's or Pharos's, or a group invitation. */
data class QrPopup(
    val title: String,
    val heading: String,
    val uri: String,
    val detail: String? = null,
    val safetyNumber: String? = null,
    /** A group invitation: admits whoever has it, so copying and sharing ask first. */
    val secret: Boolean = false,
    /** What the code is for; defaults to "show it so they can write to you". */
    val body: String? = null,
)

/**
 * A `skypost:` code to scan in person, as a popup over the screen it came
 * from (MeshConnect's QR dialog): name, code, what it is for, safety number,
 * and copy/share as icons beside Done.
 */
@Composable
fun QrDialog(qr: QrPopup, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var confirm by remember { mutableStateOf<(() -> Unit)?>(null) }
    var copied by remember { mutableStateOf(false) }
    val guarded: (() -> Unit) -> Unit = { action -> if (qr.secret) confirm = action else action() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(qr.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(qr.heading, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    qr.detail?.let {
                        Text(it, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                QrCode(qr.uri, Modifier.widthIn(max = 280.dp).fillMaxWidth())
                if (qr.secret) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                        Text(
                            stringResource(R.string.group_invite_warning),
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    Text(
                        qr.body ?: stringResource(R.string.my_code_body),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
                qr.safetyNumber?.let {
                    Text(stringResource(R.string.safety_number), style = MaterialTheme.typography.labelLarge)
                    SafetyNumber(it)
                }
                if (copied) {
                    Text(
                        stringResource(R.string.copied),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
        dismissButton = {
            Row {
                IconButton(onClick = {
                    guarded {
                        copyToClipboard(context, qr.title, qr.uri)
                        copied = true
                    }
                }) { Icon(painterResource(R.drawable.ic_content_copy), stringResource(R.string.copy), Modifier.size(22.dp)) }
                IconButton(onClick = { guarded { share(context, qr.uri) } }) {
                    Icon(painterResource(R.drawable.ic_share), stringResource(R.string.share), Modifier.size(22.dp))
                }
            }
        },
    )

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
