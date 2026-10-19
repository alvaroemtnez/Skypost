package io.alvaroedo.skypost.ui.chats

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.alvaroedo.skypost.R
import io.alvaroedo.skypost.model.Auth
import io.alvaroedo.skypost.model.Chat
import io.alvaroedo.skypost.model.ChatKind
import io.alvaroedo.skypost.radio.RadioState
import io.alvaroedo.skypost.ui.components.AuthIcon
import io.alvaroedo.skypost.ui.components.Avatar
import io.alvaroedo.skypost.ui.components.DeliveryIcon
import io.alvaroedo.skypost.ui.components.LocalSnackbar
import io.alvaroedo.skypost.ui.components.MenuItem
import io.alvaroedo.skypost.ui.components.PharosIndicator
import io.alvaroedo.skypost.ui.components.RadioIndicator
import io.alvaroedo.skypost.ui.components.SwipeRow
import io.alvaroedo.skypost.ui.components.label
import io.alvaroedo.skypost.ui.components.shortTime
import io.alvaroedo.skypost.ui.components.shownText
import io.alvaroedo.skypost.ui.components.rememberWithLocation
import io.alvaroedo.skypost.ui.SosResult
import io.alvaroedo.skypost.ui.theme.extraColors
import kotlinx.coroutines.launch

/**
 * The chat list. With `archived = true` (its ViewModel too) it is the
 * archive: back arrow, no SOS button, swipe right unarchives.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    vm: ChatListViewModel,
    onSos: suspend () -> SosResult,
    onOpenChat: (String) -> Unit,
    onNewChat: () -> Unit,
    onPharos: () -> Unit,
    onMyCode: () -> Unit,
    onSettings: () -> Unit,
    onSosSettings: () -> Unit,
    radio: RadioState,
    onRadio: () -> Unit,
    onArchived: () -> Unit,
    archived: Boolean = false,
    onBack: () -> Unit = {},
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var confirmSos by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val scroll = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val sosSent = stringResource(R.string.sos_sent)
    val sosNowhere = stringResource(R.string.sos_nowhere)
    val sosNoPosition = stringResource(R.string.sos_sent_no_position)
    val locating = stringResource(R.string.locating)
    val withLocation = rememberWithLocation()
    val undo = stringResource(R.string.undo)
    val maxPinned = stringResource(R.string.pin_limit, Chat.MAX_PINNED)
    val selecting = ui.selected.isNotEmpty()
    val deletedOne = pluralStringResource(R.plurals.chats_deleted, 1, 1)
    val deletedMany = pluralStringResource(R.plurals.chats_deleted, ui.selected.size, ui.selected.size)

    val delete: (Set<String>) -> Unit = { ids ->
        val text = if (ids.size == 1) deletedOne else deletedMany
        val gone = vm.delete(ids)
        scope.launch {
            val r = snackbar.showSnackbar(text, actionLabel = undo, duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) vm.restore(gone)
        }
    }
    val pin: (Chat) -> Unit = { c ->
        if (!vm.setPinned(c.id, c.pinnedAt == null)) scope.launch { snackbar.showSnackbar(maxPinned) }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selecting) {
                SelectionBar(ui, vm, onPin = pin, onDelete = delete)
            } else {
                TopAppBar(
                    title = { Text(stringResource(if (archived) R.string.archived_chats else R.string.app_name)) },
                    scrollBehavior = scroll,
                    navigationIcon = {
                        if (archived) {
                            IconButton(onClick = onBack) {
                                Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                            }
                        }
                    },
                    actions = {
                        if (!archived) {
                            RadioIndicator(radio, onRadio)
                            PharosIndicator(ui.activePharos, onPharos)
                            IconButton(onClick = onNewChat) {
                                Icon(painterResource(R.drawable.ic_edit_square), stringResource(R.string.new_chat))
                            }
                            Box {
                                IconButton(onClick = { menu = true }) {
                                    Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.more_options))
                                }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    MenuItem(R.string.my_code, R.drawable.ic_qr_code) {
                                        menu = false
                                        onMyCode()
                                    }
                                    MenuItem(R.string.settings, R.drawable.ic_settings) {
                                        menu = false
                                        onSettings()
                                    }
                                }
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!archived) {
                FloatingActionButton(
                    onClick = { confirmSos = true },
                    containerColor = MaterialTheme.extraColors.emergency,
                    contentColor = MaterialTheme.extraColors.onEmergency,
                    modifier = Modifier.size(72.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_sos), stringResource(R.string.sos), Modifier.size(52.dp))
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!archived) FilterRow(ui.filter, ui.counts, vm::setFilter)
            if (ui.chats.isEmpty() && (archived || ui.archivedCount == 0)) {
                EmptyChats(ui.filter == null && !archived, onNewChat)
            } else {
                // Room at the bottom so the SOS button never hides the last row.
                LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(ui.chats, key = { it.id }) { chat ->
                        ChatRow(
                            chat = chat,
                            selecting = selecting,
                            selected = chat.id in ui.selected,
                            onClick = { if (selecting) vm.toggleSelected(chat.id) else onOpenChat(chat.id) },
                            onArchive = { vm.setArchived(setOf(chat.id), !chat.archived) },
                            onDelete = { delete(setOf(chat.id)) },
                            onUnread = { vm.setMarkedUnread(setOf(chat.id), !(chat.markedUnread || chat.unread > 0)) },
                            onPin = { pin(chat) },
                            onSelect = { vm.toggleSelected(chat.id) },
                            onBlock = { vm.setBlocked(chat.id, !chat.blocked) },
                        )
                    }
                    if (!archived && ui.archivedCount > 0) {
                        item(key = "archived") {
                            HorizontalDivider()
                            ListItem(
                                modifier = Modifier.clickable(onClick = onArchived),
                                leadingContent = { Icon(painterResource(R.drawable.ic_archive), null) },
                                headlineContent = { Text(stringResource(R.string.archived_count, ui.archivedCount)) },
                                trailingContent = {
                                    if (ui.archivedUnread) UnreadDot()
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmSos) {
        AlertDialog(
            onDismissRequest = { confirmSos = false },
            title = { Text(stringResource(R.string.sos_confirm_title)) },
            text = {
                if (ui.sosTargets.isEmpty()) {
                    Text(stringResource(R.string.sos_no_targets))
                } else {
                    Text(stringResource(R.string.sos_confirm_body, ui.sosTargets.joinToString()))
                }
            },
            confirmButton = {
                if (ui.sosTargets.isEmpty()) {
                    TextButton(onClick = { confirmSos = false; onSosSettings() }) {
                        Text(stringResource(R.string.sos_contacts))
                    }
                } else {
                    Button(
                        onClick = {
                            confirmSos = false
                            withLocation {
                                scope.launch {
                                    val waiting = launch { snackbar.showSnackbar(locating, duration = SnackbarDuration.Indefinite) }
                                    val r = onSos()
                                    waiting.cancel()
                                    snackbar.showSnackbar(
                                        when {
                                            r.sentTo == 0 -> sosNowhere
                                            r.withPosition -> sosSent
                                            else -> sosNoPosition
                                        },
                                    )
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.extraColors.emergency,
                            contentColor = MaterialTheme.extraColors.onEmergency,
                        ),
                    ) { Text(stringResource(R.string.sos_send)) }
                }
            },
            dismissButton = { TextButton(onClick = { confirmSos = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Contextual top bar while chats are selected. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(ui: ChatListUi, vm: ChatListViewModel, onPin: (Chat) -> Unit, onDelete: (Set<String>) -> Unit) {
    val chosen = ui.chats.filter { it.id in ui.selected }
    val allUnread = chosen.all { it.markedUnread || it.unread > 0 }
    val allArchived = chosen.all { it.archived }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = vm::clearSelection) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.cancel))
            }
        },
        title = { Text(ui.selected.size.toString()) },
        actions = {
            chosen.singleOrNull()?.takeIf { !it.archived }?.let { c ->
                IconButton(onClick = { onPin(c) }) {
                    Icon(
                        painterResource(if (c.pinnedAt != null) R.drawable.ic_keep_off else R.drawable.ic_push_pin),
                        stringResource(if (c.pinnedAt != null) R.string.unpin else R.string.pin),
                    )
                }
            }
            IconButton(onClick = { vm.setMarkedUnread(ui.selected, !allUnread) }) {
                Icon(
                    painterResource(if (allUnread) R.drawable.ic_mark_read else R.drawable.ic_mark_unread),
                    stringResource(if (allUnread) R.string.mark_read else R.string.mark_unread),
                )
            }
            IconButton(onClick = { vm.setArchived(ui.selected, !allArchived) }) {
                Icon(
                    painterResource(if (allArchived) R.drawable.ic_unarchive else R.drawable.ic_archive),
                    stringResource(if (allArchived) R.string.unarchive else R.string.archive),
                )
            }
            IconButton(onClick = { onDelete(ui.selected) }) {
                Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.delete))
            }
        },
    )
}

@Composable
private fun FilterRow(selected: ChatKind?, counts: Map<ChatKind, Int>, onSelect: (ChatKind?) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text(stringResource(R.string.filter_all)) })
        }
        items(ChatKind.entries.filter { (counts[it] ?: 0) > 0 }) { kind ->
            FilterChip(
                selected = selected == kind,
                onClick = { onSelect(if (selected == kind) null else kind) },
                label = { Text(stringResource(kind.label())) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(
    chat: Chat,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onUnread: () -> Unit,
    onPin: () -> Unit,
    onSelect: () -> Unit,
    onBlock: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val last = chat.last
    val unread = chat.markedUnread || chat.unread > 0
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    SwipeRow(
        onStartSwipe = onArchive,
        onEndSwipe = onDelete,
        startIcon = if (chat.archived) R.drawable.ic_unarchive else R.drawable.ic_archive,
        enabled = !selecting,
    ) {
        Box {
            ListItem(
                modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = { if (!selecting) menu = true }),
                colors = if (selected) {
                    ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                } else {
                    ListItemDefaults.colors()
                },
                leadingContent = {
                    if (selecting) Checkbox(checked = selected, onCheckedChange = null) else Avatar(chat.title, chat.kind)
                },
                headlineContent = { Text(chat.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when {
                            chat.blocked -> Text(stringResource(R.string.blocked), maxLines = 1)
                            last != null -> {
                                if (last.outgoing) DeliveryIcon(last.delivery) else AuthIcon(last.auth)
                                Box(Modifier.width(4.dp))
                                // A bare position has no text: name it, in italics (like SOS shows "SOS").
                                val bare = last.position != null && last.phrase == null && !last.sos
                                val text = when {
                                    !bare -> shownText(last)
                                    last.outgoing -> stringResource(R.string.my_position)
                                    else -> stringResource(R.string.position)
                                }
                                Text(
                                    if (last.sender != null) "${last.sender}: $text" else text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontStyle = if (bare) FontStyle.Italic else null,
                                )
                            }
                            else -> Text(stringResource(R.string.chat_empty_preview), maxLines = 1)
                        }
                    }
                },
                trailingContent = {
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        last?.let { Text(shortTime(it.atMillis), style = MaterialTheme.typography.labelSmall) }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (chat.pinnedAt != null) {
                                Icon(painterResource(R.drawable.ic_push_pin), stringResource(R.string.pinned), Modifier.size(16.dp), tint = muted)
                            }
                            when {
                                chat.blocked -> {
                                    Icon(painterResource(R.drawable.ic_block), stringResource(R.string.blocked), Modifier.size(16.dp), tint = muted)
                                }
                                chat.unread > 0 -> {
                                    // Theme colour; emergency red only for an unread SOS.
                                    val sos = last?.sos == true && !last.outgoing
                                    Badge(
                                        containerColor = if (sos) MaterialTheme.extraColors.emergency else MaterialTheme.colorScheme.primary,
                                        contentColor = if (sos) MaterialTheme.extraColors.onEmergency else MaterialTheme.colorScheme.onPrimary,
                                    ) { Text(chat.unread.toString()) }
                                }
                                chat.markedUnread -> UnreadDot()
                                chat.encrypted -> AuthIcon(Auth.ENCRYPTED, tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                },
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                MenuItem(if (unread) R.string.mark_read else R.string.mark_unread, if (unread) R.drawable.ic_mark_read else R.drawable.ic_mark_unread) {
                    menu = false
                    onUnread()
                }
                if (!chat.archived) {
                    MenuItem(
                        if (chat.pinnedAt != null) R.string.unpin else R.string.pin,
                        if (chat.pinnedAt != null) R.drawable.ic_keep_off else R.drawable.ic_push_pin,
                    ) {
                        menu = false
                        onPin()
                    }
                }
                MenuItem(R.string.select, R.drawable.ic_check_box) {
                    menu = false
                    onSelect()
                }
                MenuItem(
                    if (chat.archived) R.string.unarchive else R.string.archive,
                    if (chat.archived) R.drawable.ic_unarchive else R.drawable.ic_archive,
                ) {
                    menu = false
                    onArchive()
                }
                if (chat.kind == ChatKind.DIRECT || chat.kind == ChatKind.GROUP) {
                    MenuItem(if (chat.blocked) R.string.unblock else R.string.block, R.drawable.ic_block) {
                        menu = false
                        onBlock()
                    }
                }
                MenuItem(R.string.delete, R.drawable.ic_delete) {
                    menu = false
                    onDelete()
                }
            }
        }
    }
}

/** Unread without a count: a filled circle as big as a numbered badge. */
@Composable
private fun UnreadDot() {
    Box(Modifier.size(16.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
}

@Composable
private fun EmptyChats(all: Boolean, onNewChat: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_edit_square),
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(if (all) R.string.chats_empty else R.string.chats_empty_filter),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(16.dp),
        )
        if (all) Button(onClick = onNewChat) { Text(stringResource(R.string.new_chat)) }
    }
}
