@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package to.eyed.inferno.ui.models

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Ellipsis
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X
import kotlinx.coroutines.delay
import to.eyed.inferno.engine.Calibration
import to.eyed.inferno.models.DownloadService
import to.eyed.inferno.models.DownloadState
import to.eyed.inferno.models.ModelEntry
import to.eyed.inferno.models.ModelTier
import to.eyed.inferno.ui.S
import to.eyed.inferno.ui.components.FlatMenu
import to.eyed.inferno.ui.components.GhostIconButton
import to.eyed.inferno.ui.components.GlassButton
import to.eyed.inferno.ui.components.InfernoSheet
import to.eyed.inferno.ui.components.MenuRow
import to.eyed.inferno.ui.components.PrimaryButton
import to.eyed.inferno.ui.components.SheetHeader
import to.eyed.inferno.ui.components.WavyBar
import to.eyed.inferno.ui.theme.Ink
import to.eyed.inferno.ui.theme.Numeric
import to.eyed.inferno.ui.theme.Typography
import to.eyed.inferno.ui.theme.fastEffectsSpec
import to.eyed.inferno.ui.theme.layoutSpec
import to.eyed.inferno.ui.theme.rememberHaptics
import java.util.Locale

// One model row (spec 5.6 ModelCards.kt). Lives inside a SettingsCard: the parent adds the dividers. The row is
// the tap target ("Load") when the model is on disk; the download states unfold underneath the subtitle with the
// wavy bar, so the title never moves while the bytes arrive.

/** Callbacks a model row can fire; the parent wires them to AppViewModel. */
class ModelActions(
    val onLoad: (() -> Unit)? = null,
    val onDownload: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onResume: () -> Unit = {},
    val onDiscard: () -> Unit = {},
    val onDelete: () -> Unit = {},
)

@Composable
fun ModelCard(
    entry: ModelEntry,
    selected: Boolean,
    loading: Boolean,
    actions: ModelActions,
    modifier: Modifier = Modifier,
    blockedReason: String? = null,
    calibration: Calibration? = null,
    showMenu: Boolean = true,
    /** Extra rows at the top of the overflow menu (imports: "Add vision projector"); receives the close action. */
    menuExtra: (@Composable (close: () -> Unit) -> Unit)? = null,
    /** Developer mode: quant · ctx · tok/s · KV in the subtitle; otherwise size · tier · licence plus the blurb. */
    techSpecs: Boolean = false,
) {
    val state = entry.download
    val onDisk = entry.isDownloaded
    val canLoad = onDisk && !selected && !loading && blockedReason == null && actions.onLoad != null
    val haptics = rememberHaptics()
    Column(
        modifier
            .fillMaxWidth()
            .then(
                if (canLoad) Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                ) { haptics.tap(); actions.onLoad.invoke() } else Modifier,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .animateContentSize(layoutSpec()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).graphicsLayer { alpha = if (blockedReason != null) 0.5f else 1f }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.displayName,
                        style = Typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                        color = Ink.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (entry.catalog?.recommended == true) {
                        Spacer(Modifier.width(8.dp))
                        Pill(S.recommended)
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(subtitle(entry, calibration, techSpecs), style = Typography.bodyMedium, color = Ink.I500, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!techSpecs) blurb(entry)?.let { Text(it, style = Typography.bodyMedium, color = Ink.I500, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            Spacer(Modifier.width(12.dp))
            Trailing(entry, selected, loading, canLoad, actions, showMenu, menuExtra)
        }
        if (blockedReason != null) {
            Spacer(Modifier.height(6.dp))
            Text(blockedReason, style = Typography.bodySmall, color = Ink.I300)
        }
        ProgressBlock(state, actions)
    }
}

/** Right edge: Load / Check / spinner / Download, plus the overflow menu for delete and discard. */
@Composable
private fun Trailing(entry: ModelEntry, selected: Boolean, loading: Boolean, canLoad: Boolean, actions: ModelActions, showMenu: Boolean, menuExtra: (@Composable (close: () -> Unit) -> Unit)?) {
    val state = entry.download
    val fade = fastEffectsSpec<Float>()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        AnimatedContent(
            targetState = Triple(state::class, selected, loading),
            transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) },
            label = "modelTrailing",
        ) { (_, sel, load) ->
            when {
                load -> Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(22.dp), color = Ink.White) }
                sel -> Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    Icon(Lucide.Check, S.loaded, Modifier.size(18.dp), tint = Ink.White)
                }
                state is DownloadState.NotDownloaded -> GlassButton(S.download, onClick = actions.onDownload)
                state is DownloadState.Downloaded && canLoad -> GlassButton(S.load, onClick = { actions.onLoad?.invoke() })
                state is DownloadState.Downloaded -> Spacer(Modifier.size(1.dp))
                state is DownloadState.Queued -> Text(S.queued, style = Typography.bodySmall, color = Ink.I500)
                else -> Spacer(Modifier.size(1.dp))
            }
        }
        val hasMenu = showMenu && (state is DownloadState.Downloaded || state is DownloadState.Paused || (state is DownloadState.Failed && state.resumable))
        if (hasMenu) RowMenu(state, actions, menuExtra)
    }
}

@Composable
private fun RowMenu(state: DownloadState, actions: ModelActions, menuExtra: (@Composable (close: () -> Unit) -> Unit)?) {
    var open by remember { mutableStateOf(false) }
    Box {
        GhostIconButton(Lucide.Ellipsis, S.more, onClick = { open = true }, size = 32.dp, iconSize = 16.dp)
        FlatMenu(open, onDismiss = { open = false }) {
            if (menuExtra != null) menuExtra { open = false }
            if (state is DownloadState.Downloaded) {
                ConfirmMenuRow(S.deleteModel, icon = Lucide.Trash2) { open = false; actions.onDelete() }
            } else {
                ConfirmMenuRow(S.discardPartial, icon = Lucide.X) { open = false; actions.onDiscard() }
            }
        }
    }
}

/** Destructive menu row that needs two taps: first arms it (label swaps to "Confirm"), auto-disarms after 3 s. */
@Composable
private fun ConfirmMenuRow(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onConfirm: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) { if (armed) { delay(3_000); armed = false } }
    MenuRow(if (armed) S.confirm else label, icon = icon, color = Ink.Danger, onClick = { if (armed) { armed = false; onConfirm() } else armed = true })
}

/** Progress states unfold under the subtitle; the bar stays flat (no wave) while paused. */
@Composable
private fun ProgressBlock(state: DownloadState, actions: ModelActions) {
    when (state) {
        is DownloadState.Downloading -> Column {
            Spacer(Modifier.height(10.dp))
            WavyBar(state.fraction)
            Spacer(Modifier.height(6.dp))
            StatusLine(
                "${S.downloadOf(DownloadService.fmt(state.downloadedBytes), DownloadService.fmt(state.totalBytes))} · ${S.perSecond(DownloadService.fmt(state.bytesPerSec))}" +
                    if (state.fileCount > 1) " · ${state.fileIndex}/${state.fileCount}" else "",
            ) { GlassButton(S.cancel, textColor = Ink.Danger, onClick = actions.onCancel) }
        }
        is DownloadState.Paused -> Column {
            Spacer(Modifier.height(10.dp))
            WavyBar(state.fraction, frozen = true, color = Ink.I300)
            Spacer(Modifier.height(6.dp))
            StatusLine("${S.downloadOf(DownloadService.fmt(state.downloadedBytes), DownloadService.fmt(state.totalBytes))} · ${state.reason?.lowercase(Locale.US) ?: S.paused}") {
                GlassButton(S.resume, onClick = actions.onResume)
            }
        }
        DownloadState.Verifying -> Column {
            Spacer(Modifier.height(10.dp))
            WavyBar(0f, indeterminate = true)
            Spacer(Modifier.height(6.dp))
            StatusLine(S.verifying) {}
        }
        is DownloadState.Failed -> Column {
            Spacer(Modifier.height(8.dp))
            StatusLine(state.message, danger = true) { GlassButton(S.retry, onClick = actions.onDownload) }
        }
        else -> Unit
    }
}

@Composable
private fun StatusLine(text: String, danger: Boolean = false, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = if (danger) Typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp) else Numeric, color = if (danger) Ink.Danger else Ink.I300, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

/** Small SurfaceHigh pill next to a title ("Recommended", "Imported"). */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier) {
    Box(modifier.clip(CircleShape).background(Ink.SurfaceHigh).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(text, style = Typography.labelSmall, color = Ink.I100)
    }
}

// ---- text helpers --------------------------------------------------------------------------------------------

/**
 * Developer mode ([tech]): "1.9 GB · Q4_0 · vision · 32k ctx · 18.4 tok/s · KV 48 KB/tok · Apache-2.0" (measured tok/s
 * when calibrated, else the estimate). Otherwise "1.9 GB · vision · Fast · Apache-2.0": the tier word stands in for
 * the numbers and [blurb] adds the plain-language line.
 */
fun subtitle(entry: ModelEntry, calibration: Calibration?, tech: Boolean = true): String {
    val cat = entry.catalog
    val local = entry.local
    val bytes = local?.totalBytes ?: cat?.totalBytes ?: 0L
    val vision = if (local?.hasVision ?: cat?.hasVision ?: false) S.vision else S.textOnly
    val licence = cat?.license ?: S.importedLicence
    if (!tech) return listOfNotNull(DownloadService.fmt(bytes), vision, cat?.let { tierWord(it.tier) }, licence).joinToString(" · ")
    val quant = (local?.quant ?: cat?.text?.quant)?.name
    val ctx = cat?.contextMax?.let { "${ctxK(it)} ctx" }
    val tps = when {
        calibration != null && calibration.tgTps > 0 -> String.format(Locale.US, "%.1f ", calibration.tgTps) + S.tokPerSec
        cat != null -> "~${cat.estTgTps} ${S.tokPerSec}"
        else -> null
    }
    val kv = cat?.kvBytesPerTokenF16?.takeIf { it > 0 }?.let { "KV ${it / 1024} KB/tok" }
    return listOfNotNull(DownloadService.fmt(bytes), quant, vision, ctx, tps, kv, licence).joinToString(" · ")
}

/** Second line for normal users: the catalog blurb, or a fixed note for imports. */
fun blurb(entry: ModelEntry): String? = entry.catalog?.blurb ?: S.importedBlurb.takeIf { entry.local != null }

/** "Fast / Balanced / Best quality" in place of tok/s numbers; the other tiers carry no speed promise. */
fun tierWord(tier: ModelTier): String? = when (tier) {
    ModelTier.FASTEST -> S.tierFast
    ModelTier.BALANCED -> S.tierBalanced
    ModelTier.QUALITY -> S.tierQuality
    else -> null
}

fun ctxK(tokens: Int): String = if (tokens >= 1024) "${tokens / 1024}k" else "$tokens"

/** The line in the download confirm: Liquid models carry the non-commercial note. */
fun licenceLine(license: String): String =
    if (license.startsWith("LFM", ignoreCase = true)) S.lfmLicenceNote else license

// ---- download confirm ----------------------------------------------------------------------------------------

/** "Download 1.9 GB · Apache-2.0" with the blurb and one white button. */
@Composable
fun DownloadConfirmSheet(entry: ModelEntry, onDismiss: () -> Unit, onDownload: () -> Unit) {
    val cat = entry.catalog ?: return
    InfernoSheet(onDismiss = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SheetHeader("${S.downloadConfirmTitle} ${DownloadService.fmt(cat.totalBytes)}", Lucide.X, onLeading = onDismiss)
            Text(cat.displayName, style = Typography.titleMedium, color = Ink.White)
            Text(cat.blurb, style = Typography.bodyMedium, color = Ink.I300)
            Text(licenceLine(cat.license), style = Typography.bodySmall, color = Ink.I500)
            Spacer(Modifier.height(4.dp))
            PrimaryButton(S.download, onClick = { onDismiss(); onDownload() }, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * Remembers which entry is waiting for its download confirm; survives rotation. Callers render
 * `pending?.let { DownloadConfirmSheet(it, ...) }` and pass `request` to the rows.
 */
@Composable
fun rememberDownloadConfirm(entries: List<ModelEntry>): Pair<ModelEntry?, (ModelEntry?) -> Unit> {
    var pendingId by rememberSaveable { mutableStateOf<String?>(null) }
    val pending = entries.firstOrNull { it.id == pendingId }
    return pending to { e -> pendingId = e?.id }
}
