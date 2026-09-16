package to.eyed.inferno.ui.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import to.eyed.inferno.BuildConfig
import to.eyed.inferno.data.DevFlag
import to.eyed.inferno.data.SettingsState
import to.eyed.inferno.engine.EngineState
import to.eyed.inferno.engine.loadedAny
import to.eyed.inferno.engine.LoadedModel
import to.eyed.inferno.ui.S
import to.eyed.inferno.ui.components.CardDivider
import to.eyed.inferno.ui.components.ConnectedGroup
import to.eyed.inferno.ui.components.ControlRow
import to.eyed.inferno.ui.components.InfernoSheet
import to.eyed.inferno.ui.components.NavRow
import to.eyed.inferno.ui.components.SectionHeader
import to.eyed.inferno.ui.components.SettingRow
import to.eyed.inferno.ui.components.SettingsCard
import to.eyed.inferno.ui.components.SheetHeader
import to.eyed.inferno.ui.components.ToggleRow
import to.eyed.inferno.ui.theme.Ink
import to.eyed.inferno.ui.theme.MonoBody
import to.eyed.inferno.ui.theme.Numeric
import to.eyed.inferno.ui.theme.Typography
import to.eyed.inferno.ui.theme.layoutSpec
import to.eyed.inferno.vm.AppViewModel
import java.util.Locale

// Settings > Developer. The master switch is off by default so a normal user never meets tok/s, rings or
// timings; the sub-toggles pick which developer surfaces come back once it is on. The read-only Engine card
// at the bottom is the one place that names build tags, buffer types, threads and thermal state together.

/** Verbose / Info / Warn -> android.util.Log priorities that InferenceEngine.minLogPriority understands. */
private val LOG_LEVELS = listOf(2 to S.logVerbose, 4 to S.logInfo, 5 to S.logWarn)

/** The Advanced-card row that opens the page as a wide sheet; self-contained so SettingsSheet needs one line. */
@Composable
fun DeveloperNavRow(appVm: AppViewModel) {
    val s by appVm.settings.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }
    NavRow(S.developer, if (s.developerMode) S.on else S.off, onClick = { open = true }, description = S.developerRowDesc)
    if (open) InfernoSheet(onDismiss = { open = false }, wide = true) {
        Column(Modifier.verticalScroll(rememberScrollState())) { DeveloperPage(appVm, onBack = { open = false }) }
    }
}

@Composable
fun DeveloperPage(appVm: AppViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val s by appVm.settings.collectAsStateWithLifecycle()
    val engine by appVm.engine.collectAsStateWithLifecycle()
    val thermal by appVm.thermalStatus.collectAsStateWithLifecycle()
    val sustained by appVm.sustainedMode.collectAsStateWithLifecycle()
    val plan by appVm.loadPlan.collectAsStateWithLifecycle()
    val on = s.developerMode

    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        SheetHeader(S.developer, Lucide.ArrowLeft, onLeading = onBack, leadingDescription = S.back)
        SettingsCard {
            ToggleRow(S.developerMode, on, appVm::setDeveloperMode, description = S.developerModeDesc)
        }

        // Sub-toggles stay visible but dimmed while the master is off, so it is clear what the switch brings back.
        SectionHeader(S.devShowSection)
        SettingsCard {
            DevFlag.entries.forEachIndexed { i, flag ->
                if (i > 0) CardDivider()
                val (title, desc) = flagLabels(flag)
                ToggleRow(title, flag.get(s), { appVm.setDevFlag(flag, it) }, description = desc, enabled = on)
            }
        }

        SectionHeader(S.engineLogSection)
        SettingsCard {
            ControlRow(S.engineLogLevel, description = S.engineLogLevelDesc, enabled = on) {
                ConnectedGroup(LOG_LEVELS.map { it.second }, LOG_LEVELS.indexOfFirst { it.first == s.minLogPriority }, { appVm.setMinLogPriority(LOG_LEVELS[it].first) }, enabled = on)
            }
        }

        SectionHeader(S.engineSection)
        SettingsCard {
            val loaded = engine.loadedAny
            // engine.systemInfo() binds the JNI lazily (System.loadLibrary + the ggml CPU probe): never on the main thread.
            val systemInfo by produceState<String?>(null) { value = withContext(Dispatchers.IO) { appVm.systemInfo() } }
            val liveThreads by appVm.activeThreadsFlow.collectAsStateWithLifecycle()
            engineRows(appVm, s, loaded, thermal, sustained, plan?.resolvedLabel, systemInfo, liveThreads).forEachIndexed { i, (label, value) ->
                if (i > 0) CardDivider()
                SettingRow(label) { Text(value, style = Numeric, color = Ink.I100, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth(0.62f)) }
            }
            CardDivider()
            SystemInfoRow(systemInfo)
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun flagLabels(flag: DevFlag): Pair<String, String> = when (flag) {
    DevFlag.GENERATION_STATS -> S.devGenerationStats to S.devGenerationStatsDesc
    DevFlag.CONTEXT_METER -> S.devContextMeter to S.devContextMeterDesc
    DevFlag.TURN_DETAILS -> S.devTurnDetails to S.devTurnDetailsDesc
    DevFlag.BENCHMARK -> S.devBenchmark to S.devBenchmarkDesc
    DevFlag.MODEL_TECH_SPECS -> S.devModelTechSpecs to S.devModelTechSpecsDesc
    DevFlag.THERMAL_INFO -> S.devThermalInfo to S.devThermalInfoDesc
    DevFlag.TOKEN_COUNTER -> S.devTokenCounter to S.devTokenCounterDesc
}

/** Label -> value rows of the Engine card; model-bound rows show "—" while nothing is loaded. */
private fun engineRows(appVm: AppViewModel, s: SettingsState, loaded: LoadedModel?, thermal: Int, sustained: Boolean, ctxLabel: String?, systemInfo: String?, liveThreads: Int): List<Pair<String, String>> {
    val cpu = appVm.cpu
    val out = mutableListOf<Pair<String, String>>()
    out += S.llamaCppRow to "${BuildConfig.LLAMA_TAG} · ${BuildConfig.LLAMA_COMMIT.take(7)}"
    out += S.sdCppRow to BuildConfig.SD_COMMIT.take(7)
    out += S.cpuRow to "${cpu.socName.ifBlank { "CPU" }} · ${cpu.nBig} big of ${cpu.nCores}"
    out += S.cpuFeaturesRow to (systemInfo?.let(::cpuFeatures) ?: S.loading)
    val live = liveThreads
    out += S.threadsRow to if (live > 0) "$live · ${s.threads} ${S.requested} · ${if (s.pinBigCores) "pinned" else "free"}" else "${s.threads} ${S.requested} · ${if (s.pinBigCores) "pinned" else "free"}"
    out += S.thermalRow to AppViewModel.thermalName(thermal)
    out += S.sustainedRow to if (sustained) S.on else S.off
    out += S.modelBuffersRow to (appVm.modelBufferTypes().takeIf { loaded != null && it.isNotBlank() } ?: S.notAvailable)
    out += S.loadTimeRow to (loaded?.loadMs?.takeIf { it > 0 }?.let { S.duration(it) } ?: S.notAvailable)
    val cal = loaded?.calibration ?: loaded?.let { s.calibration[it.model.id] }
    out += S.calibrationRow to (cal?.takeIf { it.tgTps > 0 }?.let { "pp ${fmt1(it.ppTps)} · tg ${fmt1(it.tgTps)} ${S.tokPerSec}" } ?: S.notAvailable)
    out += S.contextRow to (loaded?.let { l ->
        val c = l.context
        listOfNotNull(ctxLabel?.substringBeforeLast(" · "), "KV ${c.kvType.name}", if (c.flashAttention) "flash" else null).joinToString(" · ")
    } ?: S.notAvailable)
    return out
}

/** "NEON · DOTPROD · FP16_VA · MATMUL_INT8 · KLEIDIAI": the "X = 1" entries of llama_print_system_info(). */
internal fun cpuFeatures(info: String): String {
    val on = Regex("""([A-Z0-9_]+) = 1""").findAll(info).map { it.groupValues[1] }.toList()
    return if (on.isEmpty()) info.take(60) else on.joinToString(" · ")
}

private fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)

/** Expandable mono dump of `engine.systemInfo()` (read on IO by the page; "Loading…" until it lands). */
@Composable
private fun SystemInfoRow(info: String?) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.animateContentSize(layoutSpec())) {
        SettingRow(S.fullSystemInfo, S.systemInfoDesc, onClick = { open = !open }) {
            Icon(if (open) Lucide.ChevronDown else Lucide.ChevronRight, null, Modifier.size(16.dp), tint = Ink.I500)
        }
        if (open) {
            val padding = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)
            if (info == null) Text(S.loading, style = MonoBody, color = Ink.I500, modifier = padding)
            else SelectionContainer { Text(info.replace(" | ", "\n"), style = MonoBody, color = Ink.I300, modifier = padding) }
        }
    }
}
