package to.eyed.inferno.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import to.eyed.inferno.ui.S
import to.eyed.inferno.ui.theme.Ink
import to.eyed.inferno.ui.theme.Radii
import to.eyed.inferno.ui.theme.Typography
import to.eyed.inferno.ui.theme.rememberHaptics

/**
 * Flat anchored menu: M3 `DropdownMenu` (so it anchors to the pressed element and animates with the
 * theme's motion scheme) styled to the language - no elevation, no tint, hairline border. Rows are
 * [MenuActionRow]s, never `DropdownMenuItem` (that one brings its own ripple and paddings).
 */
@Composable
fun GlassMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    minWidth: Dp = 200.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = modifier.widthIn(min = minWidth),
        offset = offset,
        shape = RoundedCornerShape(Radii.row + 4.dp),
        containerColor = Ink.Surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, Ink.I700),
        content = { content() },
    )
}

/** One menu row: 16 dp icon + 14 sp label. `tint = Ink.Danger` for destructive rows. */
@Composable
fun MenuActionRow(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    tint: Color = Ink.I100,
    dim: Boolean = false,
    onClick: () -> Unit,
) {
    val haptics = rememberHaptics()
    val color = if (dim) Ink.I500 else tint
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(Radii.control))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = { haptics.tap(); onClick() },
            )
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = color)
        Text(label, style = Typography.labelLarge, color = color)
    }
}

/**
 * Destructive menu row that needs two taps: the first arms it ("Tap again to delete"), the second
 * fires [onConfirm]. Auto-disarms after 3 s. Same contract as [ConfirmTwice], menu-shaped.
 */
@Composable
fun MenuConfirmRow(
    icon: ImageVector,
    label: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    confirmLabel: String = S.tapAgainToDelete,
) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) { if (armed) { delay(3_000); armed = false } }
    MenuActionRow(
        icon = icon,
        label = if (armed) confirmLabel else label,
        tint = Ink.Danger,
        modifier = modifier,
        onClick = { if (armed) { armed = false; onConfirm() } else armed = true },
    )
}
