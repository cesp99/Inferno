@file:OptIn(ExperimentalMaterial3Api::class)

package to.eyed.inferno.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.eyed.inferno.ui.theme.ChipLabel
import to.eyed.inferno.ui.theme.Eyebrow as EyebrowStyle
import to.eyed.inferno.ui.theme.Ink
import to.eyed.inferno.ui.theme.RowTitle
import to.eyed.inferno.ui.theme.Typography
import to.eyed.inferno.ui.theme.fastEffectsSpec
import to.eyed.inferno.ui.theme.fastSpatialSpec
import to.eyed.inferno.ui.theme.pressScale
import to.eyed.inferno.ui.theme.rememberHaptics
import to.eyed.inferno.ui.theme.whiteA

// Every control: indication = null (no ripple), press feedback through Modifier.pressScale
// (graphicsLayer, no recomposition per frame), a Role for TalkBack, and a 40 dp touch target
// through minimumInteractiveComponentSize() even when the visual is 36/32 dp.

/** Circular ghost icon button: transparent at rest, subtle fill on press. */
@Composable
fun GhostIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    iconSize: Dp = 17.dp,
    tint: Color = Ink.I300,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Read inside drawBehind so the colour animation never recomposes.
    val bg by animateColorAsState(
        if (pressed) Ink.SurfaceHigh else Color.Transparent,
        fastEffectsSpec(),
        label = "ghostBg",
    )
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .size(size)
            .pressScale(interaction, 0.92f)
            .clip(CircleShape)
            .drawBehind { drawRect(bg) }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(iconSize), tint = if (enabled) tint else Ink.I500)
    }
}

/** Full-width white pill CTA. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .pressScale(interaction, 0.97f)
            .clip(CircleShape)
            .background(if (enabled) Ink.White else Ink.SurfaceLow)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(vertical = 15.dp, horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            color = if (enabled) Ink.Pitch else whiteA(0.25f),
            style = Typography.labelLarge.copy(fontSize = 15.sp, lineHeight = 20.sp),
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        if (trailing != null) trailing()
    }
}

/** Secondary flat pill button; optional leading icon; `textColor = Ink.Danger` for destructive. */
@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    textColor: Color = Ink.White,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .minimumInteractiveComponentSize()
            .pressScale(interaction, 0.96f)
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
            .clip(CircleShape)
            .background(Ink.SurfaceHigh)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(14.dp), tint = textColor)
        Text(text, color = textColor, style = Typography.labelMedium)
    }
}

/** Track + knob of the 44x24 toggle, all drawn in the draw phase. */
@Composable
private fun ToggleVisual(checked: Boolean, modifier: Modifier = Modifier) {
    val track by animateColorAsState(if (checked) Ink.White else Ink.SurfaceHigh, fastEffectsSpec(), label = "track")
    val knob by animateColorAsState(if (checked) Ink.Pitch else Ink.I500, fastEffectsSpec(), label = "knob")
    val knobX by animateDpAsState(if (checked) 22.dp else 3.dp, fastSpatialSpec(), label = "knobX")
    Box(
        modifier
            .size(width = 44.dp, height = 24.dp)
            .drawBehind {
                drawRoundRect(track, cornerRadius = CornerRadius(12.dp.toPx()))
                val r = 9.dp.toPx()
                drawCircle(knob, radius = r, center = Offset(knobX.toPx() + r, size.height / 2f))
            },
    )
}

/** The 44x24 monochrome toggle; reads as a switch in TalkBack. */
@Composable
fun GlassToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptics = rememberHaptics()
    ToggleVisual(
        checked,
        modifier
            .minimumInteractiveComponentSize()
            .clip(CircleShape)
            .toggleable(
                value = checked,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.Switch,
            ) { haptics.toggle(it); onCheckedChange(it) }
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f },
    )
}

/** Labelled toggle row: the whole row is the switch, the visual is inert. */
@Composable
fun GlassToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptics = rememberHaptics()
    Row(
        modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .toggleable(
                value = checked,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.Switch,
            ) { haptics.toggle(it); onCheckedChange(it) }
            .padding(vertical = 8.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = RowTitle, color = Ink.White, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        ToggleVisual(checked)
    }
}

/** Segmented control with one white pill that slides between options (Role.Tab). */
@Composable
fun SegmentedPill(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var trackWidth by remember { mutableIntStateOf(0) }
    val segmentWidth = if (options.isEmpty()) 0f else trackWidth.toFloat() / options.size
    val spec = fastSpatialSpec<Float>()
    val pillX = remember { Animatable(0f) }
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(selected, segmentWidth) {
        if (segmentWidth == 0f) return@LaunchedEffect
        val target = selected * segmentWidth
        // First layout places the pill without a slide from x = 0; later changes slide.
        if (placed) pillX.animateTo(target, spec) else { pillX.snapTo(target); placed = true }
    }
    Box(
        modifier
            .surfaceLow(CircleShape)
            .padding(4.dp)
            .onSizeChanged { trackWidth = it.width }
            .selectableGroup(),
    ) {
        Box(Modifier.matchParentSize()) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(with(density) { segmentWidth.toDp() })
                    .graphicsLayer { translationX = pillX.value }
                    .background(Ink.White, CircleShape),
            )
        }
        Row(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, label ->
                val active = i == selected
                val textColor by animateColorAsState(
                    if (active) Ink.Pitch else Ink.I500,
                    fastEffectsSpec(),
                    label = "segText",
                )
                Box(
                    Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .selectable(
                            selected = active,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Tab,
                        ) { onSelect(i) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = textColor,
                        style = Typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Hairline divider. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(whiteA(0.08f)))
}

/** SurfaceHigh circle pill (12x7): the model chip in TopNav, file/status chips. */
@Composable
fun ChipPill(
    text: String,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    textColor: Color = Ink.I100,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    // A status chip (no onClick) is plain text to TalkBack: no Button role, no "double tap to activate".
    val click = if (onClick != null) {
        Modifier
            .minimumInteractiveComponentSize()
            .pressScale(interaction, 0.96f)
            .clip(CircleShape)
            .background(Ink.SurfaceHigh)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
    } else {
        Modifier.clip(CircleShape).background(Ink.SurfaceHigh)
    }
    Row(
        modifier
            .then(click)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(14.dp), tint = textColor)
        Text(text, style = ChipLabel, color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (trailing != null) trailing()
    }
}

/** 10 sp uppercase eyebrow with wide tracking. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = Ink.I500) {
    Text(text.uppercase(), style = EyebrowStyle, color = color, modifier = modifier)
}

/** Icon centred in a filled circle (NavCard leading, status glyphs). */
@Composable
fun IconCircle(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    iconSize: Dp = 17.dp,
    tint: Color = Ink.I100,
    fill: Color = Ink.SurfaceHigh,
    contentDescription: String? = null,
) {
    Box(modifier.size(size).clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription, Modifier.size(iconSize), tint = tint)
    }
}
