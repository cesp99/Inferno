package to.eyed.inferno.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.NotebookPen
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.RefreshCw
import to.eyed.inferno.data.Attachment
import to.eyed.inferno.data.ChatMessage
import to.eyed.inferno.data.ChatRepository
import to.eyed.inferno.data.MessageStats
import to.eyed.inferno.ui.S
import to.eyed.inferno.ui.components.GhostIconButton
import to.eyed.inferno.ui.components.GlassMenu
import to.eyed.inferno.ui.components.Hairline
import to.eyed.inferno.ui.components.MenuActionRow
import to.eyed.inferno.ui.components.copyToClipboard
import to.eyed.inferno.ui.components.surfaceHigh
import to.eyed.inferno.ui.components.surfaceLow
import to.eyed.inferno.ui.theme.Ink
import to.eyed.inferno.ui.theme.Meta
import to.eyed.inferno.ui.theme.Radii
import to.eyed.inferno.ui.theme.RowMeta
import to.eyed.inferno.ui.theme.Typography
import to.eyed.inferno.ui.theme.defaultEffectsSpec
import to.eyed.inferno.ui.theme.defaultSpatialSpec
import to.eyed.inferno.ui.theme.fastSpatialSpec
import to.eyed.inferno.ui.theme.rememberHaptics
import to.eyed.inferno.vm.GenState
import to.eyed.inferno.vm.isBusy
import java.util.Locale

/** Readable ceiling for the conversation column on wide windows. */
val ChatContentMaxWidth = 800.dp

/**
 * Which developer surfaces the list may draw (SettingsState.dev* readers, all false for a normal user): the
 * meta line under the latest answer, the Details action, and the live token counter while streaming.
 */
data class ChatDevFlags(val generationStats: Boolean = false, val turnDetails: Boolean = false, val tokenCounter: Boolean = false)

/** What the list shows for the latest turn while it is in flight. */
private sealed interface Live {
    data object None : Live
    /** The last persisted assistant row is the one streaming: draw it with the live text and a caret. */
    data class Row(val id: String) : Live
    /** No assistant row yet (prefill / thinking / first tokens before Room catches up): an extra item at the bottom. */
    data object Item : Live
}

private fun liveFor(messages: List<ChatMessage>, gen: GenState): Live {
    if (!gen.isBusy || gen is GenState.Queued) return Live.None
    val last = messages.lastOrNull()
    return if (last != null && last.role == ChatRepository.ROLE_ASSISTANT && gen is GenState.Streaming && gen.text.isNotBlank()) Live.Row(last.id) else Live.Item
}

/**
 * Reversed list: index 0 sits at the visual bottom so the streaming turn stays pinned with no scroll
 * math. Keys are message ids; the width cap is applied through content padding (not a narrower list)
 * so scroll gestures keep working across the whole pane.
 */
@Composable
fun SharedTransitionScope.MessagesList(
    messages: List<ChatMessage>,
    gen: GenState,
    listState: LazyListState,
    trimmedBefore: Int,
    announceId: String?,
    imageScope: AnimatedVisibilityScope,
    onRegenerate: () -> Unit,
    onOpenImage: (Attachment) -> Unit,
    onOpenTurnDetails: (ChatMessage) -> Unit,
    onEdit: (ChatMessage) -> Unit,
    modifier: Modifier = Modifier,
    /** Height of the composer overlaid on the list, so the last turn can always scroll clear of it. */
    bottomInset: Dp = 132.dp,
    dev: ChatDevFlags = ChatDevFlags(),
) {
    val live = liveFor(messages, gen)
    val liveMeta = (gen as? GenState.Streaming)?.takeIf { dev.tokenCounter && it.tokens > 0 }?.let { S.liveTokens(it.tokens, String.format(Locale.US, "%.1f", it.tokPerSec)) }
    val lastAssistantId = messages.lastOrNull { it.role == ChatRepository.ROLE_ASSISTANT }?.id
    BoxWithConstraints(modifier) {
        val sidePad = ((maxWidth - ChatContentMaxWidth) / 2).coerceAtLeast(0.dp) + 16.dp
        val bubbleMax = if (maxWidth >= 600.dp) 460.dp else 300.dp
        // Placement springs make a send glide; while an answer streams the live item grows every frame, and
        // a lagging spring would drag the rows above it over the new text, so they snap instead.
        val placement: FiniteAnimationSpec<IntOffset>? = if (gen.isBusy) null else defaultSpatialSpec()
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = sidePad, end = sidePad, top = 12.dp, bottom = bottomInset),
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.Bottom),
        ) {
            if (live is Live.Item) item(key = "live", contentType = "live") {
                if (gen is GenState.Streaming && gen.text.isNotBlank()) {
                    AssistantMessage(
                        text = gen.text, reasoning = gen.reasoning, thinkingMs = gen.thinkingMs, streaming = true,
                        stats = null, showActions = false, showRegenerate = false, announce = false,
                        onRegenerate = {}, onDetails = {}, liveMeta = liveMeta,
                    )
                } else ThinkingIndicator(gen)
            }
            // Summary rows are appended after the turns they cover; they are drawn at their compaction point instead
            // (right below the last covered message), carried-over ones at the very top.
            val summaries = messages.filter { it.isSummary }
            val turns = messages.filter { !it.isSummary }
            val anchored = summaries.groupBy { it.compactedThrough }
            for (i in turns.indices.reversed()) {
                val m = turns[i]
                anchored[m.orderIndex]?.forEach { sm -> item(key = sm.id, contentType = "summary") { SummaryCard(sm, Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = placement)) } }
                item(key = m.id, contentType = m.role) {
                    if (m.role == ChatRepository.ROLE_USER) {
                        UserBubble(m, bubbleMax, imageScope, onOpenImage, onEdit, Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = placement))
                    } else {
                        val streamingRow = (live as? Live.Row)?.id == m.id
                        val s = gen as? GenState.Streaming
                        AssistantMessage(
                            text = if (streamingRow && s != null) s.text else m.content,
                            reasoning = if (streamingRow && s != null) s.reasoning else m.thinking.orEmpty(),
                            thinkingMs = if (streamingRow && s != null) s.thinkingMs else 0L,
                            streaming = streamingRow,
                            stats = m.stats,
                            showActions = !gen.isBusy,
                            showRegenerate = m.id == lastAssistantId,
                            showMeta = m.id == lastAssistantId && !streamingRow,
                            announce = m.id == announceId,
                            onRegenerate = onRegenerate,
                            onDetails = { onOpenTurnDetails(m) },
                            modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = placement),
                            showDetails = dev.turnDetails,
                            techMeta = dev.generationStats,
                            liveMeta = if (streamingRow) liveMeta else null,
                        )
                    }
                }
                if (trimmedBefore > 0 && m.orderIndex == trimmedBefore) item(key = "trimmed", contentType = "trimmed") { TrimmedDivider() }
            }
            val turnIndexes = turns.map { it.orderIndex }.toSet()
            summaries.filter { it.compactedThrough !in turnIndexes }.forEach { sm ->
                item(key = sm.id, contentType = "summary") { SummaryCard(sm, Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = placement)) }
            }
        }
    }
}

/**
 * Collapsed "Summary of earlier messages" card at a compaction point (ContextPolicy.COMPACT): the model's own notes
 * on everything above it. Tap to read; "Compacted N messages" says how much history it stands for.
 */
@Composable
private fun SummaryCard(msg: ChatMessage, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(msg.id) { mutableStateOf(false) }
    val haptics = rememberHaptics()
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, fastSpatialSpec(), label = "summaryChevron")
    val subtitle = if (msg.compactedThrough < 0) S.carriedOverSummary else S.compactedMessages(msg.compactedCount)
    Column(
        modifier
            .fillMaxWidth()
            .surfaceLow(RoundedCornerShape(Radii.row))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) { haptics.tap(); expanded = !expanded }
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Lucide.NotebookPen, null, Modifier.size(16.dp), tint = Ink.I300)
            Column(Modifier.weight(1f)) {
                Text(S.summaryOfEarlier, style = Typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = Ink.I100)
                Text(subtitle, style = RowMeta, color = Ink.I500)
            }
            Icon(Lucide.ChevronDown, null, Modifier.size(16.dp).graphicsLayer { rotationZ = rotation }, tint = Ink.I500)
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(defaultSpatialSpec()) + fadeIn(defaultEffectsSpec()),
            exit = shrinkVertically(defaultSpatialSpec()) + fadeOut(defaultEffectsSpec()),
        ) {
            Box(Modifier.padding(top = 10.dp)) { MarkdownBody(msg.content) }
        }
    }
}

/** Right-aligned SurfaceHigh bubble; images above the text open the viewer (shared bounds). Long-press: Edit / Copy. */
@Composable
private fun SharedTransitionScope.UserBubble(
    msg: ChatMessage,
    maxBubbleWidth: Dp,
    imageScope: AnimatedVisibilityScope,
    onOpenImage: (Attachment) -> Unit,
    onEdit: (ChatMessage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var menu by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        if (msg.images.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                msg.images.forEach { att ->
                    AsyncImage(
                        model = att.thumbPath,
                        contentDescription = S.attachedImage,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(160.dp)
                            .sharedBounds(rememberSharedContentState(imageSharedKey(att)), imageScope)
                            .clip(RoundedCornerShape(Radii.row))
                            .combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Image,
                                onClick = { onOpenImage(att) },
                            ),
                    )
                }
            }
            if (msg.content.isNotBlank()) Spacer(Modifier.height(8.dp))
        }
        if (msg.content.isNotBlank()) Box {
            Box(
                Modifier
                    .widthIn(max = maxBubbleWidth)
                    .surfaceHigh(RoundedCornerShape(Radii.card))
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                        onLongClick = { haptics.longPress(); menu = true },
                    )
                    .padding(horizontal = 16.dp, vertical = 11.dp),
            ) {
                // No SelectionContainer here: its long-press would fight the bubble menu (Copy lives in the menu).
                Text(msg.content, style = Typography.bodyLarge.copy(textDirection = TextDirection.Content), color = Ink.White)
            }
            GlassMenu(expanded = menu, onDismiss = { menu = false }, minWidth = 160.dp) {
                MenuActionRow(Lucide.Pencil, S.edit) { menu = false; onEdit(msg) }
                MenuActionRow(Lucide.Copy, S.copy) { menu = false; copyToClipboard(context, msg.content, "Inferno message") }
            }
        }
    }
}

/**
 * Assistant turn, no bubble: reasoning panel, markdown, caret while streaming, then the action row
 * (fades in once the engine is idle) and - on the latest turn only - the meta line from MessageStats.
 */
@Composable
private fun AssistantMessage(
    text: String,
    reasoning: String,
    thinkingMs: Long,
    streaming: Boolean,
    stats: MessageStats?,
    showActions: Boolean,
    showRegenerate: Boolean,
    announce: Boolean,
    onRegenerate: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
    showMeta: Boolean = false,
    /** Developer mode: the Details action, the numbers in the meta line, and the live counter under the caret. */
    showDetails: Boolean = false,
    techMeta: Boolean = false,
    liveMeta: String? = null,
) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    Column(modifier.fillMaxWidth()) {
        if (reasoning.isNotBlank()) {
            ReasoningPanel(reasoning, live = false, thinkingMs = thinkingMs.takeIf { it > 0 } ?: 0L)
            Spacer(Modifier.height(10.dp))
        }
        if (text.isNotBlank()) MarkdownBody(text)
        if (streaming) Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BlinkingCaret()
            if (liveMeta != null) Text(liveMeta, style = Meta, color = Ink.I500)
        }
        // TalkBack hears "Answer complete" exactly once, on the Done transition - never per token.
        if (announce) Box(Modifier.size(1.dp).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = S.answerComplete })
        AnimatedVisibility(showActions, enter = fadeIn(defaultEffectsSpec()), exit = fadeOut(defaultEffectsSpec())) {
            Column {
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    GhostIconButton(Lucide.Copy, S.copyMessage, onClick = { haptics.tap(); copyToClipboard(context, text, "Inferno message") }, size = 32.dp, iconSize = 15.dp, tint = Ink.I500)
                    if (showRegenerate) GhostIconButton(Lucide.RefreshCw, S.regenerate, onClick = { haptics.tap(); onRegenerate() }, size = 32.dp, iconSize = 15.dp, tint = Ink.I500)
                    if (stats != null && showDetails) GhostIconButton(Lucide.Info, S.details, onClick = { haptics.tap(); onDetails() }, size = 32.dp, iconSize = 15.dp, tint = Ink.I500)
                    if (showMeta && stats != null) metaLine(stats, techMeta)?.let { meta ->
                        Spacer(Modifier.size(6.dp))
                        Text(meta, style = Meta, color = Ink.I500)
                    }
                }
            }
        }
    }
}

/**
 * Developer mode: "12.4 tok/s · 843 tokens · 8.1 s · prefill 120 ms · ctx 1,204/8,192 · image 2.1 s", plus
 * "· stopped" / "· interrupted". A normal user ([tech] false) only gets the stopped / interrupted / failed word,
 * so a clean answer has no line at all. Null when there is nothing to say.
 */
internal fun metaLine(s: MessageStats, tech: Boolean = true): String? {
    val parts = mutableListOf<String>()
    if (tech && s.generatedTokens > 0 && s.decodeMs > 0) {
        parts += String.format(Locale.US, "%.1f tok/s", s.decodeTps)
        parts += "${String.format(Locale.US, "%,d", s.generatedTokens)} tokens"
        parts += formatDuration(s.decodeMs + s.prefillMs + s.imageEncodeMs)
        if (s.prefillMs > 0) parts += "prefill ${String.format(Locale.US, "%,d", s.prefillMs)} ms"
        if (s.nCtx > 0) parts += "ctx ${String.format(Locale.US, "%,d", s.kvUsedTokens)}/${String.format(Locale.US, "%,d", s.nCtx)}"
        if (s.imageEncodeMs > 0) parts += "image ${formatDuration(s.imageEncodeMs)}"
    }
    when {
        s.finishReason == null -> parts += S.interrupted
        s.finishReason == "CANCELLED" -> parts += S.stopped
        s.finishReason == "ERROR" -> parts += S.failed.lowercase()
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** Hairline row marking where the context window now starts (shown only when trimmedBefore > 0). */
@Composable
private fun TrimmedDivider() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Hairline(Modifier.weight(1f))
        Text(S.olderOutsideContext, style = RowMeta, color = Ink.I500)
        Hairline(Modifier.weight(1f))
    }
}
