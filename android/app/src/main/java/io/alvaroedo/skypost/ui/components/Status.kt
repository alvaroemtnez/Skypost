package io.alvaroedo.skypost.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.alvaroedo.skypost.R
import io.alvaroedo.skypost.model.Pharos
import io.alvaroedo.skypost.radio.RadioState

/** A Pharos heard longer ago than this counts as silent (empty bars). */
const val HEARD_RECENTLY_MS = 60 * 60_000L

/**
 * Bars for the SNR the Pharos last reported for us, by the lanes it allows:
 * 4 at ≥ −10 dB (fast lanes), 3 at ≥ −18 (Turbo), 2 at ≥ −24 (Normal),
 * 1 below (Slow only); 0 with no recent report.
 */
fun pharosBars(snrDb: Int?): Int = when {
    snrDb == null -> 0
    snrDb >= -10 -> 4
    snrDb >= -18 -> 3
    snrDb >= -24 -> 2
    else -> 1
}

/** The SNR of [p] if it was heard recently, else null. */
fun recentSnr(p: Pharos?, now: Long = System.currentTimeMillis()): Int? =
    p?.snrDb?.takeIf { p.lastHeardMillis?.let { now - it < HEARD_RECENTLY_MS } == true }

/** Four ascending bars, like the phone's own cell meter (MeshConnect's). */
@Composable
fun SignalBars(bars: Int, live: Boolean, modifier: Modifier = Modifier) {
    val on = MaterialTheme.colorScheme.onSurface
    val off = on.copy(alpha = if (live) 0.22f else 0.15f)
    Canvas(modifier.size(width = 16.dp, height = 14.dp)) {
        val gap = 1.5.dp.toPx()
        val w = (size.width - gap * 3) / 4
        for (i in 0 until 4) {
            val h = size.height * (i + 1) / 4f
            drawRoundRect(
                color = if (i < bars) on else off,
                topLeft = Offset(i * (w + gap), size.height - h),
                size = Size(w, h),
                cornerRadius = CornerRadius(1.dp.toPx()),
            )
        }
    }
}

/** A tappable cluster in the top bar: an icon and a short name. */
@Composable
private fun StatusChip(desc: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Row(
        Modifier
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = desc }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@Composable
private fun ChipLabel(text: String, dim: Boolean) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 72.dp).alpha(if (dim) 0.6f else 1f),
    )
}

/**
 * The active Pharos in the top bar: signal bars for how well it hears us and
 * its name. Empty bars when it has not been heard for an hour; just the bars
 * when there is no Pharos.
 */
@Composable
fun PharosStatus(active: Pharos?, onClick: () -> Unit, now: Long = System.currentTimeMillis()) {
    val snr = recentSnr(active, now)
    val desc = if (active == null) {
        stringResource(R.string.pharos_none)
    } else {
        stringResource(R.string.pharos_indicator, active.card.alias, heardAgo(active.lastHeardMillis, now))
    }
    StatusChip(desc, onClick) {
        SignalBars(pharosBars(snr), live = snr != null)
        if (active != null) ChipLabel(active.card.alias, dim = snr == null)
    }
}

/**
 * The radio in the top bar: its icon and short name ("QMX+", "G90"), faded
 * while disconnected and pulsing while it connects.
 */
@Composable
fun RadioStatus(state: RadioState, shortName: String?, onClick: () -> Unit) {
    val desc = stringResource(state.label())
    val connected = state == RadioState.AUDIO_ONLY || state == RadioState.READY || state == RadioState.TRANSMITTING
    val pulse = rememberInfiniteTransition(label = "radio")
    val a by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "radioAlpha",
    )
    val alpha = when {
        state == RadioState.CONNECTING -> a
        connected -> 1f
        else -> 0.45f
    }
    StatusChip(desc, onClick) {
        Icon(
            painterResource(R.drawable.ic_radio),
            null,
            tint = if (state == RadioState.TRANSMITTING) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp).alpha(alpha),
        )
        shortName?.let { ChipLabel(it, dim = !connected) }
    }
}

/** What the radio is doing, in words. */
fun RadioState.label(): Int = when (this) {
    RadioState.DISCONNECTED -> R.string.radio_disconnected
    RadioState.CONNECTING -> R.string.radio_connecting
    RadioState.AUDIO_ONLY -> R.string.radio_audio_only
    RadioState.READY, RadioState.TRANSMITTING -> R.string.radio_ready
}
