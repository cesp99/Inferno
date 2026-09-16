package to.eyed.inferno.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingParserTest {
    private fun run(parser: ThinkingParser, pieces: List<String>): Pair<String, String> {
        val think = StringBuilder(); val text = StringBuilder()
        for (p in pieces) { val (t, v) = parser.feed(p); think.append(t); text.append(v) }
        val (t, v) = parser.flush(); think.append(t); text.append(v)
        return think.toString() to text.toString()
    }

    private fun qwen(seeded: Boolean = false) = ThinkingParser("<think>", "</think>", startInsideThinking = seeded)

    @Test fun noTagsEverythingVisible() {
        val (t, v) = run(ThinkingParser(null, null), listOf("<think>hi</think>", " there"))
        assertEquals("", t); assertEquals("<think>hi</think> there", v)
    }

    @Test fun wholeTagsInOnePiece() {
        val (t, v) = run(qwen(), listOf("<think>plan</think>\n\nanswer"))
        assertEquals("plan", t); assertEquals("answer", v)
    }

    @Test fun tagsSplitAcrossPieces() {
        val (t, v) = run(qwen(), listOf("<th", "ink>rea", "son</thi", "nk>", "\n", "  visible ", "text"))
        assertEquals("reason", t); assertEquals("visible text", v)
    }

    @Test fun leadingWhitespaceBeforeOpenTagStillEntersThinking() {
        val (t, v) = run(qwen(), listOf("\n\n", "<think>", "x", "</think>", "y"))
        assertEquals("x", t); assertEquals("y", v)
    }

    @Test fun streamWithoutOpenTagIsVisibleAndCharactersAreNotHeldForever() {
        val p = qwen()
        val (t1, v1) = p.feed("Hello <")
        assertEquals("", t1); assertEquals("Hello ", v1)          // "<" could start a tag: held back
        val (t2, v2) = p.feed("b>bold")
        assertEquals("", t2); assertEquals("<b>bold", v2)         // disambiguated: released
        assertFalse(p.insideThinking)
    }

    @Test fun partialTagAtEndOfStreamBecomesVisibleOnFlush() {
        val p = qwen()
        val (_, v) = p.feed("done <thi")
        assertEquals("done ", v)
        val (t, rest) = p.flush()
        assertEquals("", t); assertEquals("<thi", rest)
    }

    @Test fun partialCloseTagInsideThinkingFlushesAsThinking() {
        val p = qwen()
        p.feed("<think>abc</th")
        val (t, v) = p.flush()
        assertEquals("</th", t); assertEquals("", v)
        assertTrue(p.insideThinking)
    }

    @Test fun seededWithoutOpenTag() {
        val p = qwen(seeded = true)
        assertTrue(p.insideThinking)
        val (t, v) = run(p, listOf("first thoughts", " more", "</think>", "\n\nThe answer"))
        assertEquals("first thoughts more", t); assertEquals("The answer", v)
    }

    @Test fun seededWithEchoedOpenTagSwallowsItOnce() {
        val (t, v) = run(qwen(seeded = true), listOf("<think>", "\nthoughts", "</think>", "  reply"))
        assertEquals("\nthoughts", t); assertEquals("reply", v)
    }

    @Test fun seededWithSplitEchoedOpenTag() {
        val (t, v) = run(qwen(seeded = true), listOf("<t", "hink>", "z</think>ok"))
        assertEquals("z", t); assertEquals("ok", v)
    }

    @Test fun secondThinkBlockIsAlsoSplit() {
        val (t, v) = run(qwen(), listOf("<think>a</think>b<think>c</think>d"))
        assertEquals("ac", t); assertEquals("bd", v)
    }

    @Test fun whitespaceAfterCloseTagTrimmedOnlyOnce() {
        val (t, v) = run(qwen(), listOf("<think>a</think>", " ", "\n", "x y  z"))
        assertEquals("a", t); assertEquals("x y  z", v)
    }

    @Test fun emptyPiecesAreHarmless() {
        val p = qwen()
        assertEquals("" to "", p.feed(""))
        assertEquals("" to "", p.flush())
    }

    @Test fun disabledPrefixMeansModelEmitsEmptyThinkBlock() {
        // Qwen with thinking off: the injected prefix "<think>\n\n</think>\n\n" is not part of the stream; the reply is plain.
        val (t, v) = run(qwen(), listOf("Plain ", "reply."))
        assertEquals("", t); assertEquals("Plain reply.", v)
    }
}
