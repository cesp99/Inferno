package to.eyed.inferno.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import to.eyed.inferno.data.ContextPolicy
import to.eyed.inferno.ui.S
import to.eyed.inferno.ui.components.ConnectedGroup
import to.eyed.inferno.ui.components.ControlRow
import to.eyed.inferno.ui.theme.Ink
import to.eyed.inferno.ui.theme.RowMeta
import to.eyed.inferno.ui.theme.fastEffectsSpec

/**
 * Settings > Chat: "When the context fills up" as a connected group (Rolling / Compact / Stop) with one line under
 * each option; the selected line reads brighter so the group and its explanation never disagree.
 */
@Composable
fun ContextPolicyRow(policy: ContextPolicy, onSelect: (ContextPolicy) -> Unit) {
    val entries = ContextPolicy.entries
    ControlRow(S.contextPolicy) {
        ConnectedGroup(entries.map { it.label }, policy.ordinal, { onSelect(entries[it]) })
        Spacer(Modifier.height(10.dp))
        Column(Modifier.fillMaxWidth()) {
            entries.forEachIndexed { i, p ->
                val selected = p == policy
                val color by animateColorAsState(if (selected) Ink.I100 else Ink.I500, fastEffectsSpec(), label = "policyLine")
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text(p.label, style = RowMeta, color = color, modifier = Modifier.width(64.dp))
                    Text(p.description, style = RowMeta, color = color)
                }
                if (i < entries.lastIndex) Spacer(Modifier.height(2.dp))
            }
        }
    }
}

private val ContextPolicy.label: String
    get() = when (this) { ContextPolicy.ROLLING -> S.policyRolling; ContextPolicy.COMPACT -> S.policyCompact; ContextPolicy.STOP -> S.policyStop }

private val ContextPolicy.description: String
    get() = when (this) { ContextPolicy.ROLLING -> S.policyRollingDesc; ContextPolicy.COMPACT -> S.policyCompactDesc; ContextPolicy.STOP -> S.policyStopDesc }
