package to.eyed.inferno.ui.create

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import to.eyed.inferno.models.DownloadService
import to.eyed.inferno.models.DownloadState
import to.eyed.inferno.models.ImageCatalogModel
import to.eyed.inferno.models.ImageModelCatalog
import to.eyed.inferno.ui.S
import to.eyed.inferno.ui.components.ConfirmTwice
import to.eyed.inferno.ui.components.GhostIconButton
import to.eyed.inferno.ui.components.GlassButton
import to.eyed.inferno.ui.theme.Ink
import to.eyed.inferno.ui.theme.Numeric
import to.eyed.inferno.ui.theme.Radii
import to.eyed.inferno.ui.theme.RowMeta
import to.eyed.inferno.ui.theme.Typography
import to.eyed.inferno.ui.theme.fastEffectsSpec
import to.eyed.inferno.ui.theme.pressScale
import to.eyed.inferno.ui.theme.rememberHaptics
import to.eyed.inferno.ui.theme.whiteA

/**
 * One of the two large model cards on the Create screen (12.5): name + tier, one-line blurb, the
 * "~12 s · 693 MB · OpenRAIL++" meta line, and a download strip whose content follows [download].
 * Selection is a white border (never a fill), exactly like OptionRow; the whole card is the radio button.
 */
@Composable
fun ImageModelCard(
    model: ImageCatalogModel,
    selected: Boolean,
    download: DownloadState,
    etaSeconds: Int,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(Radii.card)
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    val border by animateColorAsState(if (selected) Ink.White else Ink.I700, fastEffectsSpec(), label = "cardBorder")
    Column(
        modifier
            .fillMaxWidth()
            .pressScale(interaction, 0.985f)
            .clip(shape)
            .background(Ink.I850)
            .border(if (selected) 1.5.dp else 1.dp, border, shape)
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                role = Role.RadioButton,
            ) { if (!selected) { haptics.tap(); onSelect() } }
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(model.displayName, style = Typography.titleMedium, color = Ink.White, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            TierPill(model.tierLabel, active = selected)
        }
        Spacer(Modifier.height(4.dp))
        Text(model.blurb, style = Typography.bodyMedium, color = Ink.I500)
        Spacer(Modifier.height(10.dp))
        Text(
            "${S.eta(etaSeconds)} · ${DownloadService.fmt(ImageModelCatalog.bytesOnDisk(model))} · ${model.license}",
            style = RowMeta,
            color = Ink.I300,
        )
        Spacer(Modifier.height(12.dp))
        DownloadStrip(download, onDownload, onCancel, onDiscard)
    }
}

/** "QUALITY" / "INSTANT": the tier as a tiny pill; white-on-black when the card is selected. */
@Composable
private fun TierPill(label: String, active: Boolean) {
    val bg by animateColorAsState(if (active) Ink.White else Ink.SurfaceHigh, fastEffectsSpec(), label = "tierBg")
    val fg by animateColorAsState(if (active) Ink.Pitch else Ink.I300, fastEffectsSpec(), label = "tierFg")
    Box(Modifier.clip(CircleShape).background(bg).padding(horizontal = 9.dp, vertical = 3.dp)) {
        Text(
            label.uppercase(),
            style = Typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp),
            color = fg,
        )
    }
}

/**
 * The bottom strip of a card. Every download state gets a distinct, quiet presentation; the wavy bar
 * (6.3) only waves while bytes flow and freezes flat when Paused.
 */
@Composable
private fun DownloadStrip(
    state: DownloadState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDiscard: () -> Unit,
) {
    val fade = fastEffectsSpec<Float>()
    AnimatedContent(
        targetState = state::class,
        transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) },
        label = "downloadStrip",
        contentAlignment = Alignment.CenterStart,
    ) { _ ->
        when (state) {
            DownloadState.NotDownloaded -> EndRow { GlassButton(S.download, onClick = onDownload, icon = Lucide.Download) }
            is DownloadState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.message, style = RowMeta, color = Ink.Danger, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                GlassButton(S.retry, onClick = onDownload)
            }
            DownloadState.Queued -> StatusRow(S.waiting, spinner = true) { GhostIconButton(Lucide.X, S.cancel, onClick = onCancel) }
            DownloadState.Verifying -> Column {
                WavyBar(progress = null)
                Spacer(Modifier.height(8.dp))
                StatusRow(S.verifying, spinner = false) {}
            }
            is DownloadState.Downloading -> Column {
                WavyBar(progress = state.fraction)
                Spacer(Modifier.height(8.dp))
                StatusRow("${DownloadService.fmt(state.downloadedBytes)} of ${DownloadService.fmt(state.totalBytes)} · ${DownloadService.fmt(state.bytesPerSec)}/s") {
                    GhostIconButton(Lucide.X, S.cancel, onClick = onCancel)
                }
            }
            is DownloadState.Paused -> Column {
                WavyBar(progress = state.fraction, frozen = true)
                Spacer(Modifier.height(8.dp))
                StatusRow("${DownloadService.fmt(state.downloadedBytes)} of ${DownloadService.fmt(state.totalBytes)} · ${state.reason ?: S.paused}") {
                    ConfirmTwice(S.discard, onConfirm = onDiscard)
                    Spacer(Modifier.width(8.dp))
                    GlassButton(S.resume, onClick = onDownload)
                }
            }
            DownloadState.Downloaded -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Lucide.Check, null, Modifier.size(14.dp), tint = Ink.I300)
                Text(S.downloaded, style = Typography.labelMedium, color = Ink.I300)
            }
        }
    }
}

@Composable
private fun EndRow(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { content() }
}

@Composable
private fun StatusRow(text: String, spinner: Boolean = false, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (spinner) {
            LoadingIndicator(Modifier.size(16.dp), color = Ink.White)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = Numeric, color = Ink.I300, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

/**
 * White wavy bar on a 12 % white track. [progress] null = indeterminate (verifying); [frozen] keeps the
 * fill but flattens the wave so a paused download visibly stands still.
 */
@Composable
private fun WavyBar(progress: Float?, frozen: Boolean = false) {
    val track = whiteA(0.12f)
    val stroke = WavyProgressIndicatorDefaults.linearIndicatorStroke
    val trackStroke = WavyProgressIndicatorDefaults.linearTrackStroke
    if (progress == null) {
        LinearWavyProgressIndicator(
            modifier = Modifier.fillMaxWidth().semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate },
            color = Ink.White,
            trackColor = track,
            stroke = stroke,
            trackStroke = trackStroke,
        )
    } else {
        val p = progress.coerceIn(0f, 1f)
        val amplitude: (Float) -> Float = if (frozen) { _ -> 0f } else WavyProgressIndicatorDefaults.indicatorAmplitude
        LinearWavyProgressIndicator(
            progress = { p },
            modifier = Modifier.fillMaxWidth().semantics { progressBarRangeInfo = ProgressBarRangeInfo(p, 0f..1f) },
            color = Ink.White,
            trackColor = track,
            stroke = stroke,
            trackStroke = trackStroke,
            amplitude = amplitude,
        )
    }
}
