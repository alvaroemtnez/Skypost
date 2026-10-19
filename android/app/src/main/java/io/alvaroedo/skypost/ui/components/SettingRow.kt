package io.alvaroedo.skypost.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A settings row like `ListItem`, but the leading icon and trailing control
 * stay centred on the title and subtitle together, however many lines the
 * subtitle takes (`ListItem` pins them to the top for three-line rows).
 */
@Composable
fun SettingRow(
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    leadingContent: (@Composable () -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        leadingContent?.let {
            CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant) { Box { it() } }
        }
        Column(Modifier.weight(1f)) {
            CompositionLocalProvider(LocalContentColor provides colors.onSurface) {
                ProvideTextStyle(MaterialTheme.typography.bodyLarge) { headlineContent() }
            }
            supportingContent?.let {
                CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant) {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium) { it() }
                }
            }
        }
        trailingContent?.let { Box { it() } }
    }
}
