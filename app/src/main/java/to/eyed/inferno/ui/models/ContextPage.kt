package to.eyed.inferno.ui.models

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
import androidx.compose.runtime.mutableIntStateOf
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
import to.eyed.inferno.data.KvCachePref
import to.eyed.inferno.engine.ContextManager
import to.eyed.inferno.engine.MemoryEstimate
import to.eyed.inferno.engine.modelOrNull
import to.eyed.inferno.models.ModelCatalog
import to.eyed.inferno.ui.S
import to.eyed.inferno.ui.components.CardDivider
import to.eyed.inferno.ui.components.ConnectedGroup
import to.eyed.inferno.ui.components.GlassToggle
import to.eyed.inferno.ui.components.InkSlider
import to.eyed.inferno.ui.components.PrimaryButton
import to.eyed.inferno.ui.components.SettingRow
import to.eyed.inferno.ui.components.SettingsCard
import to.eyed.inferno.ui.components.SheetHeader
import to.eyed.inferno.ui.components.UsageBar
import to.eyed.inferno.ui.theme.Ink
import to.eyed.inferno.ui.theme.Numeric
import to.eyed.inferno.ui.theme.Typography
import to.eyed.inferno.vm.AppViewModel

// Context-length page (spec 5.6 ModelSheet "context"): Auto toggle with the resolved label, a 1,024-step slider
// whose label is an instant analytic estimate (AppViewModel.previewEstimate, no native call), the KV type
// group and Apply. Hosted by ModelSheet and by the Settings screen.

private const val IMPORT_CAP = 32_768

@Composable
fun ContextPage(appVm: AppViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val settings by appVm.settings.collectAsStateWithLifecycle()
    val engine by appVm.engine.collectAsStateWithLifecycle()
    val plan by appVm.loadPlan.collectAsStateWithLifecycle()
    val models by appVm.models.collectAsStateWithLifecycle()

    val modelId = engine.modelOrNull?.id ?: settings.selectedModelId
    val entry = models.firstOrNull { it.id == modelId }
    val catalog = entry?.catalog ?: modelId?.let { ModelCatalog.byId(it) }

    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        SheetHeader(S.contextLength, Lucide.ArrowLeft, onLeading = onBack, leadingDescription = S.back)
        if (modelId == null || entry == null) {
            Text(S.noModelSelected, style = Typography.bodyMedium, color = Ink.I500, modifier = Modifier.padding(vertical = 24.dp).align(Alignment.CenterHorizontally))
            return@Column
        }

        val cap = remember(catalog) {
            ContextManager.roundDown(minOf(catalog?.contextMax ?: IMPORT_CAP, ContextManager.MAX_CTX), ContextManager.CTX_STEP)
                .coerceAtLeast(ContextManager.MIN_CTX)
        }
        val resolved = plan?.takeIf { engine.modelOrNull?.id == modelId }?.config?.nCtx
        val initial = settings.contextSize.takeIf { it > 0 } ?: resolved ?: minOf(catalog?.defaultCtx ?: 8192, cap)

        var auto by rememberSaveable(modelId) { mutableStateOf(settings.contextSize == 0) }
        var draft by rememberSaveable(modelId) { mutableIntStateOf(initial.coerceIn(ContextManager.MIN_CTX, cap)) }
        var kv by rememberSaveable(modelId) { mutableStateOf(settings.kvCache.name) }
        val kvPref = KvCachePref.valueOf(kv)

        // Shown tokens: the resolved plan while Auto (when this model is loaded), else the slider draft.
        val shown = if (auto) (resolved ?: draft) else draft
        val estimate = remember(modelId, shown, kvPref) { appVm.previewEstimate(modelId, shown, kvPref) }
        val budget = remember(modelId, engine) { appVm.budgetBytes() }

        SettingsCard {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(S.autoMax, style = to.eyed.inferno.ui.theme.RowTitle, color = Ink.White)
                    Spacer(Modifier.height(2.dp))
                    val label = plan?.takeIf { auto && resolved != null }?.resolvedLabel ?: S.autoContextDescription
                    Text(label, style = Typography.bodySmall, color = Ink.I500)
                }
                Spacer(Modifier.width(16.dp))
                GlassToggle(auto, { auto = it })
            }
            CardDivider()
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(ContextManager.tokens(shown), style = Typography.headlineMedium, color = if (auto) Ink.I300 else Ink.White)
                    Spacer(Modifier.width(8.dp))
                    Text(S.tokens, style = Typography.bodyMedium, color = Ink.I500, modifier = Modifier.padding(bottom = 6.dp))
                }
                Spacer(Modifier.height(2.dp))
                Text(breakdown(estimate), style = Numeric, color = Ink.I500)
                Spacer(Modifier.height(12.dp))
                val fraction = if (budget > 0) estimate.totalBytes.toFloat() / budget else 0f
                UsageBar(fraction, approximate = true, color = if (fraction > ContextManager.BUDGET_GATE) Ink.Danger else Ink.White)
                Spacer(Modifier.height(6.dp))
                Text("${(fraction * 100).toInt()} % ${S.memoryBudget} · ${ContextManager.gb(budget)} GB", style = Typography.bodySmall, color = Ink.I500)
                if (cap > ContextManager.MIN_CTX) {
                    Spacer(Modifier.height(8.dp))
                    val steps = (cap - ContextManager.MIN_CTX) / ContextManager.CTX_STEP - 1
                    InkSlider(
                        value = draft.toFloat(),
                        onValueChange = { draft = ContextManager.roundDown(it.toInt() + ContextManager.CTX_STEP / 2, ContextManager.CTX_STEP).coerceIn(ContextManager.MIN_CTX, cap) },
                        valueRange = ContextManager.MIN_CTX.toFloat()..cap.toFloat(),
                        steps = steps.coerceAtLeast(0),
                        enabled = !auto,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(ctxK(ContextManager.MIN_CTX), style = Typography.bodySmall, color = Ink.I500)
                        Text(ctxK(cap), style = Typography.bodySmall, color = Ink.I500)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        SettingsCard {
            SettingRow(S.kvCache, S.kvCacheDesc)
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
                ConnectedGroup(KV_LABELS, KvCachePref.entries.indexOf(kvPref), { kv = KvCachePref.entries[it].name })
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(S.contextNote, style = Typography.bodySmall, color = Ink.I500, modifier = Modifier.padding(horizontal = 8.dp))
        Spacer(Modifier.height(20.dp))

        val changed = (if (auto) 0 else draft) != settings.contextSize || kvPref != settings.kvCache
        PrimaryButton(S.apply, enabled = changed, onClick = { appVm.applyContext(if (auto) 0 else draft, kvPref); onBack() }, modifier = Modifier.fillMaxWidth())
    }
}

private val KV_LABELS = listOf("Auto", "F16", "Q8_0", "Q4_0")

/** "≈ 1.9 GB total · weights 1.2 · KV 0.4 · compute 0.3 · vision 0.5" */
private fun breakdown(e: MemoryEstimate): String {
    val parts = mutableListOf("${S.weights} ${ContextManager.gb(e.modelResidentBytes)}", "${S.kv} ${ContextManager.gb(e.kvBytes)}", "${S.compute} ${ContextManager.gb(e.computeBytes)}")
    if (e.visionBytes > 0) parts += "${S.visionMem} ${ContextManager.gb(e.visionBytes)}"
    return "≈ ${ContextManager.gb(e.totalBytes)} GB ${S.estimatedTotal} · ${parts.joinToString(" · ")}"
}
