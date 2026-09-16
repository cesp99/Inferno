package to.eyed.inferno.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import to.eyed.inferno.BuildConfig
import to.eyed.inferno.data.KvCachePref
import to.eyed.inferno.data.SettingsState
import to.eyed.inferno.data.devBenchmark
import to.eyed.inferno.ui.S
import to.eyed.inferno.ui.components.CardDivider
import to.eyed.inferno.ui.components.ConfirmTwice
import to.eyed.inferno.ui.components.ConnectedGroup
import to.eyed.inferno.ui.components.ControlRow
import to.eyed.inferno.ui.chat.MarkdownBody
import to.eyed.inferno.ui.components.InfernoSheet
import to.eyed.inferno.ui.components.NavRow
import to.eyed.inferno.ui.components.SectionHeader
import to.eyed.inferno.ui.components.SettingRow
import to.eyed.inferno.ui.components.SettingsCard
import to.eyed.inferno.ui.components.SheetHeader
import to.eyed.inferno.ui.components.ToggleRow
import to.eyed.inferno.ui.theme.Ink
import to.eyed.inferno.ui.theme.Typography
import to.eyed.inferno.vm.AppViewModel
import to.eyed.inferno.vm.ChatViewModel
import androidx.core.net.toUri

// Settings > Advanced (developer tier) / About / Danger zone / footer (spec 5.6).

private val THREAD_OPTIONS = listOf(1, 2, 3, 4, 6, 8)
private val POLL_OPTIONS = listOf(0, 50, 100)

/**
 * Settings > Advanced (developer tier only): the engine knobs the research already answered, the benchmark entry and
 * the [developerRow] that opens the Developer page (DeveloperPage.kt). Changing any knob turns the Performance
 * preset to "Custom".
 */
@Composable
fun AdvancedSection(appVm: AppViewModel, s: SettingsState, onOpenBench: () -> Unit, developerRow: @Composable () -> Unit) {
    val quantizedKv = s.kvCache == KvCachePref.Q8_0 || s.kvCache == KvCachePref.Q4_0
    SectionHeader(S.advanced)
    SettingsCard {
        ControlRow(S.threads, description = S.bigCoresDetected(appVm.cpu.nBig)) {
            ConnectedGroup(THREAD_OPTIONS.map { it.toString() }, THREAD_OPTIONS.indexOf(s.threads), { appVm.setThreads(THREAD_OPTIONS[it]) })
        }
        CardDivider()
        ToggleRow(S.pinBigCores, s.pinBigCores, appVm::setPinBigCores, description = S.pinBigCoresDesc)
        CardDivider()
        ToggleRow(S.flashAttention, s.flashAttention, appVm::setFlashAttention, enabled = !quantizedKv,
            description = if (quantizedKv) S.requiredForQuantizedKv else S.flashAttentionDesc)
        CardDivider()
        ControlRow(S.kvCache, description = S.kvCacheDesc) {
            ConnectedGroup(listOf("Auto", "F16", "Q8_0", "Q4_0"), s.kvCache.ordinal, { appVm.setKvCache(KvCachePref.entries[it]) })
        }
        CardDivider()
        ToggleRow(S.mmap, s.useMmap, appVm::setUseMmap, description = S.mmapDesc)
        CardDivider()
        ControlRow(S.poll, description = S.pollDesc) {
            ConnectedGroup(POLL_OPTIONS.map { it.toString() }, POLL_OPTIONS.indexOf(s.poll), { appVm.setPoll(POLL_OPTIONS[it]) })
        }
        if (s.devBenchmark) {
            CardDivider()
            NavRow(S.runBenchmark, null, onClick = onOpenBench, description = S.runBenchmarkDesc)
        }
        CardDivider()
        developerRow()
    }
}

/** About + Danger zone + the footer line, for every tier. [buildLine] (developer) swaps the plain version for the llama.cpp / sd.cpp build tags. */
@Composable
fun AboutAndDangerSections(appVm: AppViewModel, chatVm: ChatViewModel, onOpenLicences: () -> Unit, buildLine: Boolean) {
    val context = LocalContext.current
    SectionHeader(S.about)
    SettingsCard {
        NavRow(S.openSourceLicences, null, onClick = onOpenLicences, description = S.licencesDesc)
        CardDivider()
        NavRow(S.sourceCode, null, description = S.sourceDesc, onClick = {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, S.sourceUrl.toUri())) }
        })
        CardDivider()
        SettingRow(S.privacy, "${S.privacyLine} ${S.privacyDesc}")
    }

    SectionHeader(S.dangerZone, color = Ink.Danger)
    SettingsCard {
        SettingRow(S.deleteAllChats, S.deleteAllChatsDesc) { ConfirmTwice(S.delete, onConfirm = { chatVm.deleteAll() }) }
        CardDivider()
        SettingRow(S.deleteAllModels, S.deleteAllModelsDesc) { ConfirmTwice(S.delete, onConfirm = appVm::deleteAllModels) }
    }

    Spacer(Modifier.height(28.dp))
    Text(
        if (buildLine) "v${BuildConfig.VERSION_NAME} · llama.cpp ${BuildConfig.LLAMA_TAG} · ${BuildConfig.LLAMA_COMMIT.take(7)} · sd.cpp ${BuildConfig.SD_COMMIT.take(7)}"
        else S.version("v${BuildConfig.VERSION_NAME}"),
        style = Typography.bodySmall, color = Ink.I500, modifier = Modifier.padding(horizontal = 8.dp),
    )
}

/** THIRD-PARTY-NOTICES.md from assets, read on IO; the sheet is the wide (I850) variant, rendered as markdown (licence texts land in code blocks). */
@Composable
fun LicensesSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text by produceState("") {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open("THIRD-PARTY-NOTICES.md").bufferedReader().use { it.readText() } }
                .getOrElse { "THIRD-PARTY-NOTICES.md is missing from this build." }
        }
    }
    InfernoSheet(onDismiss = onDismiss, wide = true) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            SheetHeader(S.openSourceLicences, Lucide.X, onLeading = onDismiss)
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                if (text.isNotEmpty()) MarkdownBody(text)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
