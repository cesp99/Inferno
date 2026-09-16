@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package to.eyed.inferno.ui.bench

import android.content.Context
import android.content.Intent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Share2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.eyed.inferno.engine.EngineState
import to.eyed.inferno.engine.modelOrNull
import to.eyed.inferno.ui.S
import to.eyed.inferno.ui.components.CardDivider
import to.eyed.inferno.ui.components.ErrorNotice
import to.eyed.inferno.ui.components.Eyebrow
import to.eyed.inferno.ui.components.GhostIconButton
import to.eyed.inferno.ui.components.GlassButton
import to.eyed.inferno.ui.components.PrimaryButton
import to.eyed.inferno.ui.components.SectionHeader
import to.eyed.inferno.ui.components.SettingsCard
import to.eyed.inferno.ui.components.WavyBar
import to.eyed.inferno.ui.components.surfaceCard
import to.eyed.inferno.ui.theme.Ink
import to.eyed.inferno.ui.theme.Numeric
import to.eyed.inferno.ui.theme.Radii
import to.eyed.inferno.ui.theme.Typography
import to.eyed.inferno.ui.theme.layoutSpec
import to.eyed.inferno.vm.AppViewModel
import to.eyed.inferno.vm.BenchViewModel
import to.eyed.inferno.vm.Screen
import java.io.File
import java.util.Locale

// Benchmark screen (spec 5.6 BenchScreen.kt): headline number from the last run, quick / matrix runs with the
// wavy progress, a tabular results table (tnum so the columns never wobble), thermal line and Share JSON.

@Composable
fun BenchScreen(benchVm: BenchViewModel, appVm: AppViewModel, onBack: () -> Unit = { appVm.back() }) {
    val running by benchVm.running.collectAsStateWithLifecycle()
    val progress by benchVm.progress.collectAsStateWithLifecycle()
    val rows by benchVm.rows.collectAsStateWithLifecycle()
    val notice by benchVm.notice.collectAsStateWithLifecycle()
    val thermal by benchVm.thermal.collectAsStateWithLifecycle()
    val engine by appVm.engine.collectAsStateWithLifecycle()
    val model = engine.modelOrNull
    val ready = engine is EngineState.Ready || running
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Column(Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection)) {
        LargeFlexibleTopAppBar(
            title = { Text(S.benchmark, color = Ink.White) },
            subtitle = { Text(model?.displayName ?: S.noModelLoaded, style = Typography.bodyMedium, color = Ink.I500) },
            navigationIcon = { GhostIconButton(Lucide.ArrowLeft, S.back, onClick = onBack, size = 40.dp, iconSize = 20.dp, tint = Ink.I100) },
            windowInsets = WindowInsets(0),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink.Pitch, scrolledContainerColor = Ink.Pitch, titleContentColor = Ink.White, subtitleContentColor = Ink.I500),
            scrollBehavior = scroll,
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            notice?.let { ErrorNotice(it, onDismiss = benchVm::dismissNotice, Modifier.padding(bottom = 12.dp)) }

            Column(Modifier.fillMaxWidth().surfaceCard(RoundedCornerShape(Radii.card), fill = Ink.I850).padding(20.dp).animateContentSize(layoutSpec())) {
                val last = rows.lastOrNull()
                if (last != null) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(String.format(Locale.US, "%.1f", last.tgTps), style = Typography.headlineMedium, color = Ink.White)
                        Spacer(Modifier.width(8.dp))
                        Text("${S.tokPerSec} ${S.benchGenerate}", style = Typography.bodyMedium, color = Ink.I500, modifier = Modifier.padding(bottom = 6.dp))
                    }
                    Spacer(Modifier.height(2.dp))
                    Text("${String.format(Locale.US, "%.1f", last.ppTps)} ${S.tokPerSec} ${S.prompt} · ${last.label} · ${last.threads} ${S.thr} · ${last.kv.name}", style = Numeric, color = Ink.I500)
                    Spacer(Modifier.height(18.dp))
                } else if (model == null) {
                    Text(S.loadModelFirst, style = Typography.titleMedium, color = Ink.White)
                    Spacer(Modifier.height(4.dp))
                    Text(S.chooseModelForBench, style = Typography.bodyMedium, color = Ink.I500)
                    Spacer(Modifier.height(16.dp))
                    GlassButton(S.chooseModelButton, onClick = { appVm.navigate(Screen.MODELS) })
                }
                if (model != null) {
                    if (running) {
                        WavyBar(progress)
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${S.running} ${(progress * 100).toInt()} %", style = Numeric, color = Ink.I300, modifier = Modifier.weight(1f))
                            GlassButton(S.stop, textColor = Ink.Danger, onClick = benchVm::stop)
                        }
                    } else {
                        PrimaryButton(S.runQuick, onClick = benchVm::runQuick, enabled = ready, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        Text(S.quickRunDesc, style = Typography.bodySmall, color = Ink.I500)
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GlassButton(S.runThreadMatrix, onClick = benchVm::runMatrix, enabled = ready)
                            Spacer(Modifier.width(12.dp))
                            Text(S.matrixDesc, style = Typography.bodySmall, color = Ink.I500, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            SectionHeader("Results")
            if (rows.isEmpty()) {
                Text(S.benchIdle, style = Typography.bodyMedium, color = Ink.I500, modifier = Modifier.padding(horizontal = 8.dp))
            } else {
                SettingsCard {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Eyebrow(S.label, Modifier.weight(1f))
                        Eyebrow(S.thr, Modifier.width(40.dp))
                        Eyebrow(S.kv, Modifier.width(56.dp))
                        Eyebrow("pp", Modifier.width(64.dp))
                        Eyebrow("tg", Modifier.width(56.dp))
                    }
                    rows.forEach { r ->
                        CardDivider()
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.label, style = Typography.bodyMedium, color = Ink.White)
                                Text(if (r.pinned) "pinned" else "free", style = Typography.bodySmall, color = Ink.I500)
                            }
                            Text(r.threads.toString(), style = Numeric, color = Ink.I300, modifier = Modifier.width(40.dp))
                            Text(r.kv.name, style = Numeric, color = Ink.I300, modifier = Modifier.width(56.dp))
                            Text(String.format(Locale.US, "%.1f", r.ppTps), style = Numeric, color = Ink.I100, modifier = Modifier.width(64.dp))
                            Text(String.format(Locale.US, "%.1f", r.tgTps), style = Numeric, color = Ink.White, modifier = Modifier.width(56.dp), textAlign = TextAlign.Start)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${S.thermalLabel} ${S.thermalNames.getOrElse(thermal) { thermal.toString() }}", style = Numeric, color = if (thermal >= 2) Ink.I100 else Ink.I500, modifier = Modifier.weight(1f).padding(start = 8.dp))
                GlassButton(S.shareJson, icon = Lucide.Share2, enabled = rows.isNotEmpty() && !running, onClick = {
                    scope.launch { shareJson(context, withContext(Dispatchers.IO) { benchVm.exportJson() }) }
                })
            }
        }
    }
}

/** Copies the export into the FileProvider `exports/` root and opens the share sheet. */
private fun shareJson(context: Context, file: File) {
    val dir = File(context.filesDir, "exports").also { it.mkdirs() }
    val out = File(dir, "inferno-bench-${file.nameWithoutExtension}.json")
    file.copyTo(out, overwrite = true)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
    val send = Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(send, S.shareJson)) }
}
