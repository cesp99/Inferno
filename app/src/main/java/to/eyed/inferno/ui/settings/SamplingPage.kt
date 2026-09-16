package to.eyed.inferno.ui.settings

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import to.eyed.inferno.engine.GenerationParams
import to.eyed.inferno.engine.modelOrNull
import to.eyed.inferno.ui.S
import to.eyed.inferno.ui.components.CardDivider
import to.eyed.inferno.ui.components.FormField
import to.eyed.inferno.ui.components.GlassButton
import to.eyed.inferno.ui.components.GlassToggle
import to.eyed.inferno.ui.components.SettingsCard
import to.eyed.inferno.ui.components.SheetHeader
import to.eyed.inferno.ui.components.SliderRow
import to.eyed.inferno.ui.theme.Ink
import to.eyed.inferno.ui.theme.RowTitle
import to.eyed.inferno.ui.theme.Typography
import to.eyed.inferno.vm.AppViewModel
import java.util.Locale

// The Generation section as a reusable page: "use the model's sampling" toggle, the
// five expressive sliders (disabled while the model defaults apply, showing the model's values), max tokens and
// Reset. Used by ModelSheet ("Generation") and the Settings screen.

@Composable
fun SamplingPage(appVm: AppViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val settings by appVm.settings.collectAsStateWithLifecycle()
    val engine by appVm.engine.collectAsStateWithLifecycle()
    val useDefaults = settings.useModelSamplingDefaults
    // While the defaults apply the sliders show the effective values (the model card's numbers) read-only.
    val shown = if (useDefaults) appVm.effectiveParams() else settings.params
    val modelName = engine.modelOrNull?.displayName

    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        SheetHeader(S.sampling, Lucide.ArrowLeft, onLeading = onBack, leadingDescription = S.back)
        SettingsCard {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(S.useModelDefaults, style = RowTitle, color = Ink.White)
                        Spacer(Modifier.height(2.dp))
                        Text(if (modelName != null) "$modelName · ${S.useModelDefaultsDesc}" else S.useModelDefaultsDesc, style = Typography.bodySmall, color = Ink.I500)
                    }
                    Spacer(Modifier.width(16.dp))
                    GlassToggle(useDefaults, appVm::setUseModelDefaults)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        SamplingSliders(shown, enabled = !useDefaults, onCommit = { appVm.setParams(it) })
        Spacer(Modifier.height(16.dp))
        MaxTokensField(settings.params, onCommit = { appVm.setParams(it) })
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            GlassButton(S.resetToDefaults, enabled = !useDefaults, onClick = { appVm.setParams(GenerationParams(maxTokens = settings.params.maxTokens)) })
        }
    }
}

/** Temperature / Top-p / Top-k / Min-p / Repeat penalty; each slider commits on release. */
@Composable
fun SamplingSliders(params: GenerationParams, enabled: Boolean, onCommit: (GenerationParams) -> Unit, modifier: Modifier = Modifier) {
    // Drafts live here so dragging never round-trips through DataStore per frame; the release commits. Keyed on
    // the persisted params, so a Reset or an external change re-seeds the sliders.
    var draft by remember(params, enabled) { mutableStateOf(params) }
    SettingsCard(modifier) {
        SliderRow(S.temperature, fmt2(draft.temperature), draft.temperature, { draft = draft.copy(temperature = round2(it)) }, 0f..2f, enabled = enabled, onValueChangeFinished = { onCommit(draft) })
        CardDivider()
        SliderRow(S.topP, fmt2(draft.topP), draft.topP, { draft = draft.copy(topP = round2(it)) }, 0.05f..1f, enabled = enabled, onValueChangeFinished = { onCommit(draft) })
        CardDivider()
        SliderRow(S.topK, draft.topK.toString(), draft.topK.toFloat(), { draft = draft.copy(topK = it.toInt()) }, 0f..200f, steps = 199, enabled = enabled, onValueChangeFinished = { onCommit(draft) })
        CardDivider()
        SliderRow(S.minP, fmt2(draft.minP), draft.minP, { draft = draft.copy(minP = round2(it)) }, 0f..0.5f, enabled = enabled, onValueChangeFinished = { onCommit(draft) })
        CardDivider()
        SliderRow(S.repeatPenalty, fmt2(draft.repeatPenalty), draft.repeatPenalty, { draft = draft.copy(repeatPenalty = round2(it)) }, 1f..1.5f, enabled = enabled, onValueChangeFinished = { onCommit(draft) })
    }
}

/** Max tokens (0 = unlimited): numeric field, committed on every valid edit. */
@Composable
fun MaxTokensField(params: GenerationParams, onCommit: (GenerationParams) -> Unit, modifier: Modifier = Modifier) {
    var text by rememberSaveable(params.maxTokens) { mutableStateOf(if (params.maxTokens == 0) "" else params.maxTokens.toString()) }
    SettingsCard(modifier) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            FormField(
                "${S.maxTokens} · ${S.maxTokensDesc}",
                text,
                onValueChange = { v ->
                    if (v.length <= 6 && v.all { it.isDigit() }) {
                        text = v
                        onCommit(params.copy(maxTokens = v.toIntOrNull() ?: 0))
                    }
                },
                placeholder = "0",
            )
        }
    }
}

private fun fmt2(v: Float) = String.format(Locale.US, "%.2f", v)
private fun round2(v: Float) = Math.round(v * 100f) / 100f
