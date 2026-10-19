package io.alvaroedo.skypost.ui.chats

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.alvaroedo.skypost.R
import io.alvaroedo.skypost.model.Auth
import io.alvaroedo.skypost.model.Chat
import io.alvaroedo.skypost.model.ChatKind
import io.alvaroedo.skypost.ui.components.Avatar
import io.alvaroedo.skypost.ui.components.AuthIcon
import io.alvaroedo.skypost.ui.components.DeliveryIcon
import io.alvaroedo.skypost.ui.components.LocalSnackbar
import io.alvaroedo.skypost.ui.components.PharosIndicator
import io.alvaroedo.skypost.ui.components.label
import io.alvaroedo.skypost.ui.components.shortTime
import io.alvaroedo.skypost.ui.theme.extraColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    vm: ChatListViewModel,
    onSos: () -> Int,
    onOpenChat: (String) -> Unit,
    onNewChat: () -> Unit,
    onPharos: () -> Unit,
    onMyCode: () -> Unit,
    onSettings: () -> Unit,
    onSosSettings: () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var confirmSos by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val scroll = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val sosSent = stringResource(R.string.sos_sent)
    val sosNowhere = stringResource(R.string.sos_nowhere)

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                scrollBehavior = scroll,
                actions = {
                    PharosIndicator(ui.activePharos, onPharos)
                    IconButton(onClick = onNewChat) {
                        Icon(painterResource(R.drawable.ic_edit_square), stringResource(R.string.new_chat))
                    }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.more_options))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.my_code)) },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_qr_code), null) },
                                onClick = { menu = false; onMyCode() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings)) },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_settings), null) },
                                onClick = { menu = false; onSettings() },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { confirmSos = true },
                containerColor = MaterialTheme.extraColors.emergency,
                contentColor = MaterialTheme.extraColors.onEmergency,
                icon = { Icon(painterResource(R.drawable.ic_sos), null) },
                text = { Text(stringResource(R.string.sos), style = MaterialTheme.typography.titleMedium) },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            FilterRow(ui.filter, ui.counts, vm::setFilter)
            if (ui.chats.isEmpty()) {
                EmptyChats(ui.filter == null, onNewChat)
            } else {
                // Room at the bottom so the SOS button never hides the last chat.
                LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(ui.chats, key = { it.id }) { chat -> ChatRow(chat) { onOpenChat(chat.id) } }
                }
            }
        }
    }

    if (confirmSos) {
        AlertDialog(
            onDismissRequest = { confirmSos = false },
            icon = { Icon(painterResource(R.drawable.ic_sos), null, tint = MaterialTheme.extraColors.emergency) },
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
                            val n = onSos()
                            scope.launch { snackbar.showSnackbar(if (n > 0) sosSent else sosNowhere) }
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

@Composable
private fun ChatRow(chat: Chat, onClick: () -> Unit) {
    val last = chat.last
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Avatar(chat.title, chat.kind) },
        headlineContent = { Text(chat.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (last != null) {
                    if (last.outgoing) {
                        DeliveryIcon(last.delivery)
                    } else {
                        AuthIcon(last.auth)
                    }
                    Box(Modifier.width(4.dp))
                    val text = if (last.sender != null) "${last.sender}: ${last.text}" else last.text
                    Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    Text(stringResource(R.string.chat_empty_preview), maxLines = 1)
                }
            }
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                last?.let { Text(shortTime(it.atMillis), style = MaterialTheme.typography.labelSmall) }
                if (chat.unread > 0) {
                    Badge { Text(chat.unread.toString()) }
                } else if (chat.encrypted) {
                    AuthIcon(Auth.ENCRYPTED, tint = MaterialTheme.colorScheme.error)
                }
            }
        },
    )
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
