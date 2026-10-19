package io.alvaroedo.skypost.ui.components

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.alvaroedo.skypost.R

/**
 * A list row with two swipes: right ([onStartSwipe], e.g. archive) over the
 * theme's primary container, left ([onEndSwipe], delete) over a faded red,
 * each with its icon appearing from behind. A null action disables that side.
 */
@Composable
fun SwipeRow(
    onStartSwipe: (() -> Unit)?,
    onEndSwipe: (() -> Unit)?,
    @DrawableRes startIcon: Int = R.drawable.ic_archive,
    @DrawableRes endIcon: Int = R.drawable.ic_delete,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val state = rememberSwipeToDismissBoxState()
    // The row may stay (undo, unarchive in place): settle it back after acting.
    LaunchedEffect(state.currentValue) {
        if (state.currentValue != SwipeToDismissBoxValue.Settled) state.reset()
    }
    val colors = MaterialTheme.colorScheme
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = enabled && onStartSwipe != null,
        enableDismissFromEndToStart = enabled && onEndSwipe != null,
        onDismiss = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> onStartSwipe?.invoke()
                SwipeToDismissBoxValue.EndToStart -> onEndSwipe?.invoke()
                SwipeToDismissBoxValue.Settled -> {}
            }
        },
        backgroundContent = {
            val (bg, fg, icon, align) = when (state.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> Quad(colors.primaryContainer, colors.onPrimaryContainer, startIcon, Alignment.CenterStart)
                SwipeToDismissBoxValue.EndToStart -> Quad(colors.errorContainer, colors.onErrorContainer, endIcon, Alignment.CenterEnd)
                SwipeToDismissBoxValue.Settled -> Quad(colors.surface, colors.surface, null, Alignment.Center)
            }
            Box(Modifier.fillMaxSize().background(bg).padding(horizontal = 24.dp), contentAlignment = align) {
                if (icon != null) Icon(painterResource(icon), contentDescription = null, tint = fg)
            }
        },
    ) { content() }
}

/** A floating-menu entry with its icon. */
@Composable
fun MenuItem(@StringRes text: Int, @DrawableRes icon: Int, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(text)) },
        leadingIcon = { Icon(painterResource(icon), null) },
        onClick = onClick,
    )
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
