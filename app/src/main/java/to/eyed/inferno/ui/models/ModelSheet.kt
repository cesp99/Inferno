package to.eyed.inferno.ui.models

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Gauge
import com.composables.icons.lucide.HardDrive
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.SlidersHorizontal
import com.composables.icons.lucide.X
import to.eyed.inferno.data.devModelTechSpecs
import to.eyed.inferno.data.powerUser
import to.eyed.inferno.engine.EngineState
import to.eyed.inferno.engine.modelOrNull
import to.eyed.inferno.ui.S
import to.eyed.inferno.ui.components.CardDivider
import to.eyed.inferno.ui.components.GlassButton
import to.eyed.inferno.ui.components.InfernoSheet
import to.eyed.inferno.ui.components.NavCard
import to.eyed.inferno.ui.components.SectionHeader
import to.eyed.inferno.ui.components.SettingsCard
import to.eyed.inferno.ui.components.SheetHeader
import to.eyed.inferno.ui.components.SheetPager
import to.eyed.inferno.ui.components.rememberSheetPage
import to.eyed.inferno.ui.settings.SamplingPage
import to.eyed.inferno.ui.theme.Ink
import to.eyed.inferno.ui.theme.Typography
import to.eyed.inferno.vm.AppViewModel
import java.util.Locale

// Bottom sheet from the chat model chip (spec 5.6 ModelSheet.kt): downloaded models with the loaded one checked,
// "Manage models", then - for a power user - the Context length and Generation pages. The page name survives rotation.

@Composable
fun ModelSheet(appVm: AppViewModel, onDismiss: () -> Unit, onOpenManager: () -> Unit) {
    var page by rememberSheetPage()
    InfernoSheet(onDismiss = onDismiss, wide = true) {
        // Inside the sheet's own window: registered here it runs before the sheet's dismiss-on-back.
        BackHandler(enabled = page != "main") { page = "main" }
        SheetPager(page, Modifier.fillMaxWidth()) { current ->
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                when (current) {
                    "context" -> ContextPage(appVm, onBack = { page = "main" })
                    "sampling" -> SamplingPage(appVm, onBack = { page = "main" })
                    else -> MainPage(appVm, onDismiss, onOpenManager, onPage = { page = it })
                }
            }
        }
    }
}

@Composable
private fun MainPage(appVm: AppViewModel, onDismiss: () -> Unit, onOpenManager: () -> Unit, onPage: (String) -> Unit) {
    val models by appVm.models.collectAsStateWithLifecycle()
    val engine by appVm.engine.collectAsStateWithLifecycle()
    val settings by appVm.settings.collectAsStateWithLifecycle()
    val plan by appVm.loadPlan.collectAsStateWithLifecycle()
    val loadInProgress by appVm.loadInProgress.collectAsStateWithLifecycle()
    val downloaded = models.filter { it.isDownloaded }
    val loadedId = engine.modelOrNull?.id
    val loadingId = (engine as? EngineState.Loading)?.model?.id ?: settings.selectedModelId.takeIf { loadInProgress && engine is EngineState.Idle }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
        SheetHeader(S.model, Lucide.X, onLeading = onDismiss)

        (engine as? EngineState.Error)?.let { err ->
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(err.message, style = Typography.bodyMedium, color = Ink.Danger, modifier = Modifier.weight(1f))
                if (err.model != null) {
                    Spacer(Modifier.width(12.dp))
                    GlassButton(S.retry, onClick = { appVm.selectAndLoad(err.model.id) })
                }
            }
        }

        if (downloaded.isEmpty()) {
            NavCard(Lucide.Download, S.getModels, S.nothingDownloadedYet, onClick = { onDismiss(); onOpenManager() })
        } else {
            SectionHeader(S.downloadedModels, Modifier.padding(top = 0.dp))
            SettingsCard {
                downloaded.forEachIndexed { i, e ->
                    if (i > 0) CardDivider()
                    ModelCard(
                        entry = e,
                        selected = e.id == loadedId && engine !is EngineState.Loading,
                        loading = e.id == loadingId,
                        calibration = settings.calibration[e.id],
                        showMenu = false,
                        techSpecs = settings.devModelTechSpecs,
                        actions = ModelActions(onLoad = { appVm.selectAndLoad(e.id); onDismiss() }, onDelete = { appVm.deleteModel(e.id) }),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            NavCard(Lucide.HardDrive, S.manageModels, S.curatedForThisPhone, onClick = { onDismiss(); onOpenManager() })
        }

        if (settings.powerUser) {
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val ctxValue = plan?.takeIf { loadedId != null }?.resolvedLabel
                    ?: if (settings.contextSize == 0) S.autoMax else "${String.format(Locale.US, "%,d", settings.contextSize)} ${S.tokens}"
                NavCard(Lucide.Gauge, S.contextLength, ctxValue, onClick = { onPage("context") })
                val p = appVm.effectiveParams()
                NavCard(Lucide.SlidersHorizontal, S.generation, "temp ${trim(p.temperature)} · top-p ${trim(p.topP)} · top-k ${p.topK}", onClick = { onPage("sampling") })
            }
        }
    }
}

private fun trim(v: Float): String = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
