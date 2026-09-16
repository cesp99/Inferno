@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package to.eyed.inferno.ui.components

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import to.eyed.inferno.ui.theme.Ink
import to.eyed.inferno.ui.theme.Numeric
import to.eyed.inferno.ui.theme.RowTitle
import to.eyed.inferno.ui.theme.Typography
import to.eyed.inferno.ui.theme.whiteA

// Material 3 Expressive pieces the spec names (6.3), restyled to the monochrome language: connected
// ToggleButton groups (shape-morph on press, white when checked), the wavy progress bar (white on 12 % white,
// frozen when paused) and the expressive slider (thick track, bar thumb). Nothing here draws a ripple.

/** Ripple-free indication for the M3 components that read `LocalIndication` (the language has no ripple). */
private object NoIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = object : Modifier.Node() {}
    override fun equals(other: Any?) = other === this
    override fun hashCode() = 0x1F
}

/** Runs [content] with the ripple removed from every M3 component inside. */
@Composable
fun NoRipple(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalIndication provides NoIndication, content = content)
}

/**
 * Connected `ToggleButton` group with one selected option (Role.RadioButton). The checked button is a white
 * pill, the others sit on SurfaceHigh; the M3 shape morph on press is the only motion.
 */
@Composable
fun ConnectedGroup(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = ToggleButtonDefaults.colors(
        containerColor = Ink.SurfaceHigh,
        contentColor = Ink.I300,
        disabledContainerColor = Ink.SurfaceLow,
        disabledContentColor = Ink.I500,
        checkedContainerColor = Ink.White,
        checkedContentColor = Ink.Pitch,
    )
    NoRipple {
        Row(
            modifier.fillMaxWidth().selectableGroup().graphicsLayer { alpha = if (enabled) 1f else 0.5f },
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            options.forEachIndexed { i, label ->
                val checked = i == selected
                val shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                }
                ToggleButton(
                    checked = checked,
                    onCheckedChange = { if (!checked) onSelect(i) },
                    modifier = Modifier.weight(1f).heightIn(min = 40.dp).semantics { role = Role.RadioButton; this.selected = checked },
                    enabled = enabled,
                    shapes = shapes,
                    colors = colors,
                    contentPadding = ButtonGroupDefaults.connectedButtonContentPadding(),
                ) {
                    Text(label, style = Typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun ButtonGroupDefaults.connectedButtonContentPadding() =
    androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 8.dp)

/**
 * Determinate wavy bar: white on 12 % white. [frozen] flattens the wave (a paused download) and [indeterminate]
 * plays the M3 indeterminate wave (verifying). Height is the M3 default (10 dp) so the stroke reads at 4 dp.
 */
@Composable
fun WavyBar(
    progress: Float,
    modifier: Modifier = Modifier,
    frozen: Boolean = false,
    indeterminate: Boolean = false,
    color: Color = Ink.White,
) {
    val stroke = Stroke(width = with(LocalDensity.current) { 4.dp.toPx() }, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    if (indeterminate) {
        LinearWavyProgressIndicator(
            modifier = modifier.fillMaxWidth(),
            color = color,
            trackColor = whiteA(0.12f),
            stroke = stroke,
            trackStroke = stroke,
        )
    } else {
        val clamped = progress.coerceIn(0f, 1f)
        LinearWavyProgressIndicator(
            progress = { clamped },
            modifier = modifier.fillMaxWidth(),
            color = color,
            trackColor = whiteA(0.12f),
            stroke = stroke,
            trackStroke = stroke,
            amplitude = if (frozen) { _ -> 0f } else { p -> if (p <= 0.02f || p >= 0.98f) 0f else 1f },
        )
    }
}

/** Expressive slider in white on 12 % white with the step ticks hidden; the value snaps to [steps] by itself. */
@Composable
fun InkSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    NoRipple {
        Slider(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier.fillMaxWidth(),
            enabled = enabled,
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = onValueChangeFinished,
            colors = SliderDefaults.colors(
                thumbColor = Ink.White,
                activeTrackColor = Ink.White,
                inactiveTrackColor = whiteA(0.12f),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
                disabledThumbColor = Ink.I500,
                disabledActiveTrackColor = Ink.I500,
                disabledInactiveTrackColor = whiteA(0.08f),
                disabledActiveTickColor = Color.Transparent,
                disabledInactiveTickColor = Color.Transparent,
            ),
        )
    }
}

/** Title on the left, the live value (tabular) on the right, the slider underneath: one sampling / context row. */
@Composable
fun SliderRow(
    title: String,
    valueLabel: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    enabled: Boolean = true,
    description: String? = null,
    onValueChangeFinished: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).graphicsLayer { alpha = if (enabled) 1f else 0.5f }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = RowTitle, color = Ink.White, modifier = Modifier.weight(1f))
            Text(valueLabel, style = Numeric, color = Ink.I100)
            if (trailing != null) trailing()
        }
        if (description != null) {
            Spacer(Modifier.height(2.dp))
            Text(description, style = Typography.bodySmall, color = Ink.I500)
        }
        InkSlider(value, onValueChange, valueRange, steps = steps, enabled = enabled, onValueChangeFinished = onValueChangeFinished)
    }
}
