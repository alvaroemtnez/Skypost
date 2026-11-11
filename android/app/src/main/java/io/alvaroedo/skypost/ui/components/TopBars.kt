package io.alvaroedo.skypost.ui.components

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.alvaroedo.skypost.R
import kotlin.math.hypot
import kotlin.math.max

/** One entry in a top bar's ⋮ menu. */
class MenuAction(
    val label: String,
    @param:DrawableRes val icon: Int,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/** The ⋮ button and its rounded menu. Nothing when [actions] is empty. */
@Composable
fun OverflowMenu(actions: List<MenuAction>) {
    if (actions.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.more_options))
        }
        SkyDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (a in actions) {
                MenuRow(a.icon, a.label, a.destructive, a.enabled) {
                    open = false
                    a.onClick()
                }
            }
        }
    }
}

/**
 * The top bar of every screen under the chat list (Settings, Pharos admin,
 * SOS chats…): a band of its own colour, with a back arrow, as MeshConnect's.
 * The chat list keeps a bar the colour of the page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubTopBar(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    menu: List<MenuAction> = emptyList(),
    actions: @Composable RowScope.() -> Unit = {},
) {
    val bar = MaterialTheme.colorScheme.surfaceContainer
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = bar, scrolledContainerColor = bar),
        title = {
            Column {
                Text(title, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
            }
        },
        actions = {
            actions()
            OverflowMenu(menu)
        },
    )
}

/**
 * A screen's search, hoisted so the query survives navigation. The bar shows a
 * magnifying glass; tapping it turns the whole bar into a rounded field with a
 * back arrow, revealed as a circle growing from the icon (MeshConnect's).
 */
class TopBarSearch(
    val query: String,
    val onQueryChange: (String) -> Unit,
    val active: Boolean,
    val onActiveChange: (Boolean) -> Unit,
    val hint: String,
)

/** The magnifying glass that opens [search]. */
@Composable
fun SearchButton(search: TopBarSearch) {
    IconButton(onClick = { search.onActiveChange(true) }) {
        Icon(painterResource(R.drawable.ic_search), stringResource(R.string.search))
    }
}

/**
 * The chat list's bar: page-coloured (no band), title on the left, then
 * [actions]; with [search], its field covers the bar while active. Its own
 * colour is clear: [modifier] paints it ([frostedBehind]).
 * [searchIconEndDp] is where the glass sits from the end, for the reveal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTopBar(
    title: @Composable () -> Unit,
    search: TopBarSearch?,
    searchIconEndDp: Float,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit,
) {
    Box {
        TopAppBar(
            title = title,
            navigationIcon = navigationIcon,
            actions = actions,
            modifier = modifier,
            colors = clearBarColors(),
        )
        if (search != null) SearchOverlay(search, searchIconEndDp, Modifier.matchParentSize())
    }
}

/** A top bar with no colour of its own, for a modifier to paint. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun clearBarColors() = TopAppBarDefaults.topAppBarColors(
    containerColor = Color.Transparent,
    scrolledContainerColor = Color.Transparent,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchOverlay(search: TopBarSearch, iconEndOffsetDp: Float, modifier: Modifier) {
    val progress by animateFloatAsState(
        targetValue = if (search.active) 1f else 0f,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "searchReveal",
    )
    val close = {
        search.onQueryChange("")
        search.onActiveChange(false)
    }
    BackHandler(enabled = search.active, onBack = close)
    if (progress == 0f) return
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val focus = remember { FocusRequester() }
    LaunchedEffect(search.active) { if (search.active) runCatching { focus.requestFocus() } }
    val ground = MaterialTheme.colorScheme.surface

    Box(
        modifier
            .drawWithContent {
                val inset = iconEndOffsetDp.dp.toPx()
                val cx = if (rtl) inset else size.width - inset
                val cy = size.height - 32.dp.toPx()
                val radius = progress * hypot(max(cx, size.width - cx), max(cy, size.height - cy))
                val circle = Path().apply { addOval(Rect(Offset(cx, cy), radius)) }
                clipPath(circle) { this@drawWithContent.drawContent() }
            }
            .background(ground)
            .windowInsetsPadding(TopAppBarDefaults.windowInsets),
    ) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(start = 4.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = close) {
                Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.search_close))
            }
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (search.query.isEmpty()) {
                            Text(
                                search.hint,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        BasicTextField(
                            value = search.query,
                            onValueChange = search.onQueryChange,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    }
                    if (search.query.isNotEmpty()) {
                        IconButton(onClick = { search.onQueryChange("") }) {
                            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.search_clear))
                        }
                    }
                }
            }
        }
    }
}
