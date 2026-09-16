package to.eyed.inferno.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import to.eyed.inferno.ui.theme.Ink

// Flat monochrome surfaces: solid raised fills, an optional hairline border,
// zero shadows or gradients.

/** Quiet raised surface: rows, chips, quiet panels. */
fun Modifier.surfaceLow(shape: Shape): Modifier =
    clip(shape).background(Ink.SurfaceLow)

/** Standard raised surface: composer, menus, sheets. */
fun Modifier.surfaceRaised(shape: Shape): Modifier =
    clip(shape).background(Ink.Surface)

/** Strong raised surface: user bubbles, active chips. */
fun Modifier.surfaceHigh(shape: Shape): Modifier =
    clip(shape).background(Ink.SurfaceHigh)

/** Raised surface with a hairline border - cards and popups. */
fun Modifier.surfaceCard(shape: Shape, fill: Color = Ink.SurfaceLow): Modifier =
    clip(shape).background(fill).border(1.dp, Ink.I700, shape)
