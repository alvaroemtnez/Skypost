package io.alvaroedo.skypost.ui.chats

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.alvaroedo.skypost.R
import io.alvaroedo.skypost.model.Auth
import io.alvaroedo.skypost.model.Chat
import io.alvaroedo.skypost.model.ChatKind
import io.alvaroedo.skypost.radio.RadioState
import io.alvaroedo.skypost.ui.SosResult
import io.alvaroedo.skypost.ui.components.AuthIcon
import io.alvaroedo.skypost.ui.components.Avatar
import io.alvaroedo.skypost.ui.components.DeliveryIcon
import io.alvaroedo.skypost.ui.components.LocalSnackbar
import io.alvaroedo.skypost.ui.components.MainTopBar
import io.alvaroedo.skypost.ui.components.MenuAction
import io.alvaroedo.skypost.ui.components.MenuItem
import io.alvaroedo.skypost.ui.components.OverflowMenu
import io.alvaroedo.skypost.ui.components.PharosStatus
import io.alvaroedo.skypost.ui.components.RadioStatus
import io.alvaroedo.skypost.ui.components.SearchButton
import io.alvaroedo.skypost.ui.components.SkyDropdownMenu
import io.alvaroedo.skypost.ui.components.SosButton
import io.alvaroedo.skypost.ui.components.SwipeRow
import io.alvaroedo.skypost.ui.components.TopBarSearch
import io.alvaroedo.skypost.ui.components.label
import io.alvaroedo.skypost.ui.components.rememberWithLocation
import io.alvaroedo.skypost.ui.components.shortTime
import io.alvaroedo.skypost.ui.components.shownText
import io.alvaroedo.skypost.ui.theme.extraColors
import kotlinx.coroutines.launch

/**
 * The chat list. With `archived = true` (its ViewModel too) it is the
 * archive: back arrow, no SOS or new-chat buttons.
 *
 * Laid out like MeshConnect's chat list (row sizes, two-letter avatars,
 * long-press card, swipes, search in the bar), but its bar stays the colour
 * of the page and keeps the radio and Pharos as two separate buttons.
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
    var confirmDelete by remember { mutableStateOf<Set<String>?>(null) }
    // Saveable: opening a chat takes this screen away, and coming back must keep the search.
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(query) { vm.setQuery(query) }
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val scroll = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val sosSent = stringResource(R.string.sos_sent)
    val sosNowhere = stringResource(R.string.sos_nowhere)
    val sosNoPosition = stringResource(R.string.sos_sent_no_position)
    val holdSos = stringResource(R.string.sos_hold_hint)
    val locating = stringResource(R.string.locating)
    val withLocation = rememberWithLocation()
    val undo = stringResource(R.string.undo)
    val maxPinned = stringResource(R.string.pin_limit, Chat.MAX_PINNED)
    val selecting = ui.selected.isNotEmpty()
    BackHandler(enabled = selecting) { vm.clearSelection() }
    val deletedOne = pluralStringResource(R.plurals.chats_deleted, 1, 1)
    val deletedMany = pluralStringResource(R.plurals.chats_deleted, confirmDelete?.size ?: 1, confirmDelete?.size ?: 1)

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
    val search = TopBarSearch(
        query = query,
        onQueryChange = { query = it },
        active = searching,
        onActiveChange = { searching = it },
        hint = stringResource(R.string.search_chats),
    )

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selecting) {
                SelectionBar(ui, vm, onPin = pin, onDelete = { confirmDelete = it })
            } else {
                MainTopBar(
                    title = { Text(stringResource(if (archived) R.string.archived_chats else R.string.app_name)) },
                    search = search,
                    searchIconEndDp = if (archived) 28f else 76f,
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
                            RadioStatus(radio, ui.radioName ?: stringResource(R.string.radio_custom).takeIf { ui.radioCustom }, onRadio)
                            PharosStatus(ui.activePharos, onPharos)
                        }
                        SearchButton(search)
                        if (!archived) {
                            OverflowMenu(
                                listOf(
                                    MenuAction(stringResource(R.string.my_code), R.drawable.ic_qr_code, onClick = onMyCode),
                                    MenuAction(stringResource(R.string.settings), R.drawable.ic_settings, onClick = onSettings),
                                ),
                            )
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!archived) {
                // New chat floats over SOS, centred on it.
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FloatingActionButton(onClick = onNewChat) {
                        Icon(painterResource(R.drawable.ic_edit_square), stringResource(R.string.new_chat))
                    }
                    SosButton(
                        onHeld = { confirmSos = true },
                        onTooShort = { scope.launch { snackbar.showSnackbar(holdSos) } },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!archived) FilterRow(ui.filter, ui.counts, vm::setFilter)
            if (ui.chats.isEmpty() && (archived || ui.archivedCount == 0 || ui.query.isNotBlank())) {
                EmptyChats(
                    when {
                        ui.query.isNotBlank() -> R.string.search_no_chats
                        ui.filter == null && !archived -> R.string.chats_empty
                        else -> R.string.chats_empty_filter
                    },
                    showNewChat = ui.filter == null && !archived && ui.query.isBlank(),
                    onNewChat = onNewChat,
                )
            } else {
                // Room at the bottom so the new-chat and SOS buttons never hide the last row.
                LazyColumn(contentPadding = PaddingValues(bottom = 168.dp)) {
                    items(ui.chats, key = { it.id }) { chat ->
                        val unread = chat.markedUnread || chat.unread > 0
                        SwipeRow(
                            // Right: read/unread. Left: archive/unarchive (MeshConnect's pair).
                            onStartSwipe = { vm.setMarkedUnread(setOf(chat.id), !unread) },
                            onEndSwipe = { vm.setArchived(setOf(chat.id), !chat.archived) },
                            startIcon = if (unread) R.drawable.ic_mark_read else R.drawable.ic_mark_unread,
                            endIcon = if (chat.archived) R.drawable.ic_unarchive else R.drawable.ic_archive,
                            enabled = !selecting,
                        ) {
                            ChatRow(
                                chat = chat,
                                selecting = selecting,
                                selected = chat.id in ui.selected,
                                onClick = { if (selecting) vm.toggleSelected(chat.id) else onOpenChat(chat.id) },
                                onToggleSelected = { vm.toggleSelected(chat.id) },
                                onArchive = { vm.setArchived(setOf(chat.id), !chat.archived) },
                                onDelete = { confirmDelete = setOf(chat.id) },
                                onUnread = { vm.setMarkedUnread(setOf(chat.id), !unread) },
                                onPin = { pin(chat) },
                                onSelect = { vm.toggleSelected(chat.id) },
                                onBlock = { vm.setBlocked(chat.id, !chat.blocked) },
                            )
                        }
                    }
                    if (!archived && ui.archivedCount > 0 && ui.query.isBlank()) {
                        item(key = "archived") {
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

    confirmDelete?.let { ids ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            icon = { Icon(painterResource(R.drawable.ic_delete), null) },
            title = { Text(pluralStringResource(R.plurals.chats_delete_title, ids.size, ids.size)) },
            text = { Text(stringResource(R.string.chats_delete_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDelete = null
                        delete(ids)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
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
                Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error)
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
    onToggleSelected: () -> Unit,
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
    val tint = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) tint.copy(alpha = 0.12f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = { if (selecting) onToggleSelected() else menu = true })
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(
            visible = selecting,
            enter = expandHorizontally() + fadeIn(),
            exit = shrinkHorizontally() + fadeOut(),
        ) {
            Box(Modifier.padding(end = 12.dp).size(24.dp), contentAlignment = Alignment.Center) {
                if (selected) {
                    Icon(painterResource(R.drawable.ic_check_circle), null, tint = tint, modifier = Modifier.size(24.dp))
                } else {
                    Box(Modifier.size(20.dp).border(2.dp, MaterialTheme.colorScheme.outline, CircleShape))
                }
            }
        }
        Avatar(chat.title, chat.kind)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                chat.title,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp),
                fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(color = muted)) {
                    when {
                        chat.blocked -> Text(stringResource(R.string.blocked), maxLines = 1)
                        last != null -> {
                            if (last.outgoing) DeliveryIcon(last.delivery) else AuthIcon(last.auth)
                            Spacer(Modifier.width(4.dp))
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
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            last?.let { Text(shortTime(it.atMillis), style = MaterialTheme.typography.labelMedium, color = muted) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (chat.pinnedAt != null) {
                    Icon(painterResource(R.drawable.ic_push_pin), stringResource(R.string.pinned), Modifier.size(14.dp), tint = muted)
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
        // MeshConnect's long-press card: rounded, raised, Delete in red.
        SkyDropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.widthIn(min = 232.dp)) {
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
            MenuItem(R.string.select, R.drawable.ic_select) {
                menu = false
                onSelect()
            }
            MenuItem(R.string.delete, R.drawable.ic_delete, destructive = true) {
                menu = false
                onDelete()
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
private fun EmptyChats(text: Int, showNewChat: Boolean, onNewChat: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painterResource(if (showNewChat) R.drawable.ic_edit_square else R.drawable.ic_search),
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(text),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(16.dp),
        )
        if (showNewChat) Button(onClick = onNewChat) { Text(stringResource(R.string.new_chat)) }
    }
}
