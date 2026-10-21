package com.example.tabletpanel.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.tabletpanel.weather.Sky
import kotlin.math.min

object PanelColors {
    val Text = Color(0xFFF2F6FA)
    val TextSecondary = Color(0xFFB4C1D0)
    val TextDim = Color(0xFF75859A)
    val Card = Color(0x14FFFFFF)
    val Divider = Color(0x22FFFFFF)
    val Accent = Color(0xFF5CB2FF)
    val Rain = Color(0xFF4FA3FF)
    val RainLight = Color(0x664FA3FF)
    val Warm = Color(0xFFFFB547)
    val Cold = Color(0xFF7CC4FF)
    val Sun = Color(0xFFFFC83D)
    val Storm = Color(0xFFC29BFF)
    val Ok = Color(0xFF34D399)
    val Info = Color(0xFF2563EB)
    val Warning = Color(0xFFD97706)
    val Critical = Color(0xFFDC2626)
}

/** True when the screen is wider than tall; layouts pick denser variants in portrait. */
val LocalLandscape = staticCompositionLocalOf { true }

@Composable
fun PanelTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = PanelColors.Accent,
            background = Color(0xFF0B1220),
            surface = Color(0xFF162132),
            onSurface = PanelColors.Text,
        ),
        content = content,
    )
}

/**
 * Lays the UI out against a reference screen (1280x800 landscape / 800x1280 portrait) and scales
 * it to fill the real one, so one set of sizes looks right on a 7" or a 13" tablet.
 */
@Composable
fun ScaledToReference(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth >= maxHeight
        val refW = if (landscape) 1280.dp else 800.dp
        val refH = if (landscape) 800.dp else 1280.dp
        val factor = min(maxWidth / refW, maxHeight / refH)
        val base = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(base.density * factor, fontScale = 1f),
            LocalLandscape provides landscape,
        ) { content() }
    }
}

/** Background tint that follows the weather: blue skies, night navy, slate rain, violet storms. */
fun skyBrush(sky: Sky?, isDay: Boolean): Brush {
    val (top, bottom) = when {
        sky == null -> 0xFF14202F to 0xFF080D15
        sky == Sky.THUNDER -> 0xFF2B1F45 to 0xFF0C0914
        sky == Sky.RAIN || sky == Sky.DRIZZLE || sky == Sky.SLEET -> 0xFF1B2B3D to 0xFF0A1017
        sky == Sky.SNOW -> 0xFF33465B to 0xFF121A24
        sky == Sky.FOG -> 0xFF363D46 to 0xFF14171B
        !isDay -> 0xFF0B1636 to 0xFF04070F
        sky == Sky.CLOUDY -> 0xFF29384A to 0xFF0F151D
        else -> 0xFF0F4274 to 0xFF0A1729
    }
    return Brush.verticalGradient(listOf(Color(top), Color(bottom)))
}
