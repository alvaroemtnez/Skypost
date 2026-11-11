package io.alvaroedo.skypost.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.alvaroedo.skypost.R

/*
 * Settings and sheet building blocks, after MeshConnect's (SettingsScreen,
 * SettingsComponents, NodeSheetParts), so the two apps read as siblings.
 */

/** The primary-coloured label over a group of settings cards. */
@Composable
fun GroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 20.dp, bottom = 8.dp),
    )
}

/** A rounded group of rows, [inset] from the screen's edges. */
@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    inset: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.padding(horizontal = inset).fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 6.dp), content = content)
    }
}

/** An icon on a tonal circle: the settings rows' and cards' leading mark. */
@Composable
fun IconDisc(
    @DrawableRes icon: Int,
    size: Dp = 40.dp,
    highlighted: Boolean = false,
    destructive: Boolean = false,
) {
    val c = MaterialTheme.colorScheme
    val (bg, fg) = when {
        destructive -> c.errorContainer to c.onErrorContainer
        highlighted -> c.primary to c.onPrimary
        else -> c.secondaryContainer to c.onSecondaryContainer
    }
    Box(Modifier.size(size).background(bg, CircleShape), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, tint = fg, modifier = Modifier.size(size * 0.55f))
    }
}

/**
 * A row in a [SettingsCard]: icon disc, title, what it is set to; a trailing
 * control (a switch) or nothing. Red when [destructive].
 */
@Composable
fun SettingsTileRow(
    title: String,
    subtitle: String?,
    @DrawableRes icon: Int,
    onClick: (() -> Unit)?,
    destructive: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        IconDisc(icon, destructive = destructive)
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.invoke()
    }
}

/**
 * A folding section drawn as a card: icon, title and a line saying what is
 * inside, then the content when opened (composed only while open).
 */
@Composable
fun CardSection(
    title: String,
    subtitle: String? = null,
    @DrawableRes icon: Int,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    val turn by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                IconDisc(icon, highlighted = expanded)
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    subtitle?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Icon(
                    painterResource(R.drawable.ic_expand_more),
                    contentDescription = stringResource(if (expanded) R.string.collapse else R.string.expand),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.graphicsLayer { rotationZ = turn },
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(bottom = 8.dp)) {
                    HorizontalDivider(
                        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    content()
                }
            }
        }
    }
}

/** A card that is always open: icon and title, an optional action at the header's end, then content. */
@Composable
fun PanelCard(
    title: String,
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(top = 12.dp, bottom = 12.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IconDisc(icon, size = 36.dp)
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                action?.invoke()
            }
            content()
        }
    }
}

/** A round tonal button with its label under it: a card's quick actions. */
@Composable
fun RowScope.QuickAction(
    @DrawableRes icon: Int,
    label: String,
    on: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable(enabled = enabled, onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = CircleShape,
            color = if (on) c.primary else c.secondaryContainer,
            modifier = Modifier.size(48.dp).graphicsLayer { alpha = if (enabled) 1f else 0.38f },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), null, tint = if (on) c.onPrimary else c.onSecondaryContainer)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

/** One fact: a small label over its value, on a tinted rounded tile. */
@Composable
fun InfoTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    valueColor: Color = Color.Unspecified,
    /** Keep the value on one line, shrinking the text if it has to. */
    fit: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    leading?.let {
                        it()
                        Spacer(Modifier.width(8.dp))
                    }
                    val style = if (mono) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium
                    if (fit) {
                        BasicText(
                            value,
                            style = style.copy(color = valueColor.takeOrElse { LocalContentColor.current }),
                            maxLines = 1,
                            softWrap = false,
                            autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = style.fontSize),
                        )
                    } else {
                        Text(
                            value,
                            style = style,
                            fontFamily = if (mono) FontFamily.Monospace else null,
                            color = valueColor,
                        )
                    }
                }
            }
        }
    }
}

/** A row in a sheet's lists: icon, label, optional detail and trailing; red when destructive. */
@Composable
fun SheetActionRow(
    @DrawableRes icon: Int,
    label: String,
    detail: String? = null,
    destructive: Boolean = false,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(icon),
            null,
            tint = if (destructive) color else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = color)
            detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.invoke()
    }
}

/** A section title inside a sheet, in the primary colour. */
@Composable
fun SheetHeading(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 16.dp, bottom = 6.dp),
    )
}

/** A sheet's header: a 56 dp mark, the name, and a line under it. */
@Composable
fun SheetHeader(title: String, subtitle: String?, mark: @Composable () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        mark()
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.invoke()
    }
}
