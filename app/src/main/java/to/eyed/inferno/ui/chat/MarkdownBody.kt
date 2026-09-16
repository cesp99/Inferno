package to.eyed.inferno.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Lucide
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.rememberMarkdownState
import kotlinx.coroutines.delay
import to.eyed.inferno.ui.S
import to.eyed.inferno.ui.components.Hairline
import to.eyed.inferno.ui.components.copyToClipboard
import to.eyed.inferno.ui.theme.Ink
import to.eyed.inferno.ui.theme.MonoBody
import to.eyed.inferno.ui.theme.Radii
import to.eyed.inferno.ui.theme.RowMeta
import to.eyed.inferno.ui.theme.Typography
import to.eyed.inferno.ui.theme.rememberHaptics
import to.eyed.inferno.ui.theme.whiteA

/**
 * Assistant markdown in the monochrome language: white headings, I100 body, mono code on I900 cards,
 * white underlined links (never blue). Streaming strategy: the source is split into stable block-level
 * chunks so only the growing last chunk re-parses while tokens arrive; `key(index)` keeps every earlier
 * chunk's parse alive. `TextDirection.Content` keeps RTL answers readable inside the LTR chrome.
 */
@Composable
fun MarkdownBody(text: String, modifier: Modifier = Modifier) {
    val chunks = remember(text) { MarkdownChunker.chunks(text) }
    SelectionContainer {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            chunks.forEachIndexed { index, chunk -> key(index) { MarkdownChunkView(chunk) } }
        }
    }
}

@Composable
private fun MarkdownChunkView(chunk: String) {
    val body = Typography.bodyLarge.copy(color = Ink.I100, textDirection = TextDirection.Content)
    // immediate parses the first composition synchronously (no blank frame) and retainState keeps the
    // previous tree on screen during the async re-parse; without both, each publish flashes the
    // library's placeholder, which reads as violent flicker at streaming rate.
    val state = rememberMarkdownState(content = chunk, retainState = true, immediate = true)
    Markdown(
        markdownState = state,
        colors = markdownColor(
            text = Ink.I100,
            codeBackground = Ink.I900,
            inlineCodeBackground = whiteA(0.10f),
            dividerColor = whiteA(0.10f),
            tableBackground = whiteA(0.03f),
        ),
        typography = markdownTypography(
            h1 = body.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, color = Ink.White, letterSpacing = (-0.2).sp),
            h2 = body.copy(fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold, color = Ink.White),
            h3 = body.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, color = Ink.White),
            h4 = body.copy(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, color = Ink.I100),
            h5 = body.copy(fontWeight = FontWeight.SemiBold, color = Ink.White),
            h6 = body.copy(fontWeight = FontWeight.Medium, color = Ink.I300),
            text = body,
            code = MonoBody.copy(color = Ink.I100),
            inlineCode = MonoBody.copy(color = Ink.White, fontSize = 13.sp),
            quote = body.copy(color = Ink.I300),
            paragraph = body,
            ordered = body,
            bullet = body,
            list = body,
            textLink = TextLinkStyles(
                style = SpanStyle(color = Ink.White, textDecoration = TextDecoration.Underline),
                pressedStyle = SpanStyle(color = Ink.I300, textDecoration = TextDecoration.Underline),
            ),
            table = body.copy(fontSize = 14.sp, lineHeight = 20.sp),
        ),
        dimens = markdownDimens(codeBackgroundCornerSize = Radii.row),
        components = markdownComponents(
            codeFence = { model ->
                MarkdownCodeFence(model.content, model.node) { code, language, _ -> MonoCodeBlock(code, language) }
            },
            codeBlock = { model ->
                MarkdownCodeBlock(model.content, model.node) { code, language, _ -> MonoCodeBlock(code, language) }
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Code card: language bar with a copy action, hairline seam, horizontally scrolling mono code. */
@Composable
fun MonoCodeBlock(code: String, language: String?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Radii.row)
    val context = LocalContext.current
    val trimmed = code.trimEnd('\n')
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Ink.I900)
            .border(1.dp, whiteA(0.08f), shape),
    ) {
        Row(
            Modifier.fillMaxWidth().background(whiteA(0.03f)).padding(start = 14.dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                language?.trim()?.takeIf { it.isNotBlank() } ?: "text",
                style = RowMeta.copy(fontFamily = MonoBody.fontFamily),
                color = Ink.I500,
                modifier = Modifier.weight(1f),
            )
            CopyChip(onCopy = { copyToClipboard(context, trimmed, "Code") })
        }
        Hairline()
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            Text(trimmed, style = MonoBody, color = Ink.I100, softWrap = false, modifier = Modifier.padding(14.dp))
        }
    }
}

/** "Copy" -> "Copied" for 1.6 s; the system clipboard overlay on API 33+ is the only other feedback. */
@Composable
private fun CopyChip(onCopy: () -> Unit) {
    var copied by remember { mutableStateOf(false) }
    val haptics = rememberHaptics()
    LaunchedEffect(copied) { if (copied) { delay(1_600); copied = false } }
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
            ) { haptics.tap(); onCopy(); copied = true }
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            if (copied) Lucide.Check else Lucide.Copy,
            contentDescription = S.copyCode,
            Modifier.size(12.dp),
            tint = if (copied) Ink.White else Ink.I500,
        )
        Text(if (copied) S.copied else S.copy, style = RowMeta, color = if (copied) Ink.White else Ink.I500)
    }
}

/**
 * Splits markdown into independently renderable chunks on blank lines, keeping fenced code blocks
 * (even unterminated, mid-stream) and list runs intact so numbering and fences never break across
 * chunks. Pure Kotlin: covered by MarkdownChunkerTest.
 */
object MarkdownChunker {
    private val fenceStart = Regex("^(`{3,}|~{3,})(.*)$")

    fun chunks(source: String): List<String> {
        val chunks = mutableListOf<String>()
        val current = mutableListOf<String>()
        // CommonMark fences: the opener is 3+ of one char (a backtick opener's info string may not contain a
        // backtick, so "```ls``` lists files" is inline code); the closer is the same char, at least as long, alone.
        // A plain toggle would flip on such lines and glue the rest of the answer into one re-parsed chunk.
        var fenceChar: Char? = null
        var fenceLen = 0
        val lines = source.split("\n")

        fun flush() {
            if (current.any { it.isNotBlank() }) chunks.add(current.joinToString("\n"))
            current.clear()
        }

        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()
            val m = fenceStart.matchEntire(trimmed)
            if (m != null) {
                val marker = m.groupValues[1]
                val rest = m.groupValues[2]
                if (fenceChar == null) {
                    if (marker[0] == '~' || !rest.contains('`')) {
                        fenceChar = marker[0]; fenceLen = marker.length
                        current.add(line); continue
                    }
                } else if (marker[0] == fenceChar && marker.length >= fenceLen && rest.isBlank()) {
                    fenceChar = null; fenceLen = 0
                    current.add(line); continue
                }
                // Otherwise ordinary content (inside a fence, or inline code text).
            }
            if (trimmed.isEmpty() && fenceChar == null) {
                val prevIsItem = current.lastOrNull()?.let(::isListItem) ?: false
                var nextIsItem = false
                for (i in (index + 1) until lines.size) {
                    if (lines[i].isNotBlank()) { nextIsItem = isListItem(lines[i]); break }
                }
                if (prevIsItem && nextIsItem) current.add(line) else flush()
                continue
            }
            current.add(line)
        }
        flush()
        return chunks
    }

    /** "- ", "* ", "+ ", "> " or "1." / "12)" style markers (an indented continuation counts too). */
    fun isListItem(line: String): Boolean {
        val t = line.trim()
        if (t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ") || t.startsWith("> ")) return true
        if (line.startsWith("  ") && t.isNotEmpty()) return true
        val mark = t.indexOfFirst { it == '.' || it == ')' }
        if (mark <= 0 || mark > 3) return false
        return t.substring(0, mark).all { it.isDigit() }
    }
}
