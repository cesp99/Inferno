@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package to.eyed.inferno.ui.models

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.Lucide
import to.eyed.inferno.data.SettingsState
import to.eyed.inferno.data.devModelTechSpecs
import to.eyed.inferno.engine.ContextManager
import to.eyed.inferno.engine.EngineState
import to.eyed.inferno.engine.modelOrNull
import to.eyed.inferno.models.DownloadService
import to.eyed.inferno.models.DownloadState
import to.eyed.inferno.models.ImageCatalogModel
import to.eyed.inferno.models.ImageModelCatalog
import to.eyed.inferno.models.ModelEntry
import to.eyed.inferno.models.ModelTier
import to.eyed.inferno.models.StorageInfo
import to.eyed.inferno.ui.S
import to.eyed.inferno.ui.components.CardDivider
import to.eyed.inferno.ui.components.ConfirmTwice
import to.eyed.inferno.ui.components.GhostIconButton
import to.eyed.inferno.ui.components.GlassButton
import to.eyed.inferno.ui.components.MenuRow
import to.eyed.inferno.ui.components.NavCard
import to.eyed.inferno.ui.components.QuietNotice
import to.eyed.inferno.ui.components.SectionHeader
import to.eyed.inferno.ui.components.SettingsCard
import to.eyed.inferno.ui.components.WavyBar
import to.eyed.inferno.ui.theme.Ink
import to.eyed.inferno.ui.theme.Numeric
import to.eyed.inferno.ui.theme.Typography
import to.eyed.inferno.ui.theme.whiteA
import to.eyed.inferno.vm.AppViewModel
import to.eyed.inferno.vm.Screen

// Full-screen model manager (spec 5.6 ModelManagerScreen.kt): tiers as section headers, one grouped card per
// tier, downloads unfolding inside the rows, imports, image models, storage footer.

@Composable
fun ModelManagerScreen(appVm: AppViewModel, onBeforeDownload: () -> Unit, onBack: () -> Unit = { appVm.back() }) {
    val models by appVm.models.collectAsStateWithLifecycle()
    val storage by appVm.storage.collectAsStateWithLifecycle()
    val engine by appVm.engine.collectAsStateWithLifecycle()
    val settings by appVm.settings.collectAsStateWithLifecycle()
    val loadInProgress by appVm.loadInProgress.collectAsStateWithLifecycle()
    val imageDownloads by appVm.imageDownloads.collectAsStateWithLifecycle()
    val online by rememberIsOnline()
    val context = LocalContext.current
    val importModel = rememberImportFlow(appVm)
    val (confirm, requestConfirm) = rememberDownloadConfirm(models)

    val loadedId = engine.modelOrNull?.id
    val loadingId = (engine as? EngineState.Loading)?.model?.id ?: settings.selectedModelId.takeIf { loadInProgress && engine is EngineState.Idle }
    val downloadedCount = models.count { it.isDownloaded }
    // Blocked reasons: resident weights + compute + KV at MIN_CTX against the planner gate, recomputed per list change.
    val blocked = remember(models, settings.kvCache, engine) { blockedReasons(appVm, models, settings) }

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Column(Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection)) {
        LargeFlexibleTopAppBar(
            title = { Text(S.models, color = Ink.White) },
            subtitle = { Text("$downloadedCount ${S.downloaded.lowercase()} · ${DownloadService.fmt(storage.modelsBytes)} · ${DownloadService.fmt(storage.freeBytes)} free", style = Typography.bodyMedium, color = Ink.I500) },
            navigationIcon = { GhostIconButton(Lucide.ArrowLeft, S.back, onClick = onBack, size = 40.dp, iconSize = 20.dp, tint = Ink.I100) },
            windowInsets = WindowInsets(0),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink.Pitch, scrolledContainerColor = Ink.Pitch, titleContentColor = Ink.White, subtitleContentColor = Ink.I500),
            scrollBehavior = scroll,
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
            if (!online) item { QuietNotice(S.offlineNotice, Modifier.padding(bottom = 10.dp)) }
            if (settings.notificationsAsked && !appVm.notificationsEnabled) {
                item { QuietNotice(S.notificationsNotice, Modifier.padding(bottom = 10.dp), onClick = { openNotificationSettings(context) }) }
            }

            val byTier = models.filter { it.catalog != null }.groupBy { it.catalog!!.tier }
            for (tier in ModelTier.entries) {
                val rows = byTier[tier] ?: continue
                item(key = "tier-${tier.name}") { SectionHeader(tier.label) }
                item(key = "card-${tier.name}") {
                    SettingsCard {
                        rows.forEachIndexed { i, e ->
                            if (i > 0) CardDivider()
                            ManagerRow(e, appVm, loadedId, loadingId, blocked[e.id], settings, requestConfirm)
                        }
                    }
                }
            }

            val imports = models.filter { it.catalog == null }
            item(key = "imports-header") { SectionHeader(S.importedModels) }
            item(key = "imports") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (imports.isNotEmpty()) SettingsCard {
                        imports.forEachIndexed { i, e ->
                            if (i > 0) CardDivider()
                            val addProjector = rememberProjectorPicker(appVm, e.id)
                            ManagerRow(e, appVm, loadedId, loadingId, blocked[e.id], settings, requestConfirm,
                                menuExtra = if (e.local?.hasVision == false) { close -> MenuRow(S.importProjector, icon = Lucide.Eye, onClick = { close(); addProjector() }) } else null)
                        }
                    }
                    NavCard(Lucide.FolderOpen, S.importGguf, S.importCardHint, onClick = importModel)
                }
            }

            item(key = "images-header") { SectionHeader(S.imageModels) }
            item(key = "images") {
                SettingsCard {
                    ImageModelCatalog.models.forEachIndexed { i, m ->
                        if (i > 0) CardDivider()
                        ImageModelRow(m, imageDownloads[m.id] ?: DownloadState.NotDownloaded, appVm, onBeforeDownload)
                    }
                }
            }

            item(key = "storage") { StorageFooter(storage) }
        }
    }

    confirm?.let { entry ->
        DownloadConfirmSheet(entry, onDismiss = { requestConfirm(null) }, onDownload = { onBeforeDownload(); appVm.download(entry.id) })
    }
}

@Composable
private fun ManagerRow(
    e: ModelEntry, appVm: AppViewModel, loadedId: String?, loadingId: String?, blockedReason: String?, settings: SettingsState,
    requestConfirm: (ModelEntry?) -> Unit, menuExtra: (@Composable (close: () -> Unit) -> Unit)? = null,
) {
    ModelCard(
        entry = e,
        selected = e.id == loadedId && loadingId != e.id,
        loading = e.id == loadingId,
        blockedReason = blockedReason,
        calibration = settings.calibration[e.id],
        menuExtra = menuExtra,
        techSpecs = settings.devModelTechSpecs,
        actions = ModelActions(
            onLoad = { appVm.selectAndLoad(e.id); appVm.navigate(Screen.CHAT) },
            onDownload = { if (e.download is DownloadState.Failed) appVm.download(e.id) else requestConfirm(e) },
            onCancel = { appVm.cancelDownload(e.id) },
            onResume = { appVm.download(e.id) },
            onDiscard = { appVm.discardPartial(e.id) },
            onDelete = { appVm.deleteModel(e.id) },
        ),
    )
}

/** Image models share the download machinery but are picked on the Create screen, so the row has no Load. */
@Composable
private fun ImageModelRow(m: ImageCatalogModel, state: DownloadState, appVm: AppViewModel, onBeforeDownload: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(m.displayName, style = Typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = Ink.White)
                Spacer(Modifier.height(3.dp))
                Text("${m.tierLabel} · ${DownloadService.fmt(ImageModelCatalog.bytesOnDisk(m))} · ${S.usedForCreate} · ${m.license}", style = Typography.bodyMedium, color = Ink.I500)
            }
            Spacer(Modifier.width(12.dp))
            when (state) {
                DownloadState.NotDownloaded -> GlassButton(S.download, onClick = { onBeforeDownload(); appVm.download(m.id) })
                is DownloadState.Failed -> GlassButton(S.retry, onClick = { onBeforeDownload(); appVm.download(m.id) })
                DownloadState.Queued, is DownloadState.Downloading, DownloadState.Verifying -> GlassButton(S.cancel, textColor = Ink.Danger, onClick = { appVm.cancelDownload(m.id) })
                is DownloadState.Paused -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    GlassButton(S.resume, onClick = { onBeforeDownload(); appVm.download(m.id) })
                }
                DownloadState.Downloaded -> ConfirmTwice(S.delete, onConfirm = { appVm.deleteModel(m.id) })
            }
        }
        when (state) {
            is DownloadState.Downloading -> { Spacer(Modifier.height(10.dp)); WavyBar(state.fraction); Spacer(Modifier.height(6.dp))
                Text("${S.downloadOf(DownloadService.fmt(state.downloadedBytes), DownloadService.fmt(state.totalBytes))} · ${S.perSecond(DownloadService.fmt(state.bytesPerSec))}", style = Numeric, color = Ink.I300) }
            is DownloadState.Paused -> { Spacer(Modifier.height(10.dp)); WavyBar(state.fraction, frozen = true, color = Ink.I300); Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${S.downloadOf(DownloadService.fmt(state.downloadedBytes), DownloadService.fmt(state.totalBytes))} · ${state.reason?.lowercase() ?: S.paused}", style = Numeric, color = Ink.I300, modifier = Modifier.weight(1f))
                    GlassButton(S.discardDownload, textColor = Ink.Danger, onClick = { appVm.discardPartial(m.id) })
                } }
            DownloadState.Verifying -> { Spacer(Modifier.height(10.dp)); WavyBar(0f, indeterminate = true) }
            is DownloadState.Failed -> { Spacer(Modifier.height(6.dp)); Text(state.message, style = Typography.bodySmall, color = Ink.Danger) }
            else -> Unit
        }
    }
}

/** Device storage: grey = everything used, white = the models' share; numbers underneath. */
@Composable
private fun StorageFooter(s: StorageInfo) {
    val used = (s.totalBytes - s.freeBytes).coerceAtLeast(0)
    val usedFrac = if (s.totalBytes > 0) used.toFloat() / s.totalBytes else 0f
    val modelsFrac = if (s.totalBytes > 0) (s.modelsBytes.toFloat() / s.totalBytes).coerceAtMost(usedFrac) else 0f
    Column(Modifier.fillMaxWidth().padding(top = 28.dp, start = 8.dp, end = 8.dp)) {
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(whiteA(0.10f))) {
            Box(Modifier.fillMaxWidth(usedFrac).fillMaxHeight().clip(CircleShape).background(Ink.I500))
            Box(Modifier.fillMaxWidth(modelsFrac).fillMaxHeight().clip(CircleShape).background(Ink.White))
        }
        Spacer(Modifier.height(8.dp))
        val partial = if (s.partialBytes > 0) DownloadService.fmt(s.partialBytes) else null
        Text(S.storageLine(DownloadService.fmt(s.modelsBytes), partial, DownloadService.fmt(s.freeBytes), DownloadService.fmt(s.totalBytes)), style = Numeric, color = Ink.I500)
        Spacer(Modifier.height(6.dp))
        Text(S.q4Footer, style = Typography.bodySmall, color = Ink.I500)
        Spacer(Modifier.height(2.dp))
        Text(S.storageFooter, style = Typography.bodySmall, color = Ink.I500)
    }
}

/** "Needs ~3.4 GB free, 2.9 GB available" for rows whose minimum-context footprint exceeds the planner gate. */
private fun blockedReasons(appVm: AppViewModel, models: List<ModelEntry>, settings: SettingsState): Map<String, String> {
    val budget = appVm.budgetBytes()
    val gate = (ContextManager.BUDGET_GATE * budget).toLong()
    return models.mapNotNull { e ->
        val est = appVm.previewEstimate(e.id, ContextManager.MIN_CTX, settings.kvCache)
        val need = est.modelResidentBytes + est.computeBytes + est.kvBytes
        if (need > gate && need > 0) e.id to S.needsMemory(ContextManager.gb(need), ContextManager.gb(gate)) else null
    }.toMap()
}
