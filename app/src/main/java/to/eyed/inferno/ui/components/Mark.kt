@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package to.eyed.inferno.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.graphics.shapes.Morph
import to.eyed.inferno.R
import to.eyed.inferno.ui.theme.Ink
import to.eyed.inferno.ui.theme.animationsEnabled
import to.eyed.inferno.ui.theme.defaultSpatialSpec
import to.eyed.inferno.ui.theme.morphHalfCycleSpec
import to.eyed.inferno.ui.theme.morphLoopSpec

// The launcher foreground is a 108 dp adaptive canvas with the owl at 58 % of it;
// OwlMark over-scales the drawable so `size` is the owl itself.
private const val MarkFraction = 0.58f

/** The owl, tinted. Decorative: no semantics. */
@Composable
fun OwlMark(size: Dp, modifier: Modifier = Modifier, color: Color = Ink.White) {
    Box(modifier.size(size).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Image(
            painter = painterResource(R.mipmap.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.requiredSize(size / MarkFraction),
            colorFilter = ColorFilter.tint(color),
        )
    }
}

/**
 * Brand mark: the owl inside a white container whose outline morphs Circle -> Cookie9Sided.
 * The outline is drawn in the draw phase only (`drawWithCache` reads the progress), so it never
 * recomposes per frame. Motion is reserved for state: it breathes (7 s loop) only while
 * [active], rests as a static circle otherwise, and with [once] plays a single cycle on entry.
 * Static when animations are off.
 */
@Composable
fun MorphingMark(
    size: Dp,
    active: Boolean,
    modifier: Modifier = Modifier,
    once: Boolean = false,
    color: Color = Ink.White,
    owlColor: Color = Ink.Pitch,
) {
    val animations = animationsEnabled()
    val progress = remember { Animatable(0f) }
    var playedOnce by remember { mutableStateOf(false) }
    val settle = defaultSpatialSpec<Float>()
    LaunchedEffect(active, once, animations) {
        when {
            !animations -> progress.snapTo(0f)
            active -> progress.animateTo(1f, morphLoopSpec()) // runs until `active` flips
            once && !playedOnce -> {
                playedOnce = true
                progress.animateTo(1f, morphHalfCycleSpec())
                progress.animateTo(0f, morphHalfCycleSpec())
            }
            else -> progress.animateTo(0f, settle)
        }
    }
    val morph = remember { Morph(MaterialShapes.Circle, MaterialShapes.Cookie9Sided) }
    Box(
        modifier
            .size(size)
            .clearAndSetSemantics {}
            .drawWithCache {
                val path = Path()
                onDrawBehind {
                    val p = progress.value
                    // toPath rewinds and refills `path`: no allocation per frame. The polygons are
                    // normalized to unit space, so scale up from the origin and rotate about (0.5, 0.5).
                    morph.toPath(progress = p, path = path)
                    scale(this.size.width, this.size.height, pivot = Offset.Zero) {
                        rotate(degrees = p * 20f, pivot = Offset(0.5f, 0.5f)) {
                            drawPath(path, color)
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        OwlMark(size * 0.56f, color = owlColor)
    }
}
