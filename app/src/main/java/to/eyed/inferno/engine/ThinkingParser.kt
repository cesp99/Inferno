package to.eyed.inferno.engine

/**
 * Streaming splitter: feeds raw deltas, emits (thinkingDelta, textDelta). Handles tags split across pieces.
 * [startInsideThinking] seeds the state for the case where the engine injected `ThinkingSpec.enablePrefix`
 * (e.g. "<think>\n"): the model's first tokens are then already reasoning although no open tag arrives in the stream.
 *
 * Rules: with null tags everything is visible. A stream that starts with an open tag (optionally after
 * whitespace) enters thinking; when seeded inside thinking an open tag arriving first is swallowed rather than
 * duplicated into the reasoning text. Leading whitespace after the close tag is trimmed once. Tag fragments at
 * the end of a piece are held back until the next piece disambiguates them; [flush] releases a dangling fragment
 * into the current channel at end of stream.
 */
class ThinkingParser(private val openTag: String?, private val closeTag: String?, startInsideThinking: Boolean = false) {
    private val enabled = !openTag.isNullOrEmpty() && !closeTag.isNullOrEmpty()

    var insideThinking: Boolean = enabled && startInsideThinking
        private set

    // Text not yet classified: either a possible tag prefix or leading whitespace that may precede a tag.
    private val pending = StringBuilder()
    // True until the first classified character: an open tag may still start the stream (after whitespace).
    private var atStart = true
    // True right after the close tag until the first non-whitespace visible character.
    private var trimVisibleLeading = false
    // Seeded parsers swallow one leading open tag; cleared once any reasoning text has been emitted.
    private var swallowOpenTag = enabled && startInsideThinking

    fun feed(piece: String): Pair<String, String> {
        if (!enabled) return "" to piece
        if (piece.isEmpty()) return "" to ""
        pending.append(piece)
        val thinking = StringBuilder()
        val visible = StringBuilder()
        process(thinking, visible, final = false)
        return thinking.toString() to visible.toString()
    }

    fun flush(): Pair<String, String> {
        if (!enabled || pending.isEmpty()) return "" to ""
        val thinking = StringBuilder()
        val visible = StringBuilder()
        process(thinking, visible, final = true)
        return thinking.toString() to visible.toString()
    }

    private fun process(thinking: StringBuilder, visible: StringBuilder, final: Boolean) {
        val open = openTag!!
        val close = closeTag!!
        while (pending.isNotEmpty()) {
            if (!insideThinking) {
                if (atStart || swallowOpenTag) {
                    // Leading whitespace + open tag => thinking (swallowed). Wait while the buffer could still become one.
                    val ws = leadingWhitespace(pending)
                    val rest = pending.substring(ws)
                    if (!final && (rest.isEmpty() || (rest.length < open.length && open.startsWith(rest)))) return
                    if (rest.startsWith(open)) {
                        pending.delete(0, ws + open.length)
                        insideThinking = true; atStart = false; swallowOpenTag = false
                        continue
                    }
                    atStart = false; swallowOpenTag = false
                }
                val idx = pending.indexOf(open)
                if (idx >= 0) {
                    emitVisible(visible, pending.substring(0, idx))
                    pending.delete(0, idx + open.length)
                    insideThinking = true; atStart = false
                    continue
                }
                val hold = if (final) 0 else partialSuffix(pending, open)
                emitVisible(visible, pending.substring(0, pending.length - hold))
                pending.delete(0, pending.length - hold)
                return
            } else {
                if (swallowOpenTag) {
                    // Seeded inside thinking: the model may still echo the open tag first; drop it once.
                    val ws = leadingWhitespace(pending)
                    val rest = pending.substring(ws)
                    if (!final && (rest.isEmpty() || (rest.length < open.length && open.startsWith(rest)))) return
                    if (rest.startsWith(open)) pending.delete(0, ws + open.length)
                    swallowOpenTag = false
                    atStart = false
                    continue
                }
                val idx = pending.indexOf(close)
                if (idx >= 0) {
                    thinking.append(pending, 0, idx)
                    pending.delete(0, idx + close.length)
                    insideThinking = false
                    trimVisibleLeading = true
                    atStart = false
                    continue
                }
                val hold = if (final) 0 else partialSuffix(pending, close)
                thinking.append(pending, 0, pending.length - hold)
                pending.delete(0, pending.length - hold)
                return
            }
        }
    }

    private fun emitVisible(out: StringBuilder, text: String) {
        if (text.isEmpty()) return
        var t = text
        if (trimVisibleLeading) {
            t = t.trimStart()
            if (t.isEmpty()) return          // whitespace may span pieces: keep trimming until real text arrives
            trimVisibleLeading = false
        }
        atStart = false
        out.append(t)
    }

    private companion object {
        fun leadingWhitespace(sb: CharSequence): Int {
            var i = 0
            while (i < sb.length && sb[i].isWhitespace()) i++
            return i
        }

        /** Length of the longest proper prefix of [tag] that [sb] ends with (0 when none). */
        fun partialSuffix(sb: CharSequence, tag: String): Int {
            val max = minOf(sb.length, tag.length - 1)
            for (k in max downTo 1) {
                if (sb.regionMatches(sb.length - k, tag, 0, k)) return k
            }
            return 0
        }

        private fun CharSequence.regionMatches(thisOffset: Int, other: String, otherOffset: Int, length: Int): Boolean {
            for (i in 0 until length) if (this[thisOffset + i] != other[otherOffset + i]) return false
            return true
        }
    }
}
