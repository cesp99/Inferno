package to.eyed.inferno.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.Cpu
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.PanelLeft
import com.composables.icons.lucide.SquarePen
import com.composables.icons.lucide.Zap
import to.eyed.inferno.engine.EngineState
import to.eyed.inferno.ui.S
import to.eyed.inferno.ui.components.GhostIconButton
import to.eyed.inferno.ui.components.GlassMenu
import to.eyed.inferno.ui.components.Hairline
import to.eyed.inferno.ui.theme.ChipLabel
import to.eyed.inferno.ui.theme.Ink
import to.eyed.inferno.ui.theme.Numeric
import to.eyed.inferno.ui.theme.RowMeta
import to.eyed.inferno.ui.theme.fastEffectsSpec
import to.eyed.inferno.ui.theme.pressScale
import to.eyed.inferno.ui.theme.rememberHaptics
import to.eyed.inferno.vm.ChatViewModel

/**
 * Minimal top bar: sidebar toggle, the model chip in the centre (the model is a first-class,
 * slow-to-switch object on a local app, so it lives here rather than in the composer), new chat on
 * the right. A hairline appears under the row only once the list is scrolled.
 */
@Composable
fun TopNav(
    engine: EngineState,
    contextUsage: ChatViewModel.ContextUsage,
    resolvedCtxLabel: String?,
    scrolled: Boolean,
    showDrawerToggle: Boolean,
    selectedModelName: String?,
    onOpenDrawer: () -> Unit,
    onOpenModelSheet: () -> Unit,
    onNewChat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.background(Ink.Pitch)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            if (showDrawerToggle) {
                GhostIconButton(Lucide.PanelLeft, S.openSidebar, onOpenDrawer, size = 40.dp, iconSize = 20.dp, tint = Ink.I100, modifier = Modifier.align(Alignment.CenterStart))
            }
            ModelChip(engine, contextUsage, resolvedCtxLabel, selectedModelName, onOpenModelSheet, Modifier.align(Alignment.Center).padding(horizontal = 48.dp))
            GhostIconButton(Lucide.SquarePen, S.newChat, onNewChat, size = 40.dp, iconSize = 20.dp, tint = Ink.I100, modifier = Modifier.align(Alignment.CenterEnd))
        }
        AnimatedVisibility(scrolled, enter = fadeIn(fastEffectsSpec()), exit = fadeOut(fastEffectsSpec())) { Hairline() }
    }
}

/** SurfaceHigh pill whose content follows the engine state; long-press reveals the resolved context label. */
@Composable
private fun ModelChip(
    engine: EngineState,
    usage: ChatViewModel.ContextUsage,
    resolvedCtxLabel: String?,
    selectedModelName: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    var info by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier
                .minimumInteractiveComponentSize()
                .pressScale(interaction, 0.96f)
                .clip(CircleShape)
                .background(Ink.SurfaceHigh)
                .combinedClickable(
                    interactionSource = interaction, indication = null, role = Role.Button,
                    onClick = onClick,
                    onLongClick = if (resolvedCtxLabel != null && engine !is EngineState.Idle) ({ haptics.longPress(); info = true }) else null,
                )
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            val fade = fastEffectsSpec<Float>()
            val chip = chipState(engine, selectedModelName)
            // Keyed on the kind only: the loading percentage ticks every few hundred ms and must not restart
            // the crossfade; its text is tabular so the pill never jitters.
            AnimatedContent(
                targetState = chip.kind,
                transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) },
                label = "modelChip",
            ) { kind ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    when (kind) {
                        ChipKind.IDLE -> Icon(Lucide.Cpu, null, Modifier.size(14.dp), tint = Ink.I500)
                        ChipKind.LOADING -> LoadingIndicator(Modifier.size(16.dp), color = Ink.White)
                        ChipKind.SUSPENDED -> Icon(Lucide.Zap, null, Modifier.size(14.dp), tint = Ink.I500)
                        ChipKind.ERROR -> Icon(Lucide.CircleAlert, null, Modifier.size(14.dp), tint = Ink.I300)
                        ChipKind.READY -> Unit
                    }
                    Text(chip.text, style = ChipLabel, color = chip.color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
                    if (kind == ChipKind.LOADING) (engine as? EngineState.Loading)?.progress?.let { p ->
                        Text("${(p * 100).toInt()} %", style = Numeric.copy(fontSize = 13.sp, lineHeight = 18.sp), color = Ink.I500, maxLines = 1, modifier = Modifier.widthIn(min = 32.dp))
                    }
                    if (kind == ChipKind.READY && usage.nCtx > 0) ContextRing(usage.fraction, 14.dp, approximate = usage.approximate)
                }
            }
        }
        GlassMenu(expanded = info, onDismiss = { info = false }, minWidth = 120.dp) {
            Text(resolvedCtxLabel ?: "", style = RowMeta, color = Ink.I300, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        }
    }
}

private enum class ChipKind { IDLE, LOADING, READY, SUSPENDED, ERROR }
private data class ChipState(val kind: ChipKind, val text: String, val color: androidx.compose.ui.graphics.Color)

/** Idle with a selected local model (e.g. after an image generation released the weights, 12.5): its name, dimmed - the next send reloads it. */
private fun chipState(s: EngineState, selectedModelName: String?): ChipState = when (s) {
    is EngineState.Idle -> ChipState(ChipKind.IDLE, selectedModelName ?: S.chooseModel, Ink.I500)
    is EngineState.Loading -> ChipState(ChipKind.LOADING, loadingText(s), Ink.I300)
    is EngineState.Ready -> ChipState(ChipKind.READY, s.loaded.model.displayName, Ink.I100)
    is EngineState.Generating -> ChipState(ChipKind.READY, s.loaded.model.displayName, Ink.I100)
    is EngineState.Suspended -> ChipState(ChipKind.SUSPENDED, s.loaded.model.displayName, Ink.I500)
    is EngineState.Error -> ChipState(ChipKind.ERROR, S.failed, Ink.I300)
}

/** "Reading weights" / "Calibrating…": the phase names come from the engine verbatim; the percentage is a separate tabular text. */
private fun loadingText(s: EngineState.Loading): String {
    val phase = s.phase.ifBlank { S.loading }
    return if (phase.endsWith("…") || s.progress != null) phase else "$phase…"
}
