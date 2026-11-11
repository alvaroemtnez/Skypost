package io.alvaroedo.skypost.ui.components

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/*
 * A frosted top bar, as in Signal: the list scrolls under the bar, and the
 * bar shows it blurred and tinted. The list records what it draws into a
 * layer ([frostSource]); the bar draws that layer again, blurred, behind
 * itself ([frostedBehind]). Both start at the top of the screen, so no offset
 * is needed. Blur needs Android 12; before that the copy is drawn sharp
 * under a denser tint.
 */

/** Records what this draws into [layer], for a frosted bar above it. */
fun Modifier.frostSource(layer: GraphicsLayer): Modifier = drawWithContent {
    layer.record { this@drawWithContent.drawContent() }
    drawLayer(layer)
}

/**
 * Draws [source] blurred behind this, under a tint of the page colour.
 * [progress] (0–1, read at draw time so scrolling does not recompose) fades
 * the frost in as content starts to pass under the bar.
 */
@Composable
fun Modifier.frostedBehind(source: GraphicsLayer, progress: () -> Float): Modifier {
    val blur = rememberGraphicsLayer()
    val radius = with(LocalDensity.current) { 24.dp.toPx() }
    val ground = MaterialTheme.colorScheme.surface
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    return drawBehind {
        drawRect(ground)
        val p = progress().coerceIn(0f, 1f)
        if (p == 0f) return@drawBehind
        if (canBlur) {
            val r = radius * p
            blur.renderEffect = BlurEffect(r, r, TileMode.Decal)
        }
        blur.record { drawLayer(source) }
        drawLayer(blur)
        drawRect(ground.copy(alpha = if (canBlur) 0.6f else 0.9f))
    }
}
