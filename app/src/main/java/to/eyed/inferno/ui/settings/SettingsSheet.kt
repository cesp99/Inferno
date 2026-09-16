@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package to.eyed.inferno.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import to.eyed.inferno.data.PerfPreset
import to.eyed.inferno.data.SettingsState
import to.eyed.inferno.data.devGenerationStats
import to.eyed.inferno.data.devThermalInfo
import to.eyed.inferno.engine.EngineState
import to.eyed.inferno.engine.LoadedModel
import to.eyed.inferno.engine.modelOrNull
import to.eyed.inferno.models.DownloadService
import to.eyed.inferno.models.ImageDetail
import to.eyed.inferno.ui.S
import to.eyed.inferno.ui.components.CardDivider
import to.eyed.inferno.ui.components.ConnectedGroup
import to.eyed.inferno.ui.components.ControlRow
import to.eyed.inferno.ui.components.GhostIconButton
import to.eyed.inferno.ui.components.GlassButton
import to.eyed.inferno.ui.components.InfernoSheet
import to.eyed.inferno.ui.components.NavRow
import to.eyed.inferno.ui.components.SectionHeader
import to.eyed.inferno.ui.components.SettingRow
import to.eyed.inferno.ui.components.SettingsCard
import to.eyed.inferno.ui.components.SheetHeader
import to.eyed.inferno.ui.components.ToggleRow
import to.eyed.inferno.ui.models.ContextPage
import to.eyed.inferno.ui.theme.Ink
import to.eyed.inferno.ui.theme.Typography
import to.eyed.inferno.vm.AppViewModel
import to.eyed.inferno.vm.ChatViewModel
import to.eyed.inferno.vm.Screen
import java.util.Locale

// Settings (spec 5.6 SettingsSheet.kt). The body is one composable; SettingsScreen wraps it in a
// LargeFlexibleTopAppBar for the SETTINGS screen, SettingsSheet in an InfernoSheet for the sidebar footer.
// Sub-pages open as sheets whose name lives in rememberSaveable.

@Composable
fun SettingsScreen(appVm: AppViewModel, chatVm: ChatViewModel, onBack: () -> Unit = { appVm.back() }) {
    val engine by appVm.engine.collectAsStateWithLifecycle()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Column(Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection)) {
        LargeFlexibleTopAppBar(
            title = { Text(S.settings, color = Ink.White) },
            subtitle = { Text(engine.modelOrNull?.displayName ?: S.noModelLoaded, style = Typography.bodyMedium, color = Ink.I500) },
            navigationIcon = { GhostIconButton(Lucide.ArrowLeft, S.back, onClick = onBack, size = 40.dp, iconSize = 20.dp, tint = Ink.I100) },
            windowInsets = WindowInsets(0),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink.Pitch, scrolledContainerColor = Ink.Pitch, titleContentColor = Ink.White, subtitleContentColor = Ink.I500),
            scrollBehavior = scroll,
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            SettingsBody(appVm, chatVm, onOpenBench = { appVm.navigate(Screen.BENCH) }, onOpenModels = { appVm.navigate(Screen.MODELS) })
        }
    }
}

/** Sheet variant for the sidebar footer (WP7). */
@Composable
fun SettingsSheet(appVm: AppViewModel, chatVm: ChatViewModel, onDismiss: () -> Unit, onOpenBench: () -> Unit) {
    InfernoSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
            SheetHeader(S.settings, Lucide.X, onLeading = onDismiss)
            SettingsBody(appVm, chatVm, onOpenBench = { onDismiss(); onOpenBench() }, onOpenModels = { onDismiss(); appVm.navigate(Screen.MODELS) })
        }
    }
}

@Composable
private fun SettingsBody(appVm: AppViewModel, chatVm: ChatViewModel, onOpenBench: () -> Unit, onOpenModels: () -> Unit) {
    val s by appVm.settings.collectAsStateWithLifecycle()
    val engine by appVm.engine.collectAsStateWithLifecycle()
    val plan by appVm.loadPlan.collectAsStateWithLifecycle()
    val models by appVm.models.collectAsStateWithLifecycle()
    val storage by appVm.storage.collectAsStateWithLifecycle()
    val loaded = (engine as? EngineState.Ready)?.loaded ?: (engine as? EngineState.Generating)?.loaded
    val catalog = engine.modelOrNull?.catalog
    var sheet by rememberSaveable { mutableStateOf("") }

    // ---- Performance: the one top-level engine decision --------------------------------------------------------
    SectionHeader(S.performance, Modifier.padding(top = 0.dp))
    SettingsCard {
        val custom = s.threads != s.perfPreset.threads || s.pinBigCores != s.perfPreset.pin || s.poll != s.perfPreset.poll
        ControlRow(if (custom) "${S.preset} · ${S.custom}" else S.preset, description = if (s.devGenerationStats) perfLine(s, loaded, catalog?.estTgTps) else null) {
            ConnectedGroup(PerfPreset.entries.map { it.label }, if (custom) -1 else s.perfPreset.ordinal, { appVm.setPerfPreset(PerfPreset.entries[it]) })
        }
        CardDivider()
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) { Text(S.presetDescription, style = Typography.bodySmall, color = Ink.I500) }
    }

    // ---- Inference ---------------------------------------------------------------------------------------------
    SectionHeader(S.inference)
    SettingsCard {
        val ctxValue = plan?.takeIf { loaded != null }?.resolvedLabel?.substringBeforeLast(" · ") ?: if (s.contextSize == 0) S.presetAuto else "${String.format(Locale.US, "%,d", s.contextSize)} ${S.tokens}"
        NavRow(S.contextLength, ctxValue, onClick = { sheet = "context" })
        CardDivider()
        ControlRow(S.imageDetail, description = imageDetailLine(s, loaded, catalog?.encodeMsAt448, s.devGenerationStats)) {
            ConnectedGroup(listOf(S.fast, S.balanced, S.highDetail), s.imageDetail.ordinal, { appVm.setImageDetail(ImageDetail.entries[it]) })
        }
        CardDivider()
        ToggleRow(S.keepModelLoaded, s.keepModelLoaded, appVm::setKeepModelLoaded, description = S.keepModelLoadedDesc)
        if (s.devThermalInfo) {
            CardDivider()
            val cpu = appVm.cpu
            val features = listOfNotNull("dotprod".takeIf { cpu.hasDotprod }, "fp16".takeIf { cpu.hasFp16 }, "i8mm".takeIf { cpu.hasI8mm })
            SettingRow(S.computeRow, "CPU · ${cpu.socName} · ${cpu.nBig} big of ${cpu.nCores} cores · ${features.joinToString(", ")} · ${appVm.computeLine()}")
        }
    }

    // ---- Generation --------------------------------------------------------------------------------------------
    SectionHeader(S.generation)
    SettingsCard {
        val p = appVm.effectiveParams()
        NavRow(S.sampling, "temp ${trim(p.temperature)} · top-p ${trim(p.topP)}", onClick = { sheet = "sampling" },
            description = if (s.useModelSamplingDefaults) S.useModelDefaults else null)
        CardDivider()
        val thinkingAvailable = catalog?.thinking?.hasTags == true
        ToggleRow(S.thinkingToggle, s.thinking, appVm::setThinking, description = if (thinkingAvailable || loaded == null) S.thinkingDesc else S.thinkingUnavailable, enabled = thinkingAvailable || loaded == null)
    }

    // ---- Chat --------------------------------------------------------------------------------------------------
    SectionHeader(S.chat)
    SettingsCard {
        NavRow(S.systemPrompt, s.systemPrompt.trim().ifBlank { S.noSystemPrompt }, onClick = { sheet = "prompt" })
        CardDivider()
        ToggleRow(S.autoTrim, s.autoTrim, appVm::setAutoTrim, description = S.autoTrimDesc)
        CardDivider()
        ToggleRow(S.streamingAnimations, s.streamingAnimations, appVm::setStreamingAnimations, description = S.streamingAnimationsDesc)
        CardDivider()
        ToggleRow(S.haptics, s.haptics, appVm::setHaptics, description = S.hapticsDesc)
    }

    // ---- Storage -----------------------------------------------------------------------------------------------
    SectionHeader(S.storage)
    SettingsCard {
        val n = models.count { it.isDownloaded }
        SettingRow(S.modelsStorage, S.modelsStorageLine(n, DownloadService.fmt(storage.modelsBytes), storage.partialBytes.takeIf { it > 0 }?.let { DownloadService.fmt(it) })) {
            GlassButton(S.manage, onClick = onOpenModels)
        }
        CardDivider()
        ToggleRow(S.allowMetered, s.allowMeteredDownloads, appVm::setAllowMeteredDownloads, description = S.allowMeteredDesc)
        CardDivider()
        SettingRow(S.clearImageCache, S.clearImageCacheDesc) { GlassButton(S.clear, onClick = appVm::clearImageCache) }
    }

    AdvancedSections(appVm, chatVm, s, onOpenBench = onOpenBench, onOpenLicences = { sheet = "licences" }, developerRow = { DeveloperNavRow(appVm) })

    when (sheet) {
        "context" -> InfernoSheet(onDismiss = { sheet = "" }, wide = true) {
            Column(Modifier.verticalScroll(rememberScrollState())) { ContextPage(appVm, onBack = { sheet = "" }) }
        }
        "sampling" -> InfernoSheet(onDismiss = { sheet = "" }, wide = true) {
            Column(Modifier.verticalScroll(rememberScrollState())) { SamplingPage(appVm, onBack = { sheet = "" }) }
        }
        "prompt" -> SystemPromptSheet(s.systemPrompt, onSave = appVm::setSystemPrompt, onDismiss = { sheet = "" })
        "licences" -> LicensesSheet(onDismiss = { sheet = "" })
    }
    Spacer(Modifier.height(8.dp))
}

/** "18.4 tok/s with 4 pinned cores" from the calibration, else the catalog estimate, else the preset blurb. */
private fun perfLine(s: SettingsState, loaded: LoadedModel?, est: String?): String {
    val cal = loaded?.calibration ?: loaded?.let { s.calibration[it.model.id] }
    return when {
        cal != null && cal.tgTps > 0 -> S.measuredLine(String.format(Locale.US, "%.1f", cal.tgTps), s.threads, s.pinBigCores)
        est != null -> S.estimatedLine(est)
        else -> "${s.threads} threads · poll ${s.poll}"
    }
}

/**
 * "≈ 512 image tokens, about 10 s per image": measured encode when present, else scaled from the catalog's 448 px
 * figure. Without developer mode only the plain "About 10 s per image" part remains (null when nothing is known).
 */
private fun imageDetailLine(s: SettingsState, loaded: LoadedModel?, encodeMsAt448: Int?, tech: Boolean): String? {
    val d = s.imageDetail
    val measured = loaded?.let { s.calibration[it.model.id]?.imageEncode?.get(d.name)?.ms }
    val ms = measured ?: encodeMsAt448?.takeIf { it > 0 && d != ImageDetail.HIGH }?.let { base ->
        val scale = (d.maxEdgePx.toFloat() / 448f).let { it * it }
        (base * scale).toLong()
    }
    val seconds = ms?.let { if (it >= 1000) "${(it + 500) / 1000} s" else "$it ms" }
    return if (tech) S.imageDetailDesc(d.maxTokens, seconds) else seconds?.let { S.aboutPerImage(it) }
}

private fun trim(v: Float): String = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
