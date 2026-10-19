package io.alvaroedo.skypost.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Semantic colours Material 3 has no role for. These are the only colours
 * defined by hand: an emergency red that must not follow the wallpaper
 * (dynamic `error` can be pale), a traffic-light scale for signal reports,
 * and the networks' own colours for the chat-kind badges.
 */
@Immutable
data class ExtraColors(
    val emergency: Color,
    val onEmergency: Color,
    /** SNR scale, best to worst. */
    val signalGood: Color,
    val signalFair: Color,
    val signalPoor: Color,
    val signalBad: Color,
    /** Badge backgrounds for bridged chats, with [onBrand] icons. */
    val meshcore: Color = Color(0xFF111827),
    val whatsapp: Color = Color(0xFF25D366),
    val signal: Color = Color(0xFF3B45FD),
    val onBrand: Color = Color(0xFFFFFFFF),
)

val LightExtraColors = ExtraColors(
    emergency = Color(0xFFD50000),
    onEmergency = Color(0xFFFFFFFF),
    signalGood = Color(0xFF2E7D32),
    signalFair = Color(0xFFF9A825),
    signalPoor = Color(0xFFEF6C00),
    signalBad = Color(0xFFC62828),
)

val DarkExtraColors = ExtraColors(
    emergency = Color(0xFFE53935),
    onEmergency = Color(0xFFFFFFFF),
    signalGood = Color(0xFF66BB6A),
    signalFair = Color(0xFFFFEE58),
    signalPoor = Color(0xFFFFA726),
    signalBad = Color(0xFFEF5350),
)

val LocalExtraColors = staticCompositionLocalOf { LightExtraColors }

/** `MaterialTheme.extraColors.emergency`, next to `MaterialTheme.colorScheme`. */
val MaterialTheme.extraColors: ExtraColors
    @Composable @ReadOnlyComposable
    get() = LocalExtraColors.current
