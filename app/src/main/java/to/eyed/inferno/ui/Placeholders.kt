package to.eyed.inferno.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Cpu
import com.composables.icons.lucide.Gauge
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.SquarePen
import com.composables.icons.lucide.Wand
import com.composables.icons.lucide.X
import to.eyed.inferno.data.ChatMessage
import to.eyed.inferno.data.ChatRepository
import to.eyed.inferno.data.PerfPreset
import to.eyed.inferno.engine.EngineState
import to.eyed.inferno.engine.modelOrNull
import to.eyed.inferno.imagegen.ImageGenUiState
import to.eyed.inferno.imagegen.ImageSizePreset
import to.eyed.inferno.models.DownloadService
import to.eyed.inferno.models.DownloadState
import to.eyed.inferno.models.ImageModelCatalog
import to.eyed.inferno.models.ModelEntry
import to.eyed.inferno.ui.components.CardDivider
import to.eyed.inferno.ui.components.ChipPill
import to.eyed.inferno.ui.components.ErrorNotice
import to.eyed.inferno.ui.components.FormField
import to.eyed.inferno.ui.components.GhostIconButton
import to.eyed.inferno.ui.components.GlassButton
import to.eyed.inferno.ui.components.GlassToggle
import to.eyed.inferno.ui.components.OptionRow
import to.eyed.inferno.ui.components.OwlMark
import to.eyed.inferno.ui.components.PrimaryButton
import to.eyed.inferno.ui.components.QuietNotice
import to.eyed.inferno.ui.components.SectionHeader
import to.eyed.inferno.ui.components.SegmentedPill
import to.eyed.inferno.ui.components.SettingRow
import to.eyed.inferno.ui.components.SettingsCard
import to.eyed.inferno.ui.components.SheetHeader
import to.eyed.inferno.ui.components.UsageBar
import to.eyed.inferno.ui.components.surfaceCard
import to.eyed.inferno.ui.theme.Ink
import to.eyed.inferno.ui.theme.Meta
import to.eyed.inferno.ui.theme.Numeric
import to.eyed.inferno.ui.theme.Radii
import to.eyed.inferno.ui.theme.RowMeta
import to.eyed.inferno.ui.theme.RowTitle
import to.eyed.inferno.ui.theme.Typography
import to.eyed.inferno.ui.theme.rememberHaptics
import to.eyed.inferno.vm.AppViewModel
import to.eyed.inferno.vm.BenchViewModel
import to.eyed.inferno.vm.ChatViewModel
import to.eyed.inferno.vm.GenState
import to.eyed.inferno.vm.ImageGenViewModel
import to.eyed.inferno.vm.Screen
import to.eyed.inferno.vm.isBusy
import java.util.Locale

// TEMPORARY placeholder screens (WP5): bare compositions of WP6 components so the whole pipeline can be exercised
// on the phone before WP7 (chat), WP8 (models/settings/bench) and WP9b-ui (create/gallery) replace them. No
// visual polish is intended here; every screen is a plain scrolling column.

@Composable
fun UnsupportedCpuPlaceholder() {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        OwlMark(64.dp)
        Spacer(Modifier.height(24.dp))
        Text(S.unsupportedCpu, style = Typography.bodyLarge, color = Ink.I100)
    }
}

@Composable
fun FirstRunPlaceholder(appVm: AppViewModel, onBeforeDownload: () -> Unit) {
    val models by appVm.models.collectAsStateWithLifecycle()
    val recommended = models.firstOrNull { it.catalog?.recommended == true }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(40.dp))
        OwlMark(56.dp, Modifier.align(Alignment.CenterHorizontally))
        Text(S.appName, style = Typography.headlineMedium, color = Ink.White, modifier = Modifier.align(Alignment.CenterHorizontally))
        Text(S.firstRunTagline, style = Typography.bodyMedium, color = Ink.I300, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(12.dp))
        if (recommended != null && recommended.download !is DownloadState.Downloading) {
            PrimaryButton("${S.downloadAndStart} · ${DownloadService.fmt(recommended.catalog!!.totalBytes)}", onClick = { onBeforeDownload(); appVm.download(recommended.id) }, modifier = Modifier.fillMaxWidth())
        }
        SectionHeader(S.chooseDifferentModel)
        ModelRows(models, appVm, onBeforeDownload)
    }
}

// ---- Chat --------------------------------------------------------------------------------------------------

@Composable
fun ChatPlaceholder(appVm: AppViewModel, chatVm: ChatViewModel, onBeforeSend: () -> Unit) {
    val engine by appVm.engine.collectAsStateWithLifecycle()
    val messages by chatVm.messages.collectAsStateWithLifecycle()
    val gen by chatVm.gen.collectAsStateWithLifecycle()
    val pending by chatVm.pending.collectAsStateWithLifecycle()
    val usage by chatVm.contextUsage.collectAsStateWithLifecycle()
    val imageBusy by chatVm.imageBusy.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf("") }
    val haptics = rememberHaptics()
    val listState = rememberLazyListState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(2)) { uris -> uris.take(2).forEach { chatVm.attachFromUri(it) } }

    LaunchedEffect(messages.size, gen) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            GhostIconButton(Lucide.SquarePen, S.newChat, onClick = { chatVm.newChat() })
            ChipPill(chipText(engine), onClick = { appVm.navigate(Screen.MODELS) }, icon = Lucide.Cpu, modifier = Modifier.weight(1f))
            GhostIconButton(Lucide.Wand, "Create image", onClick = { appVm.navigate(Screen.CREATE) })
            GhostIconButton(Lucide.Gauge, S.benchmark, onClick = { appVm.navigate(Screen.BENCH) })
            GhostIconButton(Lucide.Settings, S.settings, onClick = { appVm.navigate(Screen.SETTINGS) })
        }
        if (usage.nCtx > 0) UsageBar(usage.fraction, Modifier.padding(horizontal = 16.dp), approximate = usage.approximate)

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (messages.isEmpty() && !gen.isBusy) item { Text(S.emptyTitle, style = Typography.titleLarge, color = Ink.I300, modifier = Modifier.padding(top = 80.dp).fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
            items(messages, key = { it.id }) { m -> MessageRow(m, streamingId = if (gen.isBusy) messages.lastOrNull()?.id else null, gen = gen) }
            item { GenStatusLine(gen, hasAssistantRow = messages.lastOrNull()?.role == ChatRepository.ROLE_ASSISTANT) }
        }

        (gen as? GenState.Error)?.let { ErrorNotice(it.message, onDismiss = { chatVm.cancel() }, modifier = Modifier.padding(horizontal = 12.dp)) }
        if (gen is GenState.Queued) QuietNotice(S.willSendWhenReady, Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        if (imageBusy) QuietNotice("Generating image…", Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        if (pending.isNotEmpty()) Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pending.forEachIndexed { i, a ->
                Box { AsyncImage(a.thumbPath, S.attachedImage, Modifier.size(56.dp).clip(RoundedCornerShape(Radii.control)))
                    GhostIconButton(Lucide.X, S.removeImage, onClick = { chatVm.removeAttachment(i) }, size = 20.dp, iconSize = 12.dp, modifier = Modifier.align(Alignment.TopEnd)) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val canAttach = engine.modelOrNull?.hasVision == true
            GhostIconButton(Lucide.Plus, S.attach, enabled = canAttach && pending.size < 2, onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
            FormField("", draft, { draft = it }, Modifier.weight(1f), placeholder = if (engine is EngineState.Idle) S.composerLoadModelToStart else S.composerPlaceholder, multiline = true)
            if (gen.isBusy && gen !is GenState.Queued) GlassButton(S.stop, onClick = { chatVm.cancel() })
            else GlassButton(S.send, enabled = draft.isNotBlank() && !imageBusy, onClick = { onBeforeSend(); haptics.gestureEnd(); chatVm.send(draft.trim()); draft = "" })
        }
    }
}

private fun chipText(s: EngineState): String = when (s) {
    is EngineState.Idle -> S.chooseModel
    is EngineState.Loading -> "${s.phase}${s.progress?.let { " ${(it * 100).toInt()} %" } ?: ""}"
    is EngineState.Ready -> s.loaded.model.displayName
    is EngineState.Generating -> s.loaded.model.displayName
    is EngineState.Suspended -> "${s.loaded.model.displayName} · ${S.suspended}"
    is EngineState.Error -> "${S.failed}: ${s.message}"
}

@Composable
private fun MessageRow(m: ChatMessage, streamingId: String?, gen: GenState) {
    val user = m.role == ChatRepository.ROLE_USER
    // While streaming, the last assistant row shows the live text (Room lags by up to 2 s).
    val live = if (m.id == streamingId && !user) (gen as? GenState.Streaming)?.text else null
    Column(Modifier.fillMaxWidth().padding(start = if (user) 48.dp else 0.dp, end = if (user) 0.dp else 24.dp)
        .surfaceCard(RoundedCornerShape(Radii.row), fill = if (user) Ink.SurfaceHigh else Ink.I850).padding(12.dp)) {
        if (m.images.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { m.images.forEach { AsyncImage(it.thumbPath, S.attachedImage, Modifier.size(96.dp).clip(RoundedCornerShape(Radii.control))) } }
        val thinking = (if (m.id == streamingId) (gen as? GenState.Streaming)?.reasoning ?: (gen as? GenState.Thinking)?.reasoning else null) ?: m.thinking
        if (!thinking.isNullOrBlank()) Text(thinking, style = RowMeta, color = Ink.I500, modifier = Modifier.padding(bottom = 6.dp))
        Text(live ?: m.content, style = Typography.bodyLarge, color = Ink.I100)
        m.stats?.let { s ->
            val meta = when {
                s.finishReason == null && m.id != streamingId -> "· ${S.interrupted}"
                s.finishReason == "CANCELLED" -> "· ${S.stopped}"
                s.generatedTokens > 0 -> String.format(Locale.US, "%d tok · %.1f tok/s · prefill %d ms%s · ctx %d/%d", s.generatedTokens, s.decodeTps, s.prefillMs,
                    if (s.imageEncodeMs > 0) " · image ${s.imageEncodeMs} ms" else "", s.kvUsedTokens, s.nCtx)
                else -> null
            }
            if (meta != null) Text(meta, style = Meta, color = Ink.I500, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun GenStatusLine(gen: GenState, hasAssistantRow: Boolean) {
    val text = when (gen) {
        is GenState.Prefill -> if (gen.encodingImage) "Reading image${if (gen.expectedMs > 0) " · ~${(gen.expectedMs + 999) / 1000} s" else ""}" else "Thinking about it${if (gen.total > 0) " · ${gen.done}/${gen.total}" else ""}"
        is GenState.Thinking -> "${S.thinking} ${gen.tokPerSec} tok/s"
        is GenState.Streaming -> if (hasAssistantRow) "${gen.tokens} tok · ${gen.tokPerSec} tok/s" else gen.text
        GenState.Trimming -> S.trimmingOlder
        else -> null
    } ?: return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(4.dp)) {
        LoadingIndicator(Modifier.size(16.dp), color = Ink.White)
        Text(text, style = RowMeta, color = Ink.I300)
    }
}

// ---- Models --------------------------------------------------------------------------------------------------

@Composable
fun ModelsPlaceholder(appVm: AppViewModel, onBeforeDownload: () -> Unit) {
    val models by appVm.models.collectAsStateWithLifecycle()
    val storage by appVm.storage.collectAsStateWithLifecycle()
    val engine by appVm.engine.collectAsStateWithLifecycle()
    val imageDownloads by appVm.imageDownloads.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SheetHeader(S.models, Lucide.ArrowLeft, onLeading = { appVm.back() }, leadingDescription = S.back)
        Text("${DownloadService.fmt(storage.modelsBytes)} used · ${DownloadService.fmt(storage.freeBytes)} free", style = RowMeta, color = Ink.I500)
        ChipPill(chipText(engine), onClick = { if (engine !is EngineState.Idle) appVm.unload() }, icon = Lucide.Cpu)
        SectionHeader(S.curatedForThisPhone)
        ModelRows(models, appVm, onBeforeDownload)
        SectionHeader("Image models")
        SettingsCard {
            ImageModelCatalog.models.forEachIndexed { i, m ->
                if (i > 0) CardDivider()
                val state = imageDownloads[m.id] ?: DownloadState.NotDownloaded
                SettingRow(m.displayName, "${m.tierLabel} · ${DownloadService.fmt(ImageModelCatalog.bytesOnDisk(m))} · ${stateText(state)}") {
                    DownloadActions(m.id, state, appVm, onBeforeDownload, loadable = false)
                }
            }
        }
    }
}

@Composable
private fun ModelRows(models: List<ModelEntry>, appVm: AppViewModel, onBeforeDownload: () -> Unit) {
    val engine by appVm.engine.collectAsStateWithLifecycle()
    SettingsCard {
        models.forEachIndexed { i, e ->
            if (i > 0) CardDivider()
            val cat = e.catalog
            val desc = listOfNotNull(cat?.tier?.label, cat?.let { DownloadService.fmt(it.totalBytes) }, cat?.license, stateText(e.download)).joinToString(" · ")
            SettingRow(e.displayName + if (cat?.recommended == true) " · ${S.recommended}" else "", desc) {
                DownloadActions(e.id, e.download, appVm, onBeforeDownload, loadable = true, loaded = engine.modelOrNull?.id == e.id)
            }
        }
    }
}

private fun stateText(s: DownloadState): String = when (s) {
    DownloadState.NotDownloaded -> "not downloaded"
    DownloadState.Queued -> "queued"
    is DownloadState.Downloading -> "${(s.fraction * 100).toInt()} % · ${DownloadService.fmt(s.bytesPerSec)}/s"
    is DownloadState.Paused -> "${S.paused} ${(s.fraction * 100).toInt()} %${s.reason?.let { " ($it)" } ?: ""}"
    DownloadState.Verifying -> "verifying"
    DownloadState.Downloaded -> "downloaded"
    is DownloadState.Failed -> "failed: ${s.message}"
}

@Composable
private fun DownloadActions(id: String, state: DownloadState, appVm: AppViewModel, onBeforeDownload: () -> Unit, loadable: Boolean, loaded: Boolean = false) {
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (state) {
            DownloadState.NotDownloaded, is DownloadState.Failed -> GlassButton(if (state is DownloadState.Failed) S.retry else S.download, onClick = { onBeforeDownload(); appVm.download(id) })
            DownloadState.Queued, is DownloadState.Downloading, DownloadState.Verifying -> GlassButton(S.cancel, onClick = { appVm.cancelDownload(id) })
            is DownloadState.Paused -> { GlassButton(S.resume, onClick = { onBeforeDownload(); appVm.download(id) }); GlassButton(S.discardDownload, textColor = Ink.Danger, onClick = { appVm.discardPartial(id) }) }
            DownloadState.Downloaded -> {
                if (loadable) GlassButton(if (loaded) "Loaded" else "Load", enabled = !loaded, onClick = { appVm.selectAndLoad(id); appVm.navigate(Screen.CHAT) })
                GlassButton(S.delete, textColor = Ink.Danger, onClick = { appVm.deleteModel(id) })
            }
        }
    }
}

// ---- Settings ------------------------------------------------------------------------------------------------

@Composable
fun SettingsPlaceholder(appVm: AppViewModel) {
    val s by appVm.settings.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SheetHeader(S.settings, Lucide.ArrowLeft, onLeading = { appVm.back() }, leadingDescription = S.back)
        SectionHeader(S.inference)
        SegmentedPill(PerfPreset.entries.map { it.label }, s.perfPreset.ordinal, { appVm.setPerfPreset(PerfPreset.entries[it]) })
        SettingsCard { Column(Modifier.padding(horizontal = 16.dp)) {
            GlassToggle("Keep model loaded in background", s.keepModelLoaded, appVm::setKeepModelLoaded)
            GlassToggle("Flash attention", s.flashAttention, appVm::setFlashAttention)
            GlassToggle("Use mmap", s.useMmap, appVm::setUseMmap)
        } }
        SectionHeader(S.generation)
        SettingsCard { Column(Modifier.padding(horizontal = 16.dp)) {
            GlassToggle("Thinking", s.thinking, appVm::setThinking)
            GlassToggle("Model sampling defaults", s.useModelSamplingDefaults, appVm::setUseModelDefaults)
            GlassToggle("Auto-trim context", s.autoTrim, appVm::setAutoTrim)
        } }
        FormField(S.systemPrompt, s.systemPrompt, appVm::setSystemPrompt, multiline = true, placeholder = "Optional")
        SectionHeader(S.chat)
        SettingsCard { Column(Modifier.padding(horizontal = 16.dp)) {
            GlassToggle(S.streamingAnimations, s.streamingAnimations, appVm::setStreamingAnimations)
            GlassToggle(S.haptics, s.haptics, appVm::setHaptics)
            GlassToggle("Allow metered downloads", s.allowMeteredDownloads, appVm::setAllowMeteredDownloads)
        } }
        Text("Selected: ${s.selectedModelId ?: "none"} · crash guard: ${s.loadAttemptModelId ?: "clear"}", style = RowMeta, color = Ink.I500)
    }
}

// ---- Bench ---------------------------------------------------------------------------------------------------

@Composable
fun BenchPlaceholder(benchVm: BenchViewModel, appVm: AppViewModel) {
    val running by benchVm.running.collectAsStateWithLifecycle()
    val progress by benchVm.progress.collectAsStateWithLifecycle()
    val rows by benchVm.rows.collectAsStateWithLifecycle()
    val notice by benchVm.notice.collectAsStateWithLifecycle()
    val thermal by benchVm.thermal.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SheetHeader(S.benchmark, Lucide.ArrowLeft, onLeading = { appVm.back() }, leadingDescription = S.back)
        notice?.let { ErrorNotice(it, onDismiss = benchVm::dismissNotice) }
        Text("Thermal status $thermal", style = RowMeta, color = Ink.I500)
        if (running) { UsageBar(progress); GlassButton(S.stop, onClick = benchVm::stop) }
        else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { GlassButton(S.runQuick, onClick = benchVm::runQuick); GlassButton(S.runThreadMatrix, onClick = benchVm::runMatrix) }
        SettingsCard {
            if (rows.isEmpty()) SettingRow(S.runBenchmarkHint)
            rows.forEachIndexed { i, r ->
                if (i > 0) CardDivider()
                SettingRow(r.label, "${r.threads} thr · ${if (r.pinned) "pinned" else "free"} · ${r.kv}") {
                    Text(String.format(Locale.US, "pp %.1f · tg %.1f", r.ppTps, r.tgTps), style = Numeric, color = Ink.I100)
                }
            }
        }
    }
}

// ---- Create / Gallery ----------------------------------------------------------------------------------------

@Composable
fun CreatePlaceholder(imageVm: ImageGenViewModel, appVm: AppViewModel, chatVm: ChatViewModel, onBeforeDownload: () -> Unit) {
    val state by imageVm.state.collectAsStateWithLifecycle()
    val prompt by imageVm.prompt.collectAsStateWithLifecycle()
    val modelId by imageVm.modelId.collectAsStateWithLifecycle()
    val size by imageVm.size.collectAsStateWithLifecycle()
    val steps by imageVm.steps.collectAsStateWithLifecycle()
    val downloads by imageVm.downloads.collectAsStateWithLifecycle()
    val notice by imageVm.notice.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SheetHeader("Create image", Lucide.ArrowLeft, onLeading = { appVm.back() }, leadingDescription = S.back,
            trailing = { GhostIconButton(Lucide.Image, "Gallery", onClick = { appVm.navigate(Screen.GALLERY) }) })
        notice?.let { ErrorNotice(it, onDismiss = imageVm::dismissNotice) }
        FormField("Prompt", prompt, imageVm::setPrompt, multiline = true, placeholder = "A lighthouse at dusk, oil painting")
        imageVm.catalog.forEach { m ->
            val dl = downloads[m.id] ?: DownloadState.NotDownloaded
            OptionRow("${m.displayName} · ${m.tierLabel}", selected = modelId == m.id, onClick = { imageVm.selectModel(m.id) },
                description = "${m.license} · ${DownloadService.fmt(ImageModelCatalog.bytesOnDisk(m))} · ${stateText(dl)}")
            if (dl !is DownloadState.Downloaded) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { DownloadActions(m.id, dl, appVm, onBeforeDownload, loadable = false) }
        }
        SegmentedPill(ImageSizePreset.entries.map { "${it.px}" }, size.ordinal, { imageVm.setSize(ImageSizePreset.entries[it]) })
        val model = imageVm.model
        if (model.stepsAdjustable) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val cur = model.clampSteps(steps ?: model.defaultSteps)
            GlassButton("-", onClick = { imageVm.setSteps(model.clampSteps(cur - 1)) }); Text("$cur steps", style = Numeric, color = Ink.I100); GlassButton("+", onClick = { imageVm.setSteps(model.clampSteps(cur + 1)) })
        }
        when (val st = state) {
            is ImageGenUiState.Loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { LoadingIndicator(Modifier.size(16.dp), color = Ink.White); Text("Loading image model…", style = RowMeta, color = Ink.I300); GlassButton(S.cancel, onClick = imageVm::cancel) }
            is ImageGenUiState.Generating -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                UsageBar(if (st.total > 0) st.step.toFloat() / st.total else 0f)
                Text(if (st.isDecoding) "Decoding · ~${st.etaSeconds} s" else "Step ${st.step}/${st.total} · ~${st.etaSeconds} s", style = RowMeta, color = Ink.I300)
                st.previewPng?.let { AsyncImage(it, "Preview", Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(Radii.card))) }
                GlassButton(S.stop, onClick = imageVm::cancel)
            }
            is ImageGenUiState.Done -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AsyncImage(st.record.path, "Generated image", Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(Radii.card)))
                Text("seed ${st.record.seed} · ${st.record.totalMs / 1000.0} s · ${st.record.width}x${st.record.height}", style = Meta, color = Ink.I500)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton("Save", onClick = { imageVm.saveToPhotos(st.record) })
                    GlassButton("Share", onClick = { imageVm.share(st.record) { uri -> context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null)) } })
                    GlassButton("Attach", onClick = { appVm.navigate(Screen.CHAT); chatVm.newChat(); chatVm.attachFromUri(android.net.Uri.fromFile(java.io.File(st.record.path))) })
                    GlassButton("Again", onClick = { imageVm.regenerate(st.record) })
                }
                PrimaryButton("Generate · ~${imageVm.estimateSeconds()} s", onClick = { imageVm.setSeed(-1); imageVm.generate() }, modifier = Modifier.fillMaxWidth())
            }
            is ImageGenUiState.Error -> { ErrorNotice(st.message, onDismiss = imageVm::reset); PrimaryButton("Generate · ~${imageVm.estimateSeconds()} s", onClick = imageVm::generate, modifier = Modifier.fillMaxWidth()) }
            else -> PrimaryButton("Generate · ~${imageVm.estimateSeconds()} s", onClick = imageVm::generate, modifier = Modifier.fillMaxWidth(), enabled = prompt.isNotBlank())
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryPlaceholder(imageVm: ImageGenViewModel, appVm: AppViewModel) {
    val gallery by imageVm.gallery.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        SheetHeader("Gallery", Lucide.ArrowLeft, onLeading = { appVm.back() }, leadingDescription = S.back)
        if (gallery.isEmpty()) Text("No images yet", style = Typography.bodyMedium, color = Ink.I500)
        LazyVerticalGrid(GridCells.Fixed(2), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(gallery, key = { it.id }) { r ->
                Column {
                    AsyncImage(r.thumbPath ?: r.path, r.prompt, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(Radii.row)).background(Ink.I850)
                        .combinedClickable(onClick = {}, onLongClick = { haptics.longPress(); imageVm.deleteImage(r.id) }))
                    Text(r.prompt, style = RowMeta, color = Ink.I500, maxLines = 2)
                }
            }
        }
    }
}
