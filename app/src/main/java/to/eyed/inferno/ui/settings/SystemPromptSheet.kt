package to.eyed.inferno.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import to.eyed.inferno.ui.S
import to.eyed.inferno.ui.components.FormField
import to.eyed.inferno.ui.components.GlassButton
import to.eyed.inferno.ui.components.InfernoSheet
import to.eyed.inferno.ui.components.PrimaryButton
import to.eyed.inferno.ui.components.SheetHeader
import to.eyed.inferno.ui.theme.Ink
import to.eyed.inferno.ui.theme.Typography

/** Multi-line editor for the system prompt (spec 5.6): applies to new turns, Save commits, Clear empties. */
@Composable
fun SystemPromptSheet(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var draft by rememberSaveable { mutableStateOf(current) }
    InfernoSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp).imePadding()) {
            SheetHeader(S.systemPrompt, Lucide.X, onLeading = onDismiss)
            FormField("", draft, { draft = it }, placeholder = S.systemPromptPlaceholder, multiline = true)
            Spacer(Modifier.height(8.dp))
            Text(S.systemPromptDesc, style = Typography.bodySmall, color = Ink.I500)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton(S.save, onClick = { onSave(draft.trim()); onDismiss() }, modifier = Modifier.weight(1f))
                GlassButton(S.clear, enabled = draft.isNotEmpty(), onClick = { draft = "" })
            }
        }
    }
}
