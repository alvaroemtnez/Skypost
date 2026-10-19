package io.alvaroedo.skypost.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext

/**
 * Material 3, with dynamic colour (Material You) on Android 12+ and the
 * generated sky-blue scheme ([LightColors]/[DarkColors]) before that.
 *
 * Material 3 Expressive (MaterialExpressiveTheme, MotionScheme.expressive())
 * is internal in the stable material3 1.4 and public only in the 1.5
 * pre-releases. When 1.5 is stable, switch here.
 */
@Composable
fun SkypostTheme(
    dark: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        dark -> DarkColors
        else -> LightColors
    }
    CompositionLocalProvider(LocalExtraColors provides if (dark) DarkExtraColors else LightExtraColors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
