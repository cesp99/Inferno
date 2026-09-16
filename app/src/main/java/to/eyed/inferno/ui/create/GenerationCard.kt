package to.eyed.inferno.ui.create

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.ImageDown
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Paperclip
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.Square
import to.eyed.inferno.imagegen.GenerationRecord
import to.eyed.inferno.imagegen.ImageGenUiState
import to.eyed.inferno.ui.S
import to.eyed.inferno.ui.components.GlassButton
import to.eyed.inferno.ui.components.PrimaryButton
import to.eyed.inferno.ui.theme.Ink
import to.eyed.inferno.ui.theme.Numeric
import to.eyed.inferno.ui.theme.Radii
import to.eyed.inferno.ui.theme.RowMeta
import to.eyed.inferno.ui.theme.Typography
import to.eyed.inferno.ui.theme.defaultEffectsSpec
import to.eyed.inferno.ui.theme.defaultSpatialSpec
import to.eyed.inferno.ui.theme.etaProgressSpec
import to.eyed.inferno.ui.theme.fastEffectsSpec
import to.eyed.inferno.ui.theme.pressScale
import to.eyed.inferno.ui.theme.rememberHaptics

/** Shared-element key of a generated image between the Create / Gallery tiles and the full-screen viewer. */
fun generatedImageKey(record: GenerationRecord) = "generated-${record.id}"

/**
 * One progress value for the whole screen (hero ring and the CTA ring). The denoise steps plus the TAESD
 * decode form N+1 equal segments; the ring glides towards the next segment over the ETA's share of time,
 * so it moves continuously between the sparse Progress events instead of jumping once per step.
 */
@Composable
fun rememberGenerationProgress(state: ImageGenUiState): State<Float> {
    val ring = remember { Animatable(0f) }
    val st = state as? ImageGenUiState.Generating
    LaunchedEffect(st?.step, st?.total, state::class) {
        when {
            st == null -> if (state is ImageGenUiState.Done) ring.snapTo(1f) else ring.snapTo(0f)
            else -> {
                val segments = st.total + 1
                val target = ((st.step + 1).toFloat() / segments).coerceAtMost(1f)
                val left = (st.total - st.step + 1).coerceAtLeast(1)
                // "Again" on a loaded model skips the Loading state, so a full ring from the last Done may
                // still be showing: a new run always starts from empty, never winds backwards.
                if (st.step == 0 && ring.value > target) ring.snapTo(0f)
                ring.animateTo(target, etaProgressSpec(st.etaSeconds * 1000 / left))
            }
        }
    }
    return ring.asState()
}

/**
 * The ETA counts down between the sparse Progress events: the repository only re-estimates once per step, so
 * without this "~12 s" would sit frozen for the whole SDXS run. Never drops below 1 s while still running.
 */
@Composable
fun rememberLiveEta(state: ImageGenUiState): Int {
    val st = state as? ImageGenUiState.Generating
    var shown by remember { mutableIntStateOf(st?.etaSeconds ?: 0) }
    LaunchedEffect(st?.step, st?.total, st?.etaSeconds) {
        if (st == null) return@LaunchedEffect
        val startedAt = SystemClock.elapsedRealtime()
        shown = st.etaSeconds
        while (true) {
            delay(1_000)
            val elapsed = ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt()
            shown = (st.etaSeconds - elapsed).coerceAtLeast(1)
        }
    }
    return shown
}

fun ImageGenUiState.Generating.label(etaSeconds: Int = this.etaSeconds): String = when {
    isDecoding -> "${S.finishing} · ${S.eta(etaSeconds)}"
    step == 0 -> "${S.starting} · ${S.eta(etaSeconds)}"
    else -> "${S.stepOf(step, total)} · ${S.eta(etaSeconds)}"
}

/**
 * The hero of the Create screen: a square canvas that is the model loading, the blurred latent preview
 * with the progress ring, the finished image (tap = viewer, shared bounds), or the error. Below the canvas,
 * only when Done, the meta line and the four actions.
 */
@Composable
fun GenerationCard(
    state: ImageGenUiState,
    progress: State<Float>,
    etaSeconds: Int,
    modelName: String,
    sizePx: Int,
    sharedScope: SharedTransitionScope,
    animatedScope: AnimatedVisibilityScope,
    onOpen: (GenerationRecord) -> Unit,
    onSave: (GenerationRecord) -> Unit,
    onShare: (GenerationRecord) -> Unit,
    onAttach: (GenerationRecord) -> Unit,
    onAgain: (GenerationRecord) -> Unit,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fade = defaultEffectsSpec<Float>()
    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(Radii.card))
                .background(Ink.I850),
        ) {
            AnimatedContent(
                targetState = state,
                contentKey = { it::class },
                transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) },
                label = "hero",
            ) { st ->
                when (st) {
                    is ImageGenUiState.Loading -> Centre {
                        LoadingIndicator(Modifier.size(44.dp), color = Ink.White)
                        Spacer(Modifier.height(14.dp))
                        Text(S.loadingImageModel(modelName), style = Typography.bodyMedium, color = Ink.I300)
                    }
                    is ImageGenUiState.Generating -> GeneratingFace(st, progress, etaSeconds, modelName, sizePx)
                    is ImageGenUiState.Done -> with(sharedScope) {
                        AsyncImage(
                            st.record.path,
                            S.generatedImage,
                            Modifier
                                .fillMaxSize()
                                .sharedBounds(
                                    rememberSharedContentState(generatedImageKey(st.record)),
                                    animatedScope,
                                    clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(Radii.card)),
                                )
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    role = Role.Button,
                                    onClickLabel = S.openImage,
                                ) { onOpen(st.record) },
                            contentScale = ContentScale.Crop,
                        )
                    }
                    is ImageGenUiState.Error -> Centre {
                        Icon(Lucide.CircleAlert, null, Modifier.size(22.dp), tint = Ink.I300)
                        Spacer(Modifier.height(12.dp))
                        Text(st.message, style = Typography.bodyMedium, color = Ink.I100, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
                        Spacer(Modifier.height(18.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GlassButton(S.retry, onClick = onRetry)
                            GlassButton(S.ok, onClick = onDismissError)
                        }
                    }
                    else -> Unit
                }
            }
        }
        val record = (state as? ImageGenUiState.Done)?.record
        // Keeps the last record so the shrink-out animation still has something to draw.
        var last by remember { mutableStateOf(record) }
        if (record != null) last = record
        AnimatedVisibility(
            visible = record != null,
            enter = expandVertically(defaultSpatialSpec()) + fadeIn(fade),
            exit = shrinkVertically(defaultSpatialSpec()) + fadeOut(fade),
        ) {
            val r = last ?: return@AnimatedVisibility
            Column {
                Spacer(Modifier.height(12.dp))
                Text(
                    "${S.square(r.width)} · seed ${r.seed} · ${S.duration(r.totalMs)}",
                    style = RowMeta,
                    color = Ink.I500,
                    modifier = Modifier.padding(start = 4.dp),
                )
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton(S.saveToPhotos, onClick = { onSave(r) }, icon = Lucide.ImageDown)
                    GlassButton(S.share, onClick = { onShare(r) }, icon = Lucide.Share2)
                    GlassButton(S.useAsAttachment, onClick = { onAttach(r) }, icon = Lucide.Paperclip)
                    GlassButton(S.again, onClick = { onAgain(r) }, icon = Lucide.RefreshCw)
                }
            }
        }
    }
}

@Composable
private fun Centre(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { content() }
}

/** Blurred latent projection behind a 45 % scrim, the ring and "Step 2/4 · ~40 s" (12.5). */
@Composable
private fun GeneratingFace(st: ImageGenUiState.Generating, progress: State<Float>, etaSeconds: Int, modelName: String, sizePx: Int) {
    // The projection is tiny (latent / 8), so decoding it in composition costs well under a millisecond.
    val bitmap = remember(st.previewPng) {
        st.previewPng?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }
    Box(Modifier.fillMaxSize()) {
        Crossfade(bitmap, animationSpec = defaultEffectsSpec(), label = "latent") { bmp ->
            if (bmp != null) {
                Image(
                    bmp, null,
                    Modifier.fillMaxSize().blur(28.dp).clearAndSetSemantics {},
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.Low,
                )
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (bitmap != null) 0.45f else 0f)))
        Text(
            "$modelName · ${S.square(sizePx)}",
            style = RowMeta,
            color = Ink.I300,
            modifier = Modifier.align(Alignment.TopStart).padding(18.dp),
        )
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            ProgressRing(progress, 76.dp, 3.dp)
            Spacer(Modifier.height(16.dp))
            Text(st.label(etaSeconds), style = Numeric.copy(fontSize = 14.sp, lineHeight = 20.sp), color = Ink.I100)
        }
    }
}

/** 2-3 dp white ring on a 15 % track; determinate, semantics carry the fraction. */
@Composable
fun ProgressRing(progress: State<Float>, size: Dp, stroke: Dp, color: Color = Ink.White) {
    CircularProgressIndicator(
        progress = { progress.value },
        modifier = Modifier.size(size).semantics { progressBarRangeInfo = ProgressBarRangeInfo(progress.value, 0f..1f) },
        color = color,
        strokeWidth = stroke,
        trackColor = color.copy(alpha = 0.15f),
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
    )
}

/** What the primary control at the bottom of the Create screen currently is. */
sealed interface CtaMode {
    data class Generate(val etaSeconds: Int, val enabled: Boolean) : CtaMode
    data class Busy(val label: String, val loading: Boolean) : CtaMode
    data class Download(val label: String) : CtaMode
    data class Progress(val label: String) : CtaMode
}

/**
 * The white "Generate · ~12 s" pill that morphs into a progress pill (ring + "Step 2/4 · ~40 s") with a
 * Stop circle beside it while a run is busy, or into the download CTA when the chosen model is not on the
 * phone. One control, always meaningful.
 */
@Composable
fun GenerateCta(
    mode: CtaMode,
    progress: State<Float>,
    onGenerate: () -> Unit,
    onCancel: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fade = fastEffectsSpec<Float>()
    val haptics = rememberHaptics()
    AnimatedContent(
        targetState = mode,
        contentKey = { it::class },
        transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) },
        label = "cta",
        modifier = modifier,
    ) { m ->
        when (m) {
            is CtaMode.Generate -> PrimaryButton(
                "${S.generate} · ${S.eta(m.etaSeconds)}",
                onClick = { haptics.gestureEnd(); onGenerate() },
                enabled = m.enabled,
                modifier = Modifier.fillMaxWidth(),
            )
            is CtaMode.Download -> PrimaryButton(m.label, onClick = onDownload, modifier = Modifier.fillMaxWidth())
            is CtaMode.Progress -> PrimaryButton(m.label, onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth())
            is CtaMode.Busy -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier
                        .weight(1f)
                        .height(50.dp)
                        .clip(CircleShape)
                        .background(Ink.SurfaceHigh)
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    if (m.loading) LoadingIndicator(Modifier.size(20.dp), color = Ink.White)
                    else ProgressRing(progress, 18.dp, 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        m.label,
                        color = Ink.I100,
                        style = Typography.labelLarge.copy(fontSize = 15.sp, lineHeight = 20.sp),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                val interaction = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .size(50.dp)
                        .pressScale(interaction, 0.92f)
                        .clip(CircleShape)
                        .background(Ink.SurfaceHigh)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            role = Role.Button,
                            onClickLabel = S.stop,
                        ) { haptics.tap(); onCancel() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Lucide.Square, S.stop, Modifier.size(15.dp), tint = Ink.I100)
                }
            }
        }
    }
}
