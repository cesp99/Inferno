package to.eyed.inferno.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import to.eyed.inferno.ui.theme.Ink
import to.eyed.inferno.ui.theme.Numeric
import to.eyed.inferno.ui.theme.RowMeta
import to.eyed.inferno.ui.theme.RowTitle
import to.eyed.inferno.ui.theme.rememberHaptics

// Settings-card rows that SheetKit's SettingRow does not cover: a whole-row switch with a description, a row that
// navigates (value + chevron) and a row that hosts a control underneath its title (connected groups).

/** Title + description on the left, the 44x24 toggle on the right; the whole row is the switch. */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
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
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = RowTitle, color = Ink.White)
            if (description != null) {
                Spacer(Modifier.height(2.dp))
                Text(description, style = RowMeta, color = Ink.I500)
            }
        }
        Spacer(Modifier.width(16.dp))
        // The knob toggles too (a tap on it must not be swallowed); TalkBack sees only the row's switch.
        GlassToggle(checked, onCheckedChange = onCheckedChange, enabled = enabled, modifier = Modifier.clearAndSetSemantics { })
    }
}

/** Row that opens a page or sheet: title, current value in tabular figures, chevron. */
@Composable
fun NavRow(
    title: String,
    value: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    SettingRow(title, description, modifier, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (value != null) {
                Text(value, style = Numeric, color = Ink.I300, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
                Spacer(Modifier.width(6.dp))
            }
            Icon(Lucide.ChevronRight, null, Modifier.size(16.dp), tint = Ink.I500)
        }
    }
}

/** Title + description with a control (connected group, slider) laid out underneath, full width. */
@Composable
fun ControlRow(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    control: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).graphicsLayer { alpha = if (enabled) 1f else 0.5f }) {
        Text(title, style = RowTitle, color = Ink.White)
        if (description != null) {
            Spacer(Modifier.height(2.dp))
            Text(description, style = RowMeta, color = Ink.I500)
        }
        Spacer(Modifier.height(10.dp))
        control()
    }
}
