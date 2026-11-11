package io.alvaroedo.skypost.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.alvaroedo.skypost.R
import io.alvaroedo.skypost.ui.components.SubTopBar
import io.alvaroedo.skypost.data.Repository
import io.alvaroedo.skypost.model.ChatKind
import io.alvaroedo.skypost.ui.components.Avatar
import io.alvaroedo.skypost.ui.components.label

/**
 * Who the SOS button alerts: automatic (every group) or a chosen set of
 * chats of any kind; bridged ones go through the Pharos.
 */
@Composable
fun SosContactsScreen(repo: Repository, onBack: () -> Unit) {
    val chats by repo.chats.collectAsStateWithLifecycle()
    val settings by repo.settings.collectAsStateWithLifecycle()
    val chosen = settings.sosChats
    val auto = chosen == null

    Scaffold(
        topBar = { SubTopBar(stringResource(R.string.sos_contacts), onBack) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                ListItem(
                    modifier = Modifier.toggleable(value = auto, role = Role.Switch) { on ->
                        repo.setSosChats(
                            if (on) null else chats.filter { it.kind == ChatKind.GROUP }.map { it.id }.toSet(),
                        )
                    },
                    headlineContent = { Text(stringResource(R.string.sos_contacts_auto)) },
                    supportingContent = { Text(stringResource(R.string.sos_contacts_auto_body)) },
                    trailingContent = { Switch(checked = auto, onCheckedChange = null) },
                )
                HorizontalDivider()
            }
            items(chats, key = { it.id }) { chat ->
                val on = if (auto) chat.kind == ChatKind.GROUP else chat.id in chosen.orEmpty()
                ListItem(
                    modifier = Modifier.toggleable(value = on, enabled = !auto, role = Role.Checkbox) { now ->
                        val set = chosen.orEmpty()
                        repo.setSosChats(if (now) set + chat.id else set - chat.id)
                    },
                    leadingContent = { Avatar(chat.title, chat.kind, 40.dp) },
                    headlineContent = { Text(chat.title) },
                    supportingContent = { Text(stringResource(chat.kind.label())) },
                    trailingContent = { Checkbox(checked = on, onCheckedChange = null, enabled = !auto) },
                )
            }
            item {
                Text(
                    stringResource(R.string.sos_contacts_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}
