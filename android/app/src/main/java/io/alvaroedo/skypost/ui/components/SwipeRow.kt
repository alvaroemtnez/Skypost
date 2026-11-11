package io.alvaroedo.skypost.ui.components

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.alvaroedo.skypost.R
import kotlinx.coroutines.launch

/**
 * A list row with two swipes, as in MeshConnect: right ([onStartSwipe]) over
 * the primary container, left ([onEndSwipe]) over the secondary one (or the
 * error container when [endDestructive]), each icon showing from behind. A
 * null action disables that side.
 *
 * It acts once, when the swipe settles (`onDismiss`), then springs back.
 * Acting from `confirmValueChange` ran a toggle twice on one swipe in
 * Material3 1.4 (it is consulted during the drag too), which flipped
 * read/unread straight back.
 */
@Composable
fun SwipeRow(
    onStartSwipe: (() -> Unit)?,
    onEndSwipe: (() -> Unit)?,
    @DrawableRes startIcon: Int = R.drawable.ic_archive,
    @DrawableRes endIcon: Int = R.drawable.ic_delete,
    enabled: Boolean = true,
    endDestructive: Boolean = false,
    /** What the row sits on: the page, or a sheet's colour inside a card. */
    background: Color = MaterialTheme.colorScheme.surface,
    content: @Composable () -> Unit,
) {
    val start by rememberUpdatedState(onStartSwipe)
    val end by rememberUpdatedState(onEndSwipe)
    val state = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    val c = MaterialTheme.colorScheme
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = enabled && onStartSwipe != null,
        enableDismissFromEndToStart = enabled && onEndSwipe != null,
        onDismiss = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> start?.invoke()
                SwipeToDismissBoxValue.EndToStart -> end?.invoke()
                SwipeToDismissBoxValue.Settled -> {}
            }
            scope.launch { state.reset() }
        },
        backgroundContent = {
            val dir = state.dismissDirection
            val bg = when (dir) {
                SwipeToDismissBoxValue.StartToEnd -> c.primaryContainer
                SwipeToDismissBoxValue.EndToStart -> if (endDestructive) c.errorContainer else c.secondaryContainer
                SwipeToDismissBoxValue.Settled -> background
            }
            Box(
                Modifier.fillMaxSize().background(bg).padding(horizontal = 24.dp),
                contentAlignment = if (dir == SwipeToDismissBoxValue.EndToStart) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                when (dir) {
                    SwipeToDismissBoxValue.StartToEnd -> Icon(painterResource(startIcon), null, tint = c.onPrimaryContainer)
                    SwipeToDismissBoxValue.EndToStart -> Icon(
                        painterResource(endIcon),
                        null,
                        tint = if (endDestructive) c.onErrorContainer else c.onSecondaryContainer,
                    )
                    SwipeToDismissBoxValue.Settled -> {}
                }
            }
        },
    ) { Box(Modifier.background(background)) { content() } }
}

/**
 * Every popup menu in the app (the ⋮ menus and the long-press menus), with
 * MeshConnect's look: rounded 20 dp, raised, a step up from the surface.
 */
@Composable
fun SkyDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
        modifier = modifier.widthIn(min = 200.dp),
        content = content,
    )
}

/** A popup-menu entry with its icon; red when [destructive] (Delete). */
@Composable
fun MenuRow(@DrawableRes icon: Int, label: String, destructive: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    DropdownMenuItem(
        text = { Text(label, style = MaterialTheme.typography.bodyLarge, color = color) },
        leadingIcon = {
            Icon(
                painterResource(icon),
                null,
                tint = if (destructive) color else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        },
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp),
    )
}

/** [MenuRow] from resources. */
@Composable
fun MenuItem(@StringRes text: Int, @DrawableRes icon: Int, destructive: Boolean = false, onClick: () -> Unit) =
    MenuRow(icon, stringResource(text), destructive, onClick = onClick)
