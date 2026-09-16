package to.eyed.inferno.ui.create

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Images
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Minus
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.RotateCcw
import com.composables.icons.lucide.Shuffle
import to.eyed.inferno.imagegen.GenerationRecord
import to.eyed.inferno.imagegen.ImageGenUiState
import to.eyed.inferno.imagegen.ImageSizePreset
import to.eyed.inferno.models.DownloadService
import to.eyed.inferno.models.DownloadState
import to.eyed.inferno.models.ImageCatalogModel
import to.eyed.inferno.models.ImageModelCatalog
import to.eyed.inferno.ui.S
import to.eyed.inferno.ui.components.CardDivider
import to.eyed.inferno.ui.components.GhostIconButton
import to.eyed.inferno.ui.components.SectionHeader
import to.eyed.inferno.ui.components.SegmentedPill
import to.eyed.inferno.ui.components.SettingRow
import to.eyed.inferno.ui.components.SettingsCard
import to.eyed.inferno.ui.components.SheetHeader
import to.eyed.inferno.ui.components.copyToClipboard
import to.eyed.inferno.ui.components.surfaceLow
import to.eyed.inferno.ui.components.surfaceRaised
import to.eyed.inferno.ui.theme.Ink
import to.eyed.inferno.ui.theme.Numeric
import to.eyed.inferno.ui.theme.Radii
import to.eyed.inferno.ui.theme.Typography
import to.eyed.inferno.ui.theme.defaultEffectsSpec
import to.eyed.inferno.ui.theme.defaultSpatialSpec
import to.eyed.inferno.ui.theme.rememberHaptics
import to.eyed.inferno.vm.AppViewModel
import to.eyed.inferno.vm.ChatViewModel
import to.eyed.inferno.vm.ImageGenViewModel
import to.eyed.inferno.vm.Screen
import to.eyed.inferno.vm.isBusy
import java.io.File

/**
 * Create image (12.5): the hero canvas (progress / result) above the two model cards and the options card,
 * with the prompt composer and the morphing Generate CTA docked at the bottom, thumb-reachable. The
 * full-screen viewer swaps in through shared bounds when the result is tapped.
 */
@Composable
fun CreateScreen(imageVm: ImageGenViewModel, appVm: AppViewModel, chatVm: ChatViewModel, onBeforeDownload: () -> Unit) {
    val state by imageVm.state.collectAsStateWithLifecycle()
    val gallery by imageVm.gallery.collectAsStateWithLifecycle()
    val notice by imageVm.notice.collectAsStateWithLifecycle()
    var viewerId by rememberSaveable { mutableStateOf<String?>(null) }
    val viewing = gallery.firstOrNull { it.id == viewerId }
    val actions = rememberImageActions(imageVm, appVm, chatVm)
    val fade = defaultEffectsSpec<Float>()
    val haptics = rememberHaptics()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Done -> one Confirm, Error -> one Reject; only while the activity is RESUMED (6.7).
    LaunchedEffect(state::class) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        when (state) {
            is ImageGenUiState.Done -> haptics.confirm()
            is ImageGenUiState.Error -> haptics.reject()
            else -> Unit
        }
    }

    Box(Modifier.fillMaxSize()) {
        SharedTransitionLayout(Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = viewing,
                transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) },
                label = "createViewer",
            ) { open ->
                if (open != null) {
                    ImageViewer(
                        open, this@SharedTransitionLayout, this@AnimatedContent,
                        onClose = { viewerId = null },
                        onSave = actions.save, onShare = actions.share, onAttach = actions.attach,
                    )
                } else {
                    CreateContent(
                        imageVm, appVm, state, actions, this@SharedTransitionLayout, this@AnimatedContent,
                        onOpen = { viewerId = it.id }, onBeforeDownload = onBeforeDownload,
                    )
                }
            }
        }
        TransientNotice(notice, onDismiss = imageVm::dismissNotice, modifier = Modifier.align(Alignment.TopCenter))
    }
}

@Composable
private fun CreateContent(
    imageVm: ImageGenViewModel,
    appVm: AppViewModel,
    state: ImageGenUiState,
    actions: ImageActions,
    sharedScope: SharedTransitionScope,
    animatedScope: AnimatedVisibilityScope,
    onOpen: (GenerationRecord) -> Unit,
    onBeforeDownload: () -> Unit,
) {
    val prompt by imageVm.prompt.collectAsStateWithLifecycle()
    val modelId by imageVm.modelId.collectAsStateWithLifecycle()
    val size by imageVm.size.collectAsStateWithLifecycle()
    val steps by imageVm.steps.collectAsStateWithLifecycle()
    val seed by imageVm.seed.collectAsStateWithLifecycle()
    val downloads by imageVm.downloads.collectAsStateWithLifecycle()
    val gallery by imageVm.gallery.collectAsStateWithLifecycle()
    val model = imageVm.catalog.firstOrNull { it.id == modelId } ?: imageVm.catalog.first()
    val download = downloads[model.id] ?: if (imageVm.isDownloaded(model)) DownloadState.Downloaded else DownloadState.NotDownloaded
    val progress = rememberGenerationProgress(state)
    val liveEta = rememberLiveEta(state)
    val scroll = rememberScrollState()
    val haptics = rememberHaptics()
    val heroVisible = state is ImageGenUiState.Loading || state is ImageGenUiState.Generating ||
        state is ImageGenUiState.Done || state is ImageGenUiState.Error

    // A new run brings the canvas into view; the composer stays docked underneath.
    val busy = state is ImageGenUiState.Loading || state is ImageGenUiState.Generating
    LaunchedEffect(busy) { if (busy) scroll.animateScrollTo(0) }

    Column(Modifier.fillMaxSize()) {
        SheetHeader(
            S.create, Lucide.ArrowLeft, onLeading = { appVm.back() }, leadingDescription = S.back,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            trailing = { GhostIconButton(Lucide.Images, S.gallery, onClick = { appVm.navigate(Screen.GALLERY) }, tint = Ink.I100, iconSize = 19.dp) },
        )
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal = 16.dp)) {
            AnimatedVisibility(
                heroVisible,
                enter = expandVertically(defaultSpatialSpec()) + fadeIn(defaultEffectsSpec()),
                exit = shrinkVertically(defaultSpatialSpec()) + fadeOut(defaultEffectsSpec()),
            ) {
                // Capped so a landscape phone does not get a screen-tall canvas.
                Box(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), contentAlignment = Alignment.TopCenter) {
                    GenerationCard(
                        state, progress, liveEta, model.displayName, size.px, sharedScope, animatedScope,
                        onOpen = onOpen, onSave = actions.save, onShare = actions.share, onAttach = actions.attach,
                        onAgain = { imageVm.regenerate(it) },
                        onRetry = { imageVm.generate() },
                        onDismissError = { imageVm.reset() },
                        modifier = Modifier.widthIn(max = 440.dp),
                    )
                }
            }
            SectionHeader(S.imageModel)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                imageVm.catalog.forEach { m ->
                    val dl = downloads[m.id] ?: if (imageVm.isDownloaded(m)) DownloadState.Downloaded else DownloadState.NotDownloaded
                    ImageModelCard(
                        m, selected = m.id == modelId, download = dl,
                        etaSeconds = imageVm.estimateSeconds(m.id, size, if (m.id == modelId) steps else null),
                        onSelect = { imageVm.selectModel(m.id) },
                        onDownload = { onBeforeDownload(); imageVm.download(m.id) },
                        onCancel = { imageVm.cancelDownload(m.id) },
                        onDiscard = { appVm.discardPartial(m.id) },
                    )
                }
            }
            SectionHeader(S.options)
            OptionsCard(
                model, size, steps, seed, lastSeed = gallery.firstOrNull()?.seed,
                onSize = { haptics.tap(); imageVm.setSize(it) },
                onSteps = { haptics.tap(); imageVm.setSteps(it) },
                onSeed = { haptics.tap(); imageVm.setSeed(it) },
            )
            Spacer(Modifier.height(24.dp))
        }
        // The one gradient the language allows (6.2): the list fades under the docked composer.
        Box(Modifier.fillMaxWidth().height(24.dp).background(Brush.verticalGradient(0f to Color.Transparent, 1f to Ink.Pitch)))
        Column(Modifier.background(Ink.Pitch).padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
            PromptComposer(prompt, onValueChange = imageVm::setPrompt)
            Spacer(Modifier.height(10.dp))
            GenerateCta(
                mode = ctaMode(state, download, model, etaSeconds = imageVm.estimateSeconds(), liveEta = liveEta, promptBlank = prompt.isBlank()),
                progress = progress,
                onGenerate = { imageVm.generate() },
                onCancel = { imageVm.cancel() },
                onDownload = { onBeforeDownload(); imageVm.download(model.id) },
            )
        }
    }
}

private fun ctaMode(state: ImageGenUiState, download: DownloadState, model: ImageCatalogModel, etaSeconds: Int, liveEta: Int, promptBlank: Boolean): CtaMode = when {
    state is ImageGenUiState.Loading -> CtaMode.Busy(S.loadingImageModel(model.displayName), loading = true)
    state is ImageGenUiState.Generating -> CtaMode.Busy(state.label(liveEta), loading = false)
    download is DownloadState.Downloaded -> CtaMode.Generate(etaSeconds, enabled = !promptBlank)
    download is DownloadState.Downloading -> CtaMode.Progress("${S.downloading} · ${(download.fraction * 100).toInt()} %")
    download is DownloadState.Queued || download is DownloadState.Verifying -> CtaMode.Progress(S.downloading)
    download is DownloadState.Paused -> CtaMode.Download("${S.resumeDownload} · ${DownloadService.fmt(download.totalBytes - download.downloadedBytes)}")
    download is DownloadState.Failed -> CtaMode.Download(S.retryDownload)
    else -> CtaMode.Download(S.downloadModel(model.displayName, DownloadService.fmt(ImageModelCatalog.bytesOnDisk(model))))
}

/** Size pill, the DreamShaper-only steps stepper, and the seed row (random / fixed with copy / reuse / shuffle). */
@Composable
private fun OptionsCard(
    model: ImageCatalogModel,
    size: ImageSizePreset,
    steps: Int?,
    seed: Long,
    lastSeed: Long?,
    onSize: (ImageSizePreset) -> Unit,
    onSteps: (Int?) -> Unit,
    onSeed: (Long) -> Unit,
) {
    val context = LocalContext.current
    SettingsCard {
        SettingRow(S.size, S.square(size.px)) {
            SegmentedPill(
                ImageSizePreset.entries.map { "${it.px}" }, size.ordinal,
                onSelect = { onSize(ImageSizePreset.entries[it]) },
                modifier = Modifier.width(150.dp),
            )
        }
        if (model.stepsAdjustable) {
            CardDivider()
            val cur = model.clampSteps(steps ?: model.defaultSteps)
            SettingRow(S.steps, "${model.spec.sampler.name} · ${model.minSteps}–${model.maxSteps}") {
                Row(Modifier.surfaceLow(CircleShape).padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    GhostIconButton(Lucide.Minus, S.fewerSteps, enabled = cur > model.minSteps, tint = Ink.I100, onClick = { onSteps(cur - 1) })
                    Text("$cur", style = Numeric.copy(fontSize = 15.sp), color = Ink.White, modifier = Modifier.width(28.dp), textAlign = TextAlign.Center)
                    GhostIconButton(Lucide.Plus, S.moreSteps, enabled = cur < model.maxSteps, tint = Ink.I100, onClick = { onSteps(cur + 1) })
                }
            }
        }
        CardDivider()
        val random = seed < 0
        SettingRow(S.seed, if (random) "${S.randomSeed} · ${S.randomSeedHint}" else "$seed") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!random) {
                    GhostIconButton(Lucide.Copy, S.copySeed, tint = Ink.I100, onClick = { copyToClipboard(context, "$seed") })
                    GhostIconButton(Lucide.Shuffle, S.useRandomSeed, tint = Ink.I100, onClick = { onSeed(-1) })
                } else if (lastSeed != null) {
                    GhostIconButton(Lucide.RotateCcw, S.reuseLastSeed, tint = Ink.I100, onClick = { onSeed(lastSeed) })
                }
            }
        }
    }
}

/** The prompt field, styled exactly like the chat composer card (inspiration 12): 16 sp, 16x14 padding, max 7 lines. */
@Composable
private fun PromptComposer(value: String, onValueChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = Typography.bodyLarge.copy(color = Ink.White, fontSize = 16.sp, lineHeight = 22.sp),
        cursorBrush = SolidColor(Ink.White),
        maxLines = 7,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Default),
        decorationBox = { inner ->
            Box(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                if (value.isEmpty()) Text(S.describeImage, color = Ink.I500, fontSize = 16.sp, lineHeight = 22.sp)
                inner()
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .surfaceRaised(RoundedCornerShape(Radii.composer))
            .heightIn(min = 52.dp, max = 170.dp),
    )
}

/** Save / Share / Attach for a generated image; shared by the Create hero, the gallery and the viewer. */
@Stable
class ImageActions(
    val save: (GenerationRecord) -> Unit,
    val share: (GenerationRecord) -> Unit,
    val attach: (GenerationRecord) -> Unit,
)

@Composable
fun rememberImageActions(imageVm: ImageGenViewModel, appVm: AppViewModel, chatVm: ChatViewModel): ImageActions {
    val context = LocalContext.current
    return remember(imageVm, appVm, chatVm, context) {
        ImageActions(
            save = { imageVm.saveToPhotos(it) },
            share = { record ->
                imageVm.share(record) { uri ->
                    val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(send, null))
                }
            },
            // "Use as attachment": a fresh chat with the PNG in the composer (6.1 flow 9 rules apply).
            attach = { record ->
                if (chatVm.gen.value.isBusy) appVm.notice(S.finishCurrentAnswer)
                else {
                    appVm.navigate(Screen.CHAT)
                    chatVm.newChat()
                    chatVm.attachFromUri(Uri.fromFile(File(record.path)))
                }
            },
        )
    }
}
