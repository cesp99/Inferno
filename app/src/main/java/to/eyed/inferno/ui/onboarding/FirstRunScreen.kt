@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package to.eyed.inferno.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.eyed.inferno.engine.EngineState
import to.eyed.inferno.models.DownloadService
import to.eyed.inferno.models.DownloadState
import to.eyed.inferno.models.ModelCatalog
import to.eyed.inferno.ui.S
import to.eyed.inferno.ui.components.Eyebrow
import to.eyed.inferno.ui.components.GlassButton
import to.eyed.inferno.ui.components.MorphingMark
import to.eyed.inferno.ui.components.PrimaryButton
import to.eyed.inferno.ui.components.QuietNotice
import to.eyed.inferno.ui.components.WavyBar
import to.eyed.inferno.ui.components.surfaceCard
import to.eyed.inferno.ui.models.ModelManagerScreen
import to.eyed.inferno.ui.models.licenceLine
import to.eyed.inferno.ui.models.rememberImportFlow
import to.eyed.inferno.ui.theme.Ink
import to.eyed.inferno.ui.theme.Numeric
import to.eyed.inferno.ui.theme.Radii
import to.eyed.inferno.ui.theme.Typography
import to.eyed.inferno.ui.theme.fastEffectsSpec
import to.eyed.inferno.ui.theme.layoutSpec
import to.eyed.inferno.vm.AppViewModel
import java.util.Locale

// First run (spec 6.1 flow 2): the mark, the promise, one recommended model with the wavy download inline, and
// the two alternative paths. The metered confirm comes from Root, the auto-load from AppViewModel.firstRunAutoLoad.

@Composable
fun FirstRunScreen(appVm: AppViewModel, onBeforeDownload: () -> Unit) {
    var page by rememberSaveable { mutableStateOf("hero") }
    BackHandler(enabled = page != "hero") { page = "hero" }
    val fade = fastEffectsSpec<Float>()
    AnimatedContent(page, transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) }, label = "firstRun") { p ->
        if (p == "manager") ModelManagerScreen(appVm, onBeforeDownload, onBack = { page = "hero" })
        else Hero(appVm, onBeforeDownload, onChooseAnother = { page = "manager" })
    }
}

@Composable
private fun Hero(appVm: AppViewModel, onBeforeDownload: () -> Unit, onChooseAnother: () -> Unit) {
    val models by appVm.models.collectAsStateWithLifecycle()
    val engine by appVm.engine.collectAsStateWithLifecycle()
    val storage by appVm.storage.collectAsStateWithLifecycle()
    val rec = ModelCatalog.recommended
    val entry = models.firstOrNull { it.id == rec.id }
    val state = entry?.download ?: DownloadState.NotDownloaded
    val importGguf = rememberImportFlow(appVm)
    val busy = state is DownloadState.Downloading || state is DownloadState.Verifying || engine is EngineState.Loading

    // The hero sits a little above the optical centre; when the card unfolds the column simply grows and scrolls.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val minHeight = maxHeight
        Column(
            Modifier.fillMaxWidth().heightIn(min = minHeight).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
        MorphingMark(72.dp, active = busy, once = true)
        Spacer(Modifier.height(28.dp))
        Text(S.appName, style = Typography.headlineSmall, color = Ink.White)
        Spacer(Modifier.height(6.dp))
        Text(S.promise, style = Typography.titleSmall, color = Ink.I100)
        Spacer(Modifier.height(10.dp))
        Text(S.firstRunTagline, style = Typography.bodyMedium, color = Ink.I500, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 300.dp))
        Spacer(Modifier.height(44.dp))

        RecommendedCard(state, engine, appVm, onBeforeDownload)

        if (storage.totalBytes > 0 && storage.freeBytes < 2 * rec.totalBytes && state !is DownloadState.Downloaded) {
            Spacer(Modifier.height(12.dp))
            QuietNotice(S.freeSpaceWarning)
        }

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton(S.chooseDifferentModel, onClick = onChooseAnother)
            GlassButton(S.importAGguf, onClick = importGguf)
        }
        Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun RecommendedCard(state: DownloadState, engine: EngineState, appVm: AppViewModel, onBeforeDownload: () -> Unit) {
    val rec = ModelCatalog.recommended
    Column(
        Modifier.fillMaxWidth().widthIn(max = 480.dp).surfaceCard(RoundedCornerShape(Radii.card), fill = Ink.I850).padding(20.dp).animateContentSize(layoutSpec()),
    ) {
        Eyebrow(S.recommended)
        Spacer(Modifier.height(8.dp))
        Text(rec.displayName, style = Typography.titleMedium, color = Ink.White)
        Spacer(Modifier.height(2.dp))
        Text("${DownloadService.fmt(rec.totalBytes)} download · ${if (rec.hasVision) "text + vision" else "text"} · ${licenceLine(rec.license)}", style = Typography.bodyMedium, color = Ink.I500)
        Spacer(Modifier.height(6.dp))
        Text(rec.blurb, style = Typography.bodySmall, color = Ink.I500)
        Spacer(Modifier.height(18.dp))

        when {
            engine is EngineState.Loading -> LoadingLine(engine.phase + (engine.progress?.let { " ${(it * 100).toInt()} %" } ?: ""))
            engine is EngineState.Error -> {
                Text(engine.message, style = Typography.bodySmall, color = Ink.Danger)
                Spacer(Modifier.height(10.dp))
                GlassButton(S.retry, onClick = { appVm.selectAndLoad(rec.id) })
            }
            state is DownloadState.NotDownloaded ->
                PrimaryButton("${S.downloadAndStart} · ${DownloadService.fmt(rec.totalBytes)}", onClick = { onBeforeDownload(); appVm.download(rec.id) }, modifier = Modifier.fillMaxWidth())
            state is DownloadState.Queued -> { WavyBar(0f, indeterminate = true); Spacer(Modifier.height(8.dp)); StatusRow(S.queued) { GlassButton(S.cancel, onClick = { appVm.cancelDownload(rec.id) }) } }
            state is DownloadState.Downloading -> {
                WavyBar(state.fraction)
                Spacer(Modifier.height(8.dp))
                StatusRow("${S.downloadOf(DownloadService.fmt(state.downloadedBytes), DownloadService.fmt(state.totalBytes))} · ${S.perSecond(DownloadService.fmt(state.bytesPerSec))}") {
                    GlassButton(S.cancel, textColor = Ink.Danger, onClick = { appVm.cancelDownload(rec.id) })
                }
                Spacer(Modifier.height(6.dp))
                Text(S.autoLoadsWhenDone, style = Typography.bodySmall, color = Ink.I500)
            }
            state is DownloadState.Paused -> {
                WavyBar(state.fraction, frozen = true, color = Ink.I300)
                Spacer(Modifier.height(8.dp))
                Text("${S.downloadOf(DownloadService.fmt(state.downloadedBytes), DownloadService.fmt(state.totalBytes))} · ${state.reason?.lowercase(Locale.US) ?: S.paused}", style = Numeric, color = Ink.I300)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    GlassButton(S.discardDownload, textColor = Ink.Danger, onClick = { appVm.discardPartial(rec.id) })
                    GlassButton(S.resume, onClick = { onBeforeDownload(); appVm.download(rec.id) })
                }
            }
            state is DownloadState.Verifying -> { WavyBar(0f, indeterminate = true); Spacer(Modifier.height(8.dp)); Text(S.verifying, style = Numeric, color = Ink.I300) }
            state is DownloadState.Failed -> {
                Text(state.message, style = Typography.bodySmall, color = Ink.Danger)
                Spacer(Modifier.height(10.dp))
                GlassButton(S.retry, onClick = { onBeforeDownload(); appVm.download(rec.id) })
            }
            state is DownloadState.Downloaded -> LoadingLine(S.startingUp)
        }
    }
}

@Composable
private fun LoadingLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LoadingIndicator(Modifier.size(24.dp), color = Ink.White)
        Text(text, style = Numeric, color = Ink.I300)
    }
}

@Composable
private fun StatusRow(text: String, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = Numeric, color = Ink.I300, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}
