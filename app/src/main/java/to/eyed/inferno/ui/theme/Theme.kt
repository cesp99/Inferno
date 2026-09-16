@file:OptIn(ExperimentalMaterial3Api::class)

package to.eyed.inferno.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Corner radii: 12 controls, 16 rows, 24 cards / composer, 28 sheets.
object Radii {
    val control = 12.dp
    val row = 16.dp
    val card = 24.dp
    val composer = 24.dp
    val sheet = 28.dp
}

// surfaceTint = Transparent kills M3 tonal-elevation tinting on sheets and menus.
// error = White is deliberate: errors are never coloured through the scheme, only via Ink.Danger explicitly.
private val MonochromeScheme = darkColorScheme(
    primary = Ink.White,
    onPrimary = Ink.Pitch,
    primaryContainer = whiteA(0.10f),
    onPrimaryContainer = Ink.White,
    secondary = Ink.I300,
    onSecondary = Ink.Pitch,
    secondaryContainer = whiteA(0.06f),
    onSecondaryContainer = Ink.I100,
    tertiary = Ink.I500,
    onTertiary = Ink.Pitch,
    background = Ink.Pitch,
    onBackground = Ink.I100,
    surface = Ink.Pitch,
    onSurface = Ink.I100,
    surfaceVariant = Ink.I850,
    onSurfaceVariant = Ink.I500,
    surfaceContainer = Ink.I900,
    surfaceContainerLowest = Ink.Pitch,
    surfaceContainerLow = Ink.I900,
    surfaceContainerHigh = Ink.I850,
    surfaceContainerHighest = Ink.I800,
    surfaceBright = Ink.I800,
    surfaceDim = Ink.Pitch,
    surfaceTint = Color.Transparent,
    error = Ink.White,
    onError = Ink.Pitch,
    outline = whiteA(0.10f),
    outlineVariant = whiteA(0.06f),
    scrim = Color(0xB3000000),
    inverseSurface = Ink.I100,
    inverseOnSurface = Ink.Pitch,
    inversePrimary = Ink.Pitch,
)

private val InfernoShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(Radii.control),
    medium = RoundedCornerShape(Radii.row),
    large = RoundedCornerShape(Radii.card),
    largeIncreased = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(Radii.sheet),
    extraLargeIncreased = RoundedCornerShape(32.dp),
    extraExtraLarge = RoundedCornerShape(40.dp),
)

/**
 * One theme: pure black canvas, white ink. No variants, no dynamic colour.
 *
 * `MotionScheme.standard()` (0.9 damping) rather than `expressive()` is the one-time character
 * decision: the app wants Apple restraint, and mixing critically damped custom springs with
 * 0.6-0.8 damped M3 component springs would make sheets overshoot while buttons do not.
 * `LocalMinimumInteractiveComponentSize = 40.dp` keeps the 36/32 dp visual sizes while
 * guaranteeing a 40 dp touch target on every control that opts in via
 * `Modifier.minimumInteractiveComponentSize()`.
 */
@Composable
fun InfernoTheme(content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = MonochromeScheme,
        motionScheme = MotionScheme.standard(),
        shapes = InfernoShapes,
        typography = Typography,
    ) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 40.dp) {
            Surface(color = Ink.Pitch, contentColor = Ink.I100, content = content)
        }
    }
}
