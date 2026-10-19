package io.alvaroedo.skypost.ui.newchat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.alvaroedo.skypost.R
import io.alvaroedo.skypost.model.ChatKind
import io.alvaroedo.skypost.ui.components.Avatar
import io.alvaroedo.skypost.ui.components.LocalSnackbar
import io.alvaroedo.skypost.ui.components.PickPhone
import io.alvaroedo.skypost.ui.components.clipboardText
import io.alvaroedo.skypost.ui.components.icon
import io.alvaroedo.skypost.ui.components.label
import io.alvaroedo.skypost.ui.components.readPickedPhone
import io.alvaroedo.skypost.ui.components.scanQr
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewChatScaffold(title: String, onBack: () -> Unit, content: LazyListScope.() -> Unit) {
    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbar.current) },
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
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 24.dp), content = content)
    }
}

/** Step 1: which kind of chat. */
@Composable
fun NewChatScreen(vm: NewChatViewModel, onKind: (ChatKind) -> Unit, onPharos: () -> Unit, onBack: () -> Unit) {
    val hasPharos by vm.hasPharos.collectAsStateWithLifecycle()
    NewChatScaffold(stringResource(R.string.new_chat), onBack) {
        items(ChatKind.entries) { kind ->
            val blocked = kind.bridged && !hasPharos
            ListItem(
                modifier = Modifier.clickable { if (blocked) onPharos() else onKind(kind) },
                leadingContent = { Icon(painterResource(kind.icon()), null) },
                headlineContent = { Text(stringResource(kind.label())) },
                supportingContent = {
                    Text(stringResource(if (blocked) R.string.needs_pharos else kind.description()))
                },
            )
        }
    }
}

private fun ChatKind.description(): Int = when (this) {
    ChatKind.DIRECT -> R.string.kind_direct_body
    ChatKind.GROUP -> R.string.kind_group_body
    ChatKind.SIGNAL -> R.string.kind_signal_body
    ChatKind.WHATSAPP -> R.string.kind_whatsapp_body
    ChatKind.MESHCORE -> R.string.kind_meshcore_body
}

/** Step 2: pick or add the contact, group or bridged address. */
@Composable
fun NewChatOfScreen(
    kind: ChatKind,
    vm: NewChatViewModel,
    onCode: (String?) -> Boolean,
    onOpenChat: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val notACode = stringResource(R.string.code_not_recognised)
    val noScanner = stringResource(R.string.scanner_unavailable)
    val offer: (String?) -> Unit = { text -> if (!onCode(text)) scope.launch { snackbar.showSnackbar(notACode) } }
    val scan = { scanQr(context, onText = offer, onFailure = { scope.launch { snackbar.showSnackbar(noScanner) } }) }
    val paste = { offer(clipboardText(context)) }

    when (kind) {
        ChatKind.DIRECT -> {
            val contacts by vm.contacts.collectAsStateWithLifecycle()
            NewChatScaffold(stringResource(R.string.kind_direct), onBack) {
                codeActions(R.string.scan_contact, scan, paste)
                header(R.string.contacts)
                if (contacts.isEmpty()) hint(R.string.contacts_empty)
                items(contacts, key = { it.card.key }) { c ->
                    ListItem(
                        modifier = Modifier.clickable { onOpenChat(vm.chatWith(c.card.key)) },
                        leadingContent = { Avatar(c.card.alias, ChatKind.DIRECT, 40.dp) },
                        headlineContent = { Text(c.card.alias) },
                        supportingContent = c.card.callsign?.let { { Text(it) } },
                    )
                }
            }
        }
        ChatKind.GROUP -> {
            val groups by vm.groups.collectAsStateWithLifecycle()
            var naming by rememberSaveable { mutableStateOf(false) }
            NewChatScaffold(stringResource(R.string.kind_group), onBack) {
                item {
                    ListItem(
                        modifier = Modifier.clickable { naming = true },
                        leadingContent = { Icon(painterResource(R.drawable.ic_add), null) },
                        headlineContent = { Text(stringResource(R.string.group_create)) },
                    )
                }
                codeActions(R.string.scan_group, scan, paste)
                header(R.string.groups)
                if (groups.isEmpty()) hint(R.string.groups_empty)
                items(groups, key = { it.id }) { g ->
                    ListItem(
                        modifier = Modifier.clickable { onOpenChat(vm.chatInGroup(g.id)) },
                        leadingContent = { Avatar(g.name, ChatKind.GROUP, 40.dp) },
                        headlineContent = { Text(g.name) },
                        supportingContent = { Text(g.members.joinToString()) },
                    )
                }
            }
            if (naming) {
                GroupNameDialog(onDismiss = { naming = false }) { name ->
                    naming = false
                    vm.createGroup(name)?.let(onOpenChat)
                }
            }
        }
        ChatKind.SIGNAL, ChatKind.WHATSAPP -> PhoneChat(kind, vm, onOpenChat, onBack)
        ChatKind.MESHCORE -> NewChatScaffold(stringResource(R.string.kind_meshcore), onBack) {
            codeActions(R.string.scan_meshcore, scan, paste)
            hint(R.string.meshcore_hint)
        }
    }
}

private fun LazyListScope.codeActions(scanLabel: Int, onScan: () -> Unit, onPaste: () -> Unit) {
    item {
        ListItem(
            modifier = Modifier.clickable(onClick = onScan),
            leadingContent = { Icon(painterResource(R.drawable.ic_qr_code_scanner), null) },
            headlineContent = { Text(stringResource(scanLabel)) },
        )
        ListItem(
            modifier = Modifier.clickable(onClick = onPaste),
            leadingContent = { Icon(painterResource(R.drawable.ic_content_paste), null) },
            headlineContent = { Text(stringResource(R.string.paste_code)) },
        )
        HorizontalDivider()
    }
}

private fun LazyListScope.header(text: Int) = item {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

private fun LazyListScope.hint(text: Int) = item {
    ListItem(
        headlineContent = { Text(stringResource(text)) },
        colors = ListItemDefaults.colors(headlineColor = MaterialTheme.colorScheme.onSurfaceVariant),
    )
}

@Composable
private fun PhoneChat(kind: ChatKind, vm: NewChatViewModel, onOpenChat: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var number by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    val pick = rememberLauncherForActivityResult(PickPhone()) { uri ->
        uri?.let { readPickedPhone(context, it) }?.let { p ->
            number = p.number
            name = p.name.orEmpty()
        }
    }
    val valid = vm.address(kind, number) != null
    NewChatScaffold(stringResource(kind.label()), onBack) {
        item {
            ListItem(
                modifier = Modifier.clickable { pick.launch(Unit) },
                leadingContent = { Icon(painterResource(R.drawable.ic_contacts), null) },
                headlineContent = { Text(stringResource(R.string.pick_phone_contact)) },
            )
            HorizontalDivider()
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = number,
                    onValueChange = { number = it },
                    label = {
                        Text(stringResource(if (kind == ChatKind.SIGNAL) R.string.phone_or_username else R.string.phone))
                    },
                    supportingText = { Text(stringResource(R.string.phone_help)) },
                    isError = number.isNotBlank() && !valid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (kind == ChatKind.SIGNAL) KeyboardType.Text else KeyboardType.Phone,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(32) },
                    label = { Text(stringResource(R.string.name_optional)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { vm.openBridged(kind, number, name)?.let(onOpenChat) },
                    enabled = valid,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.open_chat)) }
                Text(
                    stringResource(R.string.bridged_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GroupNameDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.group_create)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.group_create_body))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(32) },
                    label = { Text(stringResource(R.string.group_name)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.create)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
