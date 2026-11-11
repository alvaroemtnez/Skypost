package io.alvaroedo.skypost.ui.newchat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.alvaroedo.skypost.R
import io.alvaroedo.skypost.model.ChatKind
import io.alvaroedo.skypost.model.Recent
import io.alvaroedo.skypost.ui.components.Avatar
import io.alvaroedo.skypost.ui.components.MenuItem
import io.alvaroedo.skypost.ui.components.PickPhone
import io.alvaroedo.skypost.ui.components.SettingsTileRow
import io.alvaroedo.skypost.ui.components.SkyDropdownMenu
import io.alvaroedo.skypost.ui.components.SwipeRow
import io.alvaroedo.skypost.ui.components.VerifiedBadge
import io.alvaroedo.skypost.ui.components.clipboardText
import io.alvaroedo.skypost.ui.components.icon
import io.alvaroedo.skypost.ui.components.label
import io.alvaroedo.skypost.ui.components.readPickedPhone

/** The rows inside the sheet sit on its colour, not on a surface band. */
@Composable
private fun clearItem() = ListItemDefaults.colors(containerColor = Color.Transparent)

/**
 * New chat, as a card over the chat list (MeshConnect's channel sheet): first
 * which kind, then, in the same card, the contact, group or address. The
 * phone's contact picker is its own full-screen activity, so it covers the
 * card rather than stacking on it, and the card is there when it returns.
 *
 * [onCode] takes a pasted code (false if it is not one); [onScan] and
 * [onPharos] close the card and go to their screens; [onOpenChat] opens the
 * chat (the caller closes the card).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatSheet(
    vm: NewChatViewModel,
    onDismiss: () -> Unit,
    onCode: (String?) -> Boolean,
    onScan: () -> Unit,
    onPharos: () -> Unit,
    onOpenChat: (String) -> Unit,
) {
    var kind by rememberSaveable { mutableStateOf<ChatKind?>(null) }
    val hasPharos by vm.hasPharos.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (kind != null) {
                IconButton(onClick = { kind = null }) {
                    Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                }
            } else {
                Spacer(Modifier.width(12.dp))
            }
            Text(
                stringResource(kind?.label() ?: R.string.new_chat),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        val k = kind
        if (k == null) {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(ChatKind.entries) { entry ->
                    val blocked = entry.bridged && !hasPharos
                    SettingsTileRow(
                        title = stringResource(entry.label()),
                        subtitle = stringResource(if (blocked) R.string.needs_pharos else entry.description()),
                        icon = entry.icon(),
                        onClick = { if (blocked) onPharos() else kind = entry },
                    )
                }
            }
        } else {
            NewChatOf(k, vm, onCode, onScan, onOpenChat)
        }
        }
    }
}

@Composable
private fun StepList(content: LazyListScope.() -> Unit) {
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), content = content)
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
private fun NewChatOf(
    kind: ChatKind,
    vm: NewChatViewModel,
    onCode: (String?) -> Boolean,
    onScan: () -> Unit,
    onOpenChat: (String) -> Unit,
) {
    val context = LocalContext.current
    // A paste that was not a code says so under the button (a snackbar would sit under the card).
    var bad by remember { mutableStateOf(false) }
    val scan = onScan
    val paste = { bad = !onCode(clipboardText(context)) }
    var forget by remember { mutableStateOf<Forget?>(null) }

    when (kind) {
        ChatKind.DIRECT -> {
            val contacts by vm.contacts.collectAsStateWithLifecycle()
            StepList {
                header(R.string.contacts)
                if (contacts.isEmpty()) hint(R.string.contacts_empty)
                items(contacts, key = { it.card.key }) { c ->
                    EntryRow(
                        title = c.card.alias,
                        subtitle = c.card.callsign,
                        kind = ChatKind.DIRECT,
                        blocked = c.blocked,
                        verified = c.verified,
                        onOpen = { onOpenChat(vm.chatWith(c.card.key)) },
                        onBlock = { vm.setContactBlocked(c.card.key, !c.blocked) },
                        onDelete = { forget = Forget(c.card.alias, R.string.contact_delete_body) { vm.removeContact(c.card.key) } },
                    )
                }
                item { HorizontalDivider() }
                codeActions(R.string.scan_contact, scan, paste, bad)
            }
        }
        ChatKind.GROUP -> {
            val groups by vm.groups.collectAsStateWithLifecycle()
            var naming by rememberSaveable { mutableStateOf(false) }
            StepList {
                header(R.string.groups)
                if (groups.isEmpty()) hint(R.string.groups_empty)
                items(groups, key = { it.id }) { g ->
                    EntryRow(
                        title = g.name,
                        subtitle = g.members.joinToString(),
                        kind = ChatKind.GROUP,
                        blocked = g.blocked,
                        onOpen = { onOpenChat(vm.chatInGroup(g.id)) },
                        onBlock = { vm.setGroupBlocked(g.id, !g.blocked) },
                        onDelete = { forget = Forget(g.name, R.string.group_leave_body) { vm.leaveGroup(g.id) } },
                    )
                }
                item {
                    HorizontalDivider()
                    ListItem(
                        modifier = Modifier.clickable { naming = true },
                        leadingContent = { Icon(painterResource(R.drawable.ic_add), null) },
                        headlineContent = { Text(stringResource(R.string.group_create)) },
                        colors = clearItem(),
                    )
                }
                codeActions(R.string.scan_group, scan, paste, bad)
            }
            if (naming) {
                GroupNameDialog(onDismiss = { naming = false }) { name ->
                    naming = false
                    vm.createGroup(name)?.let(onOpenChat)
                }
            }
        }
        ChatKind.SIGNAL, ChatKind.WHATSAPP -> PhoneChat(kind, vm, onOpenChat)
        ChatKind.MESHCORE -> {
            val recents by vm.recents.collectAsStateWithLifecycle()
            val blockedKeys by vm.blockedKeys.collectAsStateWithLifecycle()
            StepList {
                recentItems(recents.filter { it.kind == ChatKind.MESHCORE }, blockedKeys, vm, onOpenChat)
                codeActions(R.string.scan_meshcore, scan, paste, bad)
                hint(R.string.meshcore_hint)
            }
        }
    }
    forget?.let { f ->
        AlertDialog(
            onDismissRequest = { forget = null },
            title = { Text(stringResource(R.string.delete_named, f.name)) },
            text = { Text(stringResource(f.body)) },
            confirmButton = {
                Button(
                    onClick = { f.action(); forget = null },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { forget = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun LazyListScope.codeActions(scanLabel: Int, onScan: () -> Unit, onPaste: () -> Unit, bad: Boolean) {
    item {
        ListItem(
            modifier = Modifier.clickable(onClick = onScan),
            leadingContent = { Icon(painterResource(R.drawable.ic_qr_code_scanner), null) },
            headlineContent = { Text(stringResource(scanLabel)) },
            colors = clearItem(),
        )
        ListItem(
            modifier = Modifier.clickable(onClick = onPaste),
            leadingContent = { Icon(painterResource(R.drawable.ic_content_paste), null) },
            headlineContent = { Text(stringResource(R.string.paste_code)) },
            supportingContent = if (bad) {
                { Text(stringResource(R.string.code_not_recognised), color = MaterialTheme.colorScheme.error) }
            } else {
                null
            },
            colors = clearItem(),
        )
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
        colors = ListItemDefaults.colors(containerColor = Color.Transparent, headlineColor = MaterialTheme.colorScheme.onSurfaceVariant),
    )
}

@Composable
private fun PhoneChat(kind: ChatKind, vm: NewChatViewModel, onOpenChat: (String) -> Unit) {
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
    val recents by vm.recents.collectAsStateWithLifecycle()
    val blockedKeys by vm.blockedKeys.collectAsStateWithLifecycle()
    StepList {
        recentItems(recents.filter { it.kind == kind }, blockedKeys, vm, onOpenChat)
        item {
            ListItem(
                modifier = Modifier.clickable { pick.launch(Unit) },
                leadingContent = { Icon(painterResource(R.drawable.ic_contacts), null) },
                headlineContent = { Text(stringResource(R.string.pick_phone_contact)) },
                colors = clearItem(),
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

/** Something to forget, after a confirmation (its key is lost). */
private class Forget(val name: String, val body: Int, val action: () -> Unit)

/** Recent bridged contacts: tap to chat, swipe left to forget, hold for more. */
private fun LazyListScope.recentItems(
    recents: List<Recent>,
    blockedKeys: Set<String>,
    vm: NewChatViewModel,
    onOpenChat: (String) -> Unit,
) {
    if (recents.isEmpty()) return
    header(R.string.recents)
    items(recents, key = { "recent-" + it.chatId }) { r ->
        val blocked = r.chatId in blockedKeys
        EntryRow(
            title = r.shown,
            subtitle = r.name?.let { _ -> if (r.address.all(Char::isDigit)) "+" + r.address else r.address.take(12) },
            kind = r.kind,
            blocked = blocked,
            onOpen = { vm.openRecent(r)?.let(onOpenChat) },
            onBlock = { vm.setRecentBlocked(r, !blocked) },
            onDelete = { vm.forgetRecent(r) },
        )
    }
    item { HorizontalDivider() }
}

/** A contact, group or recent: swipe left to delete; hold for start chat, block and delete. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    title: String,
    subtitle: String?,
    kind: ChatKind,
    blocked: Boolean,
    onOpen: () -> Unit,
    onBlock: () -> Unit,
    onDelete: () -> Unit,
    verified: Boolean = false,
) {
    var menu by remember { mutableStateOf(false) }
    SwipeRow(onStartSwipe = null, onEndSwipe = onDelete, endDestructive = true, background = MaterialTheme.colorScheme.surfaceContainerLow) {
        Box {
            ListItem(
                modifier = Modifier.combinedClickable(onClick = { if (!blocked) onOpen() }, onLongClick = { menu = true }),
                colors = clearItem(),
                leadingContent = { Avatar(title, kind, 40.dp) },
                headlineContent = { Text(title) },
                supportingContent = {
                    val sub = if (blocked) stringResource(R.string.blocked) else subtitle
                    if (!sub.isNullOrEmpty()) Text(sub, maxLines = 1)
                },
                trailingContent = {
                    if (blocked) {
                        Icon(painterResource(R.drawable.ic_block), stringResource(R.string.blocked))
                    } else if (verified) {
                        VerifiedBadge()
                    }
                },
            )
            SkyDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (!blocked) {
                    MenuItem(R.string.start_chat, R.drawable.ic_chat) {
                        menu = false
                        onOpen()
                    }
                }
                MenuItem(if (blocked) R.string.unblock else R.string.block, R.drawable.ic_block) {
                    menu = false
                    onBlock()
                }
                MenuItem(R.string.delete, R.drawable.ic_delete, destructive = true) {
                    menu = false
                    onDelete()
                }
            }
        }
    }
}
