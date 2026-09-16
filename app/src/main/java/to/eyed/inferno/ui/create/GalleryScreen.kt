package to.eyed.inferno.ui.create

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ImageDown
import com.composables.icons.lucide.Images
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Paperclip
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.X
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import to.eyed.inferno.imagegen.GenerationRecord
import to.eyed.inferno.imagegen.ImageGenUiState
import to.eyed.inferno.models.ImageModelCatalog
import to.eyed.inferno.ui.S
import to.eyed.inferno.ui.components.ConfirmTwice
import to.eyed.inferno.ui.components.ErrorNotice
import to.eyed.inferno.ui.components.GhostIconButton
import to.eyed.inferno.ui.components.GlassButton
import to.eyed.inferno.ui.components.IconCircle
import to.eyed.inferno.ui.components.SheetHeader
import to.eyed.inferno.ui.theme.Ink
import to.eyed.inferno.ui.theme.Radii
import to.eyed.inferno.ui.theme.RowMeta
import to.eyed.inferno.ui.theme.Typography
import to.eyed.inferno.ui.theme.defaultEffectsSpec
import to.eyed.inferno.ui.theme.fastEffectsSpec
import to.eyed.inferno.ui.theme.fastSpatialSpec
import to.eyed.inferno.ui.theme.rememberHaptics
import to.eyed.inferno.vm.AppViewModel
import to.eyed.inferno.vm.ChatViewModel
import to.eyed.inferno.vm.ImageGenViewModel

/**
 * Past generations as a 2-column grid. Long-press = prompt overlay with a two-tap delete; tap = the
 * full-screen viewer, the thumbnail morphing into it through shared bounds. The open image id is saveable,
 * so rotation and process death land back in the viewer.
 */
@Composable
fun GalleryScreen(imageVm: ImageGenViewModel, appVm: AppViewModel, chatVm: ChatViewModel) {
    val gallery by imageVm.gallery.collectAsStateWithLifecycle()
    val notice by imageVm.notice.collectAsStateWithLifecycle()
    var viewerId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val viewing = gallery.firstOrNull { it.id == viewerId }
    val actions = rememberImageActions(imageVm, appVm, chatVm)
    val haptics = rememberHaptics()
    val fade = defaultEffectsSpec<Float>()
    // Deleting the image the Create hero still shows would leave a dead path behind it.
    val delete: (String) -> Unit = { id ->
        if ((imageVm.state.value as? ImageGenUiState.Done)?.record?.id == id) imageVm.reset()
        imageVm.deleteImage(id)
    }

    Box(Modifier.fillMaxSize()) {
        SharedTransitionLayout(Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = viewing,
                transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) },
                label = "galleryViewer",
            ) { open ->
                if (open != null) {
                    ImageViewer(
                        open, this@SharedTransitionLayout, this@AnimatedContent,
                        onClose = { viewerId = null },
                        onSave = actions.save, onShare = actions.share, onAttach = actions.attach,
                        onDelete = { viewerId = null; delete(it.id) },
                    )
                } else {
                    Column(Modifier.fillMaxSize()) {
                        SheetHeader(
                            S.gallery, Lucide.ArrowLeft, onLeading = { appVm.back() }, leadingDescription = S.back,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        if (gallery.isEmpty()) {
                            EmptyGallery(onCreate = { appVm.back() })
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(2),
                                state = rememberLazyGridState(),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                items(gallery, key = { it.id }) { r ->
                                    GalleryTile(
                                        r,
                                        selected = selectedId == r.id,
                                        sharedScope = this@SharedTransitionLayout,
                                        animatedScope = this@AnimatedContent,
                                        onOpen = { selectedId = null; viewerId = r.id },
                                        onLongPress = { haptics.longPress(); selectedId = if (selectedId == r.id) null else r.id },
                                        onDismiss = { selectedId = null },
                                        onDelete = { selectedId = null; delete(r.id) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        TransientNotice(notice, onDismiss = imageVm::dismissNotice, modifier = Modifier.align(Alignment.TopCenter))
    }
}

@Composable
private fun EmptyGallery(onCreate: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconCircle(Lucide.Images, size = 56.dp, iconSize = 24.dp, tint = Ink.I300, fill = Ink.SurfaceLow)
        Spacer(Modifier.height(18.dp))
        Text(S.noImagesYet, style = Typography.titleMedium, color = Ink.I100)
        Spacer(Modifier.height(4.dp))
        Text(S.noImagesHint, style = Typography.bodyMedium, color = Ink.I500, textAlign = TextAlign.Center)
        Spacer(Modifier.height(22.dp))
        GlassButton(S.createImage, onClick = onCreate, icon = Lucide.Sparkles)
    }
}

/** One square: the thumb, plus (after a long-press) a scrim with the prompt, the meta and a two-tap Delete. */
@Composable
private fun GalleryTile(
    record: GenerationRecord,
    selected: Boolean,
    sharedScope: SharedTransitionScope,
    animatedScope: AnimatedVisibilityScope,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    val shape = RoundedCornerShape(Radii.row)
    val fade = fastEffectsSpec<Float>()
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(shape)
            .background(Ink.I850)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = S.openImage,
                onLongClick = onLongPress,
                onClick = { if (selected) onDismiss() else onOpen() },
            ),
    ) {
        with(sharedScope) {
            AsyncImage(
                record.thumbPath ?: record.path,
                record.prompt,
                Modifier
                    .fillMaxSize()
                    .sharedBounds(
                        rememberSharedContentState(generatedImageKey(record)),
                        animatedScope,
                        clipInOverlayDuringTransition = OverlayClip(shape),
                    ),
                contentScale = ContentScale.Crop,
            )
        }
        AnimatedVisibility(selected, enter = fadeIn(fade), exit = fadeOut(fade)) {
            Column(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.74f)).padding(12.dp)) {
                Text(
                    record.prompt,
                    style = Typography.bodyMedium,
                    color = Ink.White,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${record.width} px · ${S.duration(record.totalMs)}",
                        style = RowMeta, color = Ink.I300, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    ConfirmTwice(S.delete, onConfirm = onDelete)
                }
            }
        }
    }
}

/**
 * Full-screen viewer for a generated image: fit-scaled on black, the prompt and meta under it, Save / Share /
 * Attach (and Delete from the gallery). Predictive back drives the image (scale + fade) before closing; the
 * shared-bounds morph then runs back into the tile.
 */
@Composable
fun ImageViewer(
    record: GenerationRecord,
    sharedScope: SharedTransitionScope,
    animatedScope: AnimatedVisibilityScope,
    onClose: () -> Unit,
    onSave: (GenerationRecord) -> Unit,
    onShare: (GenerationRecord) -> Unit,
    onAttach: (GenerationRecord) -> Unit,
    onDelete: ((GenerationRecord) -> Unit)? = null,
) {
    val back = remember { Animatable(0f) }
    val spring = fastSpatialSpec<Float>()
    PredictiveBackHandler { progress ->
        try {
            progress.collect { back.snapTo(it.progress) }
            onClose()
        } catch (e: CancellationException) {
            back.animateTo(0f, spring)
        }
    }
    val modelName = ImageModelCatalog.byId(record.modelId)?.displayName ?: record.modelId
    Box(Modifier.fillMaxSize().background(Ink.Pitch)) {
        with(sharedScope) {
            AsyncImage(
                record.path,
                S.fullSizeImage,
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .aspectRatio(record.width.toFloat() / record.height.coerceAtLeast(1))
                    .graphicsLayer {
                        val p = back.value
                        scaleX = 1f - 0.15f * p; scaleY = 1f - 0.15f * p
                        alpha = 1f - 0.4f * p
                    }
                    .sharedBounds(rememberSharedContentState(generatedImageKey(record)), animatedScope),
                contentScale = ContentScale.Fit,
            )
        }
        Column(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - back.value }) {
            GhostIconButton(
                Lucide.X, S.close, onClick = onClose, size = 40.dp, iconSize = 20.dp, tint = Ink.I100,
                modifier = Modifier.padding(start = 8.dp, top = 8.dp),
            )
            Spacer(Modifier.weight(1f))
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
                Text(record.prompt, style = Typography.bodyMedium, color = Ink.I100, maxLines = 4, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(
                    "$modelName · ${S.square(record.width)} · seed ${record.seed} · ${S.duration(record.totalMs)}",
                    style = RowMeta, color = Ink.I500,
                )
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassButton(S.saveToPhotos, onClick = { onSave(record) }, icon = Lucide.ImageDown)
                        GlassButton(S.share, onClick = { onShare(record) }, icon = Lucide.Share2)
                        GlassButton(S.useAsAttachment, onClick = { onAttach(record) }, icon = Lucide.Paperclip)
                    }
                    if (onDelete != null) {
                        Spacer(Modifier.width(8.dp))
                        ConfirmTwice(S.delete, onConfirm = { onDelete(record) })
                    }
                }
            }
        }
    }
}

/** Top-centre notice that dismisses on tap or after four seconds ("Saved to Photos", "Describe the image first"). */
@Composable
fun TransientNotice(text: String?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(text) {
        if (text != null) { delay(4_000); onDismiss() }
    }
    AnimatedVisibility(text != null, modifier = modifier, enter = fadeIn(fastEffectsSpec()), exit = fadeOut(fastEffectsSpec())) {
        var shown by remember { mutableStateOf(text ?: "") }
        if (text != null) shown = text
        ErrorNotice(shown, onDismiss = onDismiss, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}
