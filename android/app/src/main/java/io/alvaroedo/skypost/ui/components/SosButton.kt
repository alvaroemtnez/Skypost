package io.alvaroedo.skypost.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.alvaroedo.skypost.R
import io.alvaroedo.skypost.ui.theme.extraColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long the SOS button must be held. */
const val SOS_HOLD_MS = 3_000

/** One heartbeat: a strong beat, a weaker one, a rest. */
private const val BEAT_MS = 800

/**
 * The SOS button: hold it for [SOS_HOLD_MS]. While held it beats like a heart
 * (and the phone pulses with it) and a ring fills; when the ring closes,
 * [onHeld] opens the confirmation. A short tap only calls [onTooShort], so a
 * pocket or a stray finger never gets as far as the confirmation. Screen
 * readers activate it directly (the confirmation still follows).
 */
@Composable
fun SosButton(onHeld: () -> Unit, onTooShort: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val held by rememberUpdatedState(onHeld)
    val tooShort by rememberUpdatedState(onTooShort)
    val scope = rememberCoroutineScope()
    val ring = remember { Animatable(0f) }
    var holding by remember { mutableStateOf(false) }
    val heart = rememberInfiniteTransition(label = "heartbeat")
    val beat by heart.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = BEAT_MS
                1f at 0
                1.10f at 110
                1f at 230
                1.06f at 340
                1f at 520
            },
        ),
        label = "beat",
    )
    LaunchedEffect(holding) {
        while (holding) {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            delay(BEAT_MS.toLong())
        }
    }
    val emergency = MaterialTheme.extraColors.emergency
    val description = stringResource(R.string.sos_hold_description)

    Box(modifier.size(92.dp), contentAlignment = Alignment.Center) {
        if (holding || ring.value > 0f) {
            CircularProgressIndicator(
                progress = { ring.value },
                modifier = Modifier.size(92.dp),
                color = emergency,
                trackColor = emergency.copy(alpha = 0.25f),
                strokeWidth = 6.dp,
            )
        }
        Surface(
            shape = CircleShape,
            color = emergency,
            contentColor = MaterialTheme.extraColors.onEmergency,
            shadowElevation = 6.dp,
            modifier = Modifier
                .size(72.dp)
                .graphicsLayer {
                    val s = if (holding) beat else 1f
                    scaleX = s
                    scaleY = s
                }
                .semantics {
                    role = Role.Button
                    contentDescription = description
                    onClick { held(); true }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            holding = true
                            val fill = scope.launch {
                                ring.snapTo(0f)
                                ring.animateTo(1f, tween(SOS_HOLD_MS, easing = LinearEasing))
                                holding = false
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                ring.snapTo(0f)
                                held()
                            }
                            val released = tryAwaitRelease()
                            if (fill.isActive) {
                                fill.cancel()
                                holding = false
                                scope.launch { ring.animateTo(0f, tween(200)) }
                                if (released) tooShort()
                            }
                        },
                    )
                },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_sos), null, Modifier.size(52.dp))
            }
        }
    }
}
