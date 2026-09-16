package to.eyed.inferno.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Lucide
import kotlinx.coroutines.delay
import to.eyed.inferno.ui.S
import to.eyed.inferno.ui.components.MorphingMark
import to.eyed.inferno.ui.components.QuietNotice
import to.eyed.inferno.ui.components.surfaceLow
import to.eyed.inferno.ui.theme.Ink
import to.eyed.inferno.ui.theme.Radii
import to.eyed.inferno.ui.theme.Typography
import to.eyed.inferno.ui.theme.animationsEnabled
import to.eyed.inferno.ui.theme.caretBlinkSpec
import to.eyed.inferno.ui.theme.defaultEffectsSpec
import to.eyed.inferno.ui.theme.defaultSpatialSpec
import to.eyed.inferno.ui.theme.fastSpatialSpec
import to.eyed.inferno.ui.theme.pulseSpec
import to.eyed.inferno.ui.theme.rememberHaptics
import to.eyed.inferno.vm.GenState
import java.util.Locale

// The small live pieces of a turn: the reasoning panel, the pre-token line, the dots and the caret.
// Every decorative piece is `clearAndSetSemantics {}`; every loop honours animationsEnabled().

/** Reasoning body: 13 sp with a slightly looser 19 sp leading than rows. */
private val ReasoningText = Typography.bodyMedium.copy(lineHeight = 19.sp)

/** "8.1 s" / "1 min 8 s" - the only two duration shapes in the app. */
fun formatDuration(ms: Long): String {
    val s = ms / 1000.0
    return if (s < 60) String.format(Locale.US, "%.1f s", s)
    else "${(s / 60).toInt()} min ${(s % 60).toInt()} s"
}

/**
 * Collapsible reasoning panel above the answer. Live: title "Thinking…" with dots and a window that
 * follows the tail (never truncated, so earlier thoughts never slide away). Done: "Thought for 12 s"
 * from the real thinkingMs, collapsed by default.
 */
@Composable
fun ReasoningPanel(reasoning: String, live: Boolean, thinkingMs: Long, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val haptics = rememberHaptics()
    val title = when {
        live -> S.thinking
        thinkingMs >= 1_000 -> S.thoughtFor(formatDuration(thinkingMs))
        else -> S.reasoning
    }
    val chevronSpec = fastSpatialSpec<Float>()
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, chevronSpec, label = "chev")
    Column(
        modifier
            .fillMaxWidth()
            .surfaceLow(RoundedCornerShape(Radii.row))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
            ) { haptics.tap(); expanded = !expanded }
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = Typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = Ink.I300)
            if (live) { Spacer(Modifier.width(8.dp)); PulsingDots() }
            Spacer(Modifier.weight(1f))
            Icon(Lucide.ChevronDown, null, Modifier.size(16.dp).graphicsLayer { rotationZ = rotation }, tint = Ink.I500)
        }
        AnimatedVisibility(
            visible = expanded || live,
            enter = expandVertically(defaultSpatialSpec()) + fadeIn(defaultEffectsSpec()),
            exit = shrinkVertically(defaultSpatialSpec()) + fadeOut(defaultEffectsSpec()),
        ) {
            if (live && !expanded) {
                val scroll = rememberScrollState()
                LaunchedEffect(reasoning.length) { scroll.scrollTo(scroll.maxValue) }
                Box(Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(max = 160.dp).verticalScroll(scroll)) {
                    Text(reasoning, style = ReasoningText, color = Ink.I500)
                }
            } else {
                Text(reasoning, style = ReasoningText, color = Ink.I500, modifier = Modifier.padding(top = 10.dp))
            }
        }
    }
}

/**
 * The single pre-token affordance in the list. Prefill: one 13 sp line after a 250 ms grace so a
 * cache-hit turn (~100 ms) shows nothing at all; the progress itself lives in the Stop button ring.
 * Thinking: the live reasoning panel. Streaming with no text yet: the loading indicator alone.
 */
@Composable
fun ThinkingIndicator(gen: GenState, modifier: Modifier = Modifier) {
    when (gen) {
        is GenState.Prefill -> {
            var shown by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { delay(250); shown = true }
            if (!shown) return
            val label = when {
                gen.encodingImage && gen.expectedMs > 0 -> "${S.readingImage} · ~${(gen.expectedMs + 999) / 1000} s"
                gen.encodingImage -> S.readingImage
                gen.total > 0 -> S.readingTokens(gen.total)
                else -> S.preparing
            }
            Row(modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, style = Typography.bodyMedium, color = Ink.I300)
                PulsingDots()
            }
        }
        is GenState.Thinking -> ReasoningPanel(gen.reasoning, live = true, thinkingMs = gen.thinkingMs, modifier = modifier)
        is GenState.Streaming -> if (gen.text.isBlank()) {
            Column(modifier) {
                if (gen.reasoning.isNotBlank()) ReasoningPanel(gen.reasoning, live = false, thinkingMs = gen.thinkingMs, modifier = Modifier.padding(bottom = 10.dp))
                LoadingIndicator(Modifier.size(24.dp), color = Ink.White)
            }
        }
        GenState.Trimming -> QuietNotice(S.trimmingOlder, modifier)
        GenState.Compacting -> CompactingIndicator(modifier)
        else -> Unit
    }
}

/** The breathing mark next to "Compacting…": the model is writing its own notes, not an answer (ContextPolicy.COMPACT). */
@Composable
fun CompactingIndicator(modifier: Modifier = Modifier) {
    Row(modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MorphingMark(28.dp, active = true)
        Column {
            Text(S.compacting, style = Typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = Ink.I100)
            Text(S.compactingDesc, style = Typography.bodySmall, color = Ink.I500)
        }
    }
}

/** Three 3 dp dots, 600 ms pulse staggered 200 ms; static at 70 % when animations are off. */
@Composable
fun PulsingDots(modifier: Modifier = Modifier) {
    val animations = animationsEnabled()
    val t = rememberInfiniteTransition(label = "dots")
    Row(modifier.clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) { i ->
            val alpha by t.animateFloat(
                initialValue = if (animations) 0.2f else 0.7f,
                targetValue = if (animations) 1f else 0.7f,
                animationSpec = pulseSpec(i * 200),
                label = "dot$i",
            )
            Box(Modifier.size(3.dp).graphicsLayer { this.alpha = alpha }.background(Ink.I500, CircleShape))
        }
    }
}

/** 2 x 16 dp white caret, 450 ms blink; solid when animations are off. */
@Composable
fun BlinkingCaret(modifier: Modifier = Modifier) {
    val animations = animationsEnabled()
    val t = rememberInfiniteTransition(label = "caret")
    val alpha by t.animateFloat(
        initialValue = 1f,
        targetValue = if (animations) 0.15f else 1f,
        animationSpec = caretBlinkSpec(),
        label = "caretAlpha",
    )
    Box(
        modifier
            .clearAndSetSemantics {}
            .size(width = 2.dp, height = 16.dp)
            .graphicsLayer { this.alpha = alpha }
            .background(Ink.White, CircleShape),
    )
}
