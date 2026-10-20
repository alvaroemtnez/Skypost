package io.alvaroedo.skypost.ui.chat

import androidx.compose.foundation.clickable
import kotlinx.coroutines.launch
import io.alvaroedo.skypost.ui.components.copyToClipboard
import io.alvaroedo.skypost.ui.components.MenuItem
import io.alvaroedo.skypost.model.Delivery
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.MenuDefaults
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import java.util.Locale
import io.alvaroedo.skypost.ui.components.rememberWithLocation
import io.alvaroedo.skypost.ui.components.openPosition
import io.alvaroedo.skypost.model.GeoFix
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import io.alvaroedo.skypost.ui.theme.extraColors
import io.alvaroedo.skypost.ui.components.shownText
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.alvaroedo.skypost.R
import io.alvaroedo.skypost.model.Auth
import io.alvaroedo.skypost.model.ChatKind
import io.alvaroedo.skypost.model.ChatMessage
import io.alvaroedo.skypost.ui.components.AuthIcon
import io.alvaroedo.skypost.ui.components.DeliveryIcon
import io.alvaroedo.skypost.ui.components.LocalSnackbar
import io.alvaroedo.skypost.ui.components.SafetyNumber
import io.alvaroedo.skypost.ui.components.VerifiedBadge
import io.alvaroedo.skypost.ui.components.VerifyControls
import io.alvaroedo.skypost.ui.components.label
import io.alvaroedo.skypost.ui.components.shortTime

/** Seconds per JS8 frame at Normal speed, for the airtime estimate. */
private const val NORMAL_FRAME_SECS = 15

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onInvite: (uri: String, name: String) -> Unit,
    onShowCode: (key: String) -> Unit,
    onScanVerify: (key: String) -> Unit,
    onBack: () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val chat = ui.chat
    var menu by remember { mutableStateOf(false) }
    var confirmEncrypt by remember { mutableStateOf(false) }
    var showSafety by remember { mutableStateOf(false) }
    var quick by remember { mutableStateOf(false) }
    val phrases = stringArrayResource(R.array.canned_phrases).toList()
    val ping by vm.ping.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val copiedText = stringResource(R.string.copied)
    val undo = stringResource(R.string.undo)
    val deletedOne = pluralStringResource(R.plurals.messages_deleted, 1, 1)
    val deletedMany = pluralStringResource(R.plurals.messages_deleted, selected.size, selected.size)
    // What "copy" puts on the clipboard, per message, in the app's language.
    val copyTexts = ui.messages.associate { it.id to copyText(it) }
    val copy: (Set<Long>) -> Unit = { ids ->
        val text = ui.messages.filter { it.id in ids }.joinToString("\n") { copyTexts[it.id].orEmpty() }
        copyToClipboard(context, chat?.title.orEmpty(), text)
        vm.clearSelection()
        scope.launch { snackbar.showSnackbar(copiedText) }
    }
    val delete: (Set<Long>) -> Unit = { ids ->
        val text = if (ids.size == 1) deletedOne else deletedMany
        val gone = vm.delete(ids)
        scope.launch {
            val r = snackbar.showSnackbar(text, actionLabel = undo, duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) vm.restore(gone)
        }
    }
    val locating by vm.locating.collectAsStateWithLifecycle()
    val withLocation = rememberWithLocation()
    val noFixSent = stringResource(R.string.sent_without_position)
    val noFix = stringResource(R.string.no_position)
    LaunchedEffect(vm) {
        vm.events.collect { e ->
            snackbar.showSnackbar(if (e == ChatEvent.NO_POSITION) noFix else noFixSent)
        }
    }

    LaunchedEffect(ui.messages.size) { vm.markRead() }
    LaunchedEffect(phrases) { vm.setLocalPhrases(phrases) }
    // A bridged chat pings the Pharos, not the contact: say so, so nobody
    // takes it for the contact being online.
    val bridged = chat?.kind?.bridged == true
    val title = chat?.title.orEmpty()
    val via = ui.via.orEmpty()
    val heard = (ping as? PingResult.Heard)?.let {
        if (bridged) {
            stringResource(R.string.ping_pharos_ok, via, it.snrDb)
        } else {
            stringResource(R.string.ping_contact_ok, title, it.snrDb)
        }
    }
    val silent = if (bridged) stringResource(R.string.ping_pharos_silent, via) else stringResource(R.string.ping_contact_silent, title)
    LaunchedEffect(ping) {
        when (ping) {
            is PingResult.Heard -> snackbar.showSnackbar(heard.orEmpty())
            PingResult.Silent -> snackbar.showSnackbar(silent)
            else -> return@LaunchedEffect
        }
        vm.pingShown()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selected.isNotEmpty()) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = vm::clearSelection) {
                            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.cancel))
                        }
                    },
                    title = { Text(selected.size.toString()) },
                    actions = {
                        IconButton(onClick = { copy(selected) }) {
                            Icon(painterResource(R.drawable.ic_content_copy), stringResource(R.string.copy))
                        }
                        IconButton(onClick = { delete(selected) }) {
                            Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.delete))
                        }
                    },
                )
            } else TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                    }
                },
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                chat?.title.orEmpty(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (ui.verified) {
                                VerifiedBadge(
                                    Modifier.padding(start = 6.dp).size(20.dp).clickable { showSafety = true },
                                )
                            }
                        }
                        chat?.let {
                            val kind = stringResource(it.kind.label())
                            val sub = listOfNotNull(
                                kind,
                                it.detail,
                                ui.via?.let { v -> stringResource(R.string.via_pharos, v) },
                            ).joinToString(" · ")
                            Text(sub, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        }
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.more_options))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            // Greyed out while Settings turns encryption on for every chat.
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(stringResource(R.string.emergency_encryption))
                                        if (ui.encryptAll) {
                                            Text(
                                                stringResource(R.string.encrypt_all_on),
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                    }
                                },
                                enabled = !ui.encryptAll,
                                leadingIcon = { Icon(painterResource(R.drawable.ic_lock), null) },
                                trailingIcon = { Checkbox(checked = ui.encrypted, onCheckedChange = null, enabled = !ui.encryptAll) },
                                onClick = {
                                    menu = false
                                    if (chat?.encrypted == true) vm.setEncrypted(false) else confirmEncrypt = true
                                },
                            )
                            if (chat?.kind == ChatKind.GROUP && chat.ref != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.group_invite)) },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_qr_code), null) },
                                    onClick = { menu = false; onInvite(chat.ref, chat.title) },
                                )
                            }
                            if (chat != null && chat.kind != ChatKind.GROUP && !chat.blocked) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                if (chat.kind.bridged) R.string.ping_pharos_menu else R.string.ping_contact_menu,
                                            ),
                                        )
                                    },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_sensors), null) },
                                    enabled = ping != PingResult.Waiting,
                                    onClick = { menu = false; vm.ping() },
                                )
                            }
                            if (chat != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(if (chat.blocked) R.string.unblock else R.string.block)) },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_block), null) },
                                    onClick = { menu = false; vm.setBlocked(!chat.blocked) },
                                )
                            }
                            if (ui.safetyNumber != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.safety_number)) },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_key), null) },
                                    onClick = { menu = false; showSafety = true },
                                )
                            }
                            ui.contact?.let { c ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.show_qr)) },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_qr_code), null) },
                                    onClick = { menu = false; onShowCode(c.key) },
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (chat?.blocked == true) {
                BlockedBar(onUnblock = { vm.setBlocked(false) }, modifier = Modifier.navigationBarsPadding())
            } else {
                Composer(
                    draft = ui.draft,
                    frames = ui.frames,
                    encrypted = ui.encrypted,
                    onDraft = vm::setDraft,
                    onSend = vm::send,
                    onQuick = { quick = true },
                    modifier = Modifier.navigationBarsPadding().imePadding(),
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (ui.encrypted) {
                Banner(
                    R.drawable.ic_lock,
                    stringResource(if (ui.encryptAll) R.string.encrypted_banner_all else R.string.encrypted_banner),
                )
            }
            if (ui.noPharos) Banner(R.drawable.ic_cell_tower, stringResource(R.string.no_pharos_banner))
            if (locating) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.locating),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                reverseLayout = true,
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(ui.messages.asReversed(), key = { it.id }) { m ->
                    Bubble(
                        m,
                        showSender = chat?.kind == ChatKind.GROUP,
                        selecting = selected.isNotEmpty(),
                        selected = m.id in selected,
                        onToggle = { vm.toggleSelected(m.id) },
                        onRetry = { vm.retry(m.id) },
                        onCopy = { copy(setOf(m.id)) },
                        onDelete = { delete(setOf(m.id)) },
                    )
                }
            }
        }
    }

    if (quick) {
        ModalBottomSheet(onDismissRequest = { quick = false }) {
            QuickMessages(
                phrases = phrases,
                onStatusOk = { quick = false; withLocation(vm::sendStatusOk) },
                onPosition = { quick = false; withLocation(vm::sendPosition) },
                onPhrase = { quick = false; vm.sendPhrase(it) },
            )
        }
    }

    if (confirmEncrypt) {
        AlertDialog(
            onDismissRequest = { confirmEncrypt = false },
            icon = { Icon(painterResource(R.drawable.ic_lock), null) },
            title = { Text(stringResource(R.string.encrypt_confirm_title)) },
            text = { Text(stringResource(R.string.encrypt_confirm_body)) },
            confirmButton = {
                Button(
                    onClick = { confirmEncrypt = false; vm.setEncrypted(true) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text(stringResource(R.string.activate)) }
            },
            dismissButton = { TextButton(onClick = { confirmEncrypt = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (showSafety && ui.safetyNumber != null) {
        AlertDialog(
            onDismissRequest = { showSafety = false },
            title = { Text(stringResource(R.string.safety_number)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.safety_number_body, chat?.title.orEmpty()))
                    SafetyNumber(ui.safetyNumber.orEmpty(), Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (ui.verified) VerifiedBadge()
                        Text(
                            stringResource(if (ui.verified) R.string.contact_verified_body else R.string.contact_unverified_body),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    VerifyControls(
                        verified = ui.verified,
                        onScan = { showSafety = false; ui.contact?.let { onScanVerify(it.key) } },
                        onSet = vm::setVerified,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showSafety = false }) { Text(stringResource(R.string.close)) } },
        )
    }
}

@Composable
private fun Banner(icon: Int, text: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(painterResource(icon), contentDescription = null)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    m: ChatMessage,
    showSender: Boolean,
    selecting: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    onRetry: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val (bg, fg) = when {
        m.sos -> MaterialTheme.extraColors.emergency to MaterialTheme.extraColors.onEmergency
        m.outgoing -> colors.primaryContainer to colors.onPrimaryContainer
        else -> colors.surfaceContainerHigh to colors.onSurface
    }
    val shape = if (m.outgoing) {
        RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
    } else {
        RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) colors.secondaryContainer else Color.Unspecified)
            .combinedClickable(
                onClick = { if (selecting) onToggle() },
                onLongClick = { if (selecting) onToggle() else menu = true },
            )
            .padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalArrangement = if (m.outgoing) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val failed = m.outgoing && m.delivery == Delivery.FAILED
        Box {
            Surface(color = bg, contentColor = fg, shape = shape, modifier = Modifier.widthIn(max = 296.dp)) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (showSender && !m.outgoing && m.sender != null) {
                        Text(m.sender, style = MaterialTheme.typography.labelLarge, color = colors.primary)
                    }
                    Text(messageLabel(m), style = MaterialTheme.typography.bodyLarge)
                    m.position?.let { fix ->
                        // SOS: a lighter shade of its fixed red; otherwise the bubble's theme text colour.
                        val outline = if (m.sos) lerp(bg, fg, 0.5f) else fg.copy(alpha = 0.5f)
                        PositionChip(fix, fg, outline)
                    }
                    Row(
                        Modifier.align(Alignment.End).padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(shortTime(m.atMillis), style = MaterialTheme.typography.labelSmall)
                        if (m.outgoing) {
                            if (m.auth == Auth.ENCRYPTED) AuthIcon(Auth.ENCRYPTED, tint = fg)
                            // A failed send shows its warning beside the bubble instead.
                            if (!failed) DeliveryIcon(m.delivery, tint = fg)
                        } else {
                            AuthIcon(m.auth, tint = fg)
                        }
                    }
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (m.outgoing && m.delivery == Delivery.FAILED) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.retry)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_refresh), null) },
                        colors = MenuDefaults.itemColors(
                            textColor = colors.error,
                            leadingIconColor = colors.error,
                        ),
                        onClick = { menu = false; onRetry() },
                    )
                }
                MenuItem(R.string.copy, R.drawable.ic_content_copy) { menu = false; onCopy() }
                MenuItem(R.string.select, R.drawable.ic_check_box) { menu = false; onToggle() }
                MenuItem(R.string.delete, R.drawable.ic_delete) { menu = false; onDelete() }
            }
        }
        if (failed) {
            // Big and red, between the bubble and the margin; tapping it opens the menu (Retry first).
            IconButton(onClick = { if (selecting) onToggle() else menu = true }, modifier = Modifier.padding(start = 4.dp)) {
                Icon(
                    painterResource(R.drawable.ic_error),
                    stringResource(R.string.delivery_failed),
                    Modifier.size(28.dp),
                    tint = colors.error,
                )
            }
        }
    }
}

/** A message's title line: SOS, "my position"/"position" for a bare fix, or its text. */
@Composable
private fun messageLabel(m: ChatMessage): String = when {
    m.sos -> stringResource(R.string.sos)
    m.position != null && m.phrase == null ->
        stringResource(if (m.outgoing) R.string.my_position else R.string.position)
    else -> shownText(m)
}

/** What copying a message gives: its text, plus the coordinates when it has a position. */
@Composable
private fun copyText(m: ChatMessage): String {
    val label = messageLabel(m)
    return m.position?.let { "%s (%.5f, %.5f)".format(Locale.ROOT, label, it.lat, it.lon) } ?: label
}

/**
 * Signal-style message bar: a rounded field that grows upwards line by line,
 * with one round button at its bottom right: quick messages while empty,
 * send once there is text. The airtime line below always keeps its room, so
 * nothing jumps when it appears.
 */
@Composable
private fun Composer(
    draft: String,
    frames: Int,
    encrypted: Boolean,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onQuick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surface, modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                BasicTextField(
                    value = draft,
                    onValueChange = onDraft,
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    maxLines = 6,
                    decorationBox = { inner ->
                        Surface(shape = RoundedCornerShape(24.dp), color = colors.surfaceContainerHigh) {
                            Row(
                                Modifier.heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (encrypted) {
                                    Icon(
                                        painterResource(R.drawable.ic_lock),
                                        stringResource(R.string.auth_encrypted),
                                        Modifier.padding(end = 8.dp).size(18.dp),
                                        tint = colors.error,
                                    )
                                }
                                Box(Modifier.weight(1f)) {
                                    if (draft.isEmpty()) {
                                        Text(
                                            stringResource(R.string.message_hint),
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = colors.onSurfaceVariant,
                                        )
                                    }
                                    inner()
                                }
                            }
                        }
                    },
                )
                Spacer(Modifier.width(8.dp))
                if (draft.isBlank()) {
                    FilledTonalIconButton(onClick = onQuick, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.ic_add), stringResource(R.string.quick_messages))
                    }
                } else {
                    FilledIconButton(onClick = onSend, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.ic_send), stringResource(R.string.send))
                    }
                }
            }
            Text(
                if (frames > 0) airtime(frames) else "",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(start = 16.dp).height(20.dp),
            )
        }
    }
}

@Composable
private fun BlockedBar(onUnblock: () -> Unit, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.chat_blocked), Modifier.weight(1f))
            TextButton(onClick = onUnblock) { Text(stringResource(R.string.unblock)) }
        }
    }
}

/** "≈ 6 tramas · 1 min 30 s" at Normal speed. */
@Composable
private fun airtime(frames: Int): String {
    val secs = frames * NORMAL_FRAME_SECS
    val time = if (secs < 60) {
        stringResource(R.string.duration_s, secs)
    } else {
        stringResource(R.string.duration_min_s, secs / 60, secs % 60)
    }
    return stringResource(R.string.airtime, pluralStringResource(R.plurals.frames, frames, frames), time)
}

@Composable
private fun QuickMessages(phrases: List<String>, onStatusOk: () -> Unit, onPosition: () -> Unit, onPhrase: (Int) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            ListItem(
                modifier = Modifier.clickable(onClick = onStatusOk),
                leadingContent = { Icon(painterResource(R.drawable.ic_check_circle), null) },
                headlineContent = { Text(stringResource(R.string.quick_ok)) },
                supportingContent = { Text(stringResource(R.string.quick_ok_body)) },
            )
            ListItem(
                modifier = Modifier.clickable(onClick = onPosition),
                leadingContent = { Icon(painterResource(R.drawable.ic_my_location), null) },
                headlineContent = { Text(stringResource(R.string.quick_position)) },
            )
            HorizontalDivider()
            Text(
                stringResource(R.string.quick_phrases),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(16.dp),
            )
        }
        itemsIndexed(phrases) { id, p ->
            ListItem(modifier = Modifier.clickable { onPhrase(id) }, headlineContent = { Text(p) })
        }
    }
}

/** "📍 42.63110, 0.65880 · ±8 m": opens the position in the phone's maps app. */
@Composable
private fun PositionChip(fix: GeoFix, content: Color, outline: Color) {
    val context = LocalContext.current
    val acc = fix.accuracyM?.let { " · ±%.0f m".format(Locale.ROOT, it) }.orEmpty()
    AssistChip(
        onClick = { openPosition(context, fix) },
        label = { Text("%.5f, %.5f".format(Locale.ROOT, fix.lat, fix.lon) + acc) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_my_location), null, Modifier.size(16.dp)) },
        border = BorderStroke(1.dp, outline),
        colors = AssistChipDefaults.assistChipColors(labelColor = content, leadingIconContentColor = content),
        modifier = Modifier.padding(top = 4.dp),
    )
}
