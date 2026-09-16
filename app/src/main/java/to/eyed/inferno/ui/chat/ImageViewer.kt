package to.eyed.inferno.ui.chat

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import to.eyed.inferno.data.Attachment
import to.eyed.inferno.ui.S
import to.eyed.inferno.ui.components.GhostIconButton
import to.eyed.inferno.ui.theme.Ink
import to.eyed.inferno.ui.theme.Radii
import to.eyed.inferno.ui.theme.defaultEffectsSpec

/** Shared-element key for an attachment: the bubble thumbnail and the viewer both use it. */
fun imageSharedKey(att: Attachment) = "image-${att.id}"

/**
 * Full-bleed viewer that lives inside ChatRoot's SharedTransitionLayout (not a Dialog), so open,
 * close and the back gesture all morph the photo to and from its bubble thumbnail. [backProgress]
 * (0..1 from PredictiveBackHandler) shrinks the real layout bounds, so the shared-bounds morph that
 * follows a committed gesture starts from exactly where the user left the picture.
 */
@Composable
fun SharedTransitionScope.ImageViewer(
    att: Attachment,
    visibility: AnimatedVisibilityScope,
    backProgress: Float,
    onClose: () -> Unit,
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoomChange, panChange, _ ->
        zoom = (zoom * zoomChange).coerceIn(1f, 5f)
        pan = if (zoom > 1f) pan + panChange else Offset.Zero
    }
    val scrim by animateFloatAsState(1f - 0.6f * backProgress, defaultEffectsSpec(), label = "scrim")
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = scrim }
            .background(Color.Black)
            // The scrim swallows taps so the chat underneath never receives them (no click semantics: nothing to activate).
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = att.path,
            contentDescription = S.fullSizeImage,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize(1f - 0.2f * backProgress)
                .sharedBounds(rememberSharedContentState(imageSharedKey(att)), visibility)
                .clip(RoundedCornerShape(Radii.row * backProgress))
                .graphicsLayer {
                    scaleX = zoom; scaleY = zoom
                    translationX = pan.x; translationY = pan.y
                }
                .transformable(transform)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { if (zoom > 1f) { zoom = 1f; pan = Offset.Zero } else zoom = 2.5f },
                        onTap = { if (zoom <= 1f) onClose() },
                    )
                },
        )
        GhostIconButton(
            Lucide.X, S.closeImage, onClick = onClose,
            size = 40.dp, iconSize = 20.dp, tint = Ink.White,
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
        )
    }
}
