package to.eyed.inferno.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownChunkerTest {
    @Test
    fun `paragraphs split on blank lines`() {
        val chunks = MarkdownChunker.chunks("one\n\ntwo\n\n\nthree")
        assertEquals(listOf("one", "two", "three"), chunks)
    }

    @Test
    fun `unterminated fence stays in the tail chunk while streaming`() {
        val chunks = MarkdownChunker.chunks("intro\n\n```kotlin\nval a = 1\n\nval b = 2")
        assertEquals(2, chunks.size)
        assertEquals("```kotlin\nval a = 1\n\nval b = 2", chunks[1])
    }

    @Test
    fun `terminated fence is one chunk and later text is another`() {
        val chunks = MarkdownChunker.chunks("```\na\n\nb\n```\n\nafter")
        assertEquals(listOf("```\na\n\nb\n```", "after"), chunks)
    }

    @Test
    fun `list runs separated by blank lines stay together`() {
        val chunks = MarkdownChunker.chunks("1. first\n\n2. second\n\n- bullet\n\nplain")
        assertEquals(2, chunks.size)
        assertEquals("1. first\n\n2. second\n\n- bullet", chunks[0])
        assertEquals("plain", chunks[1])
    }

    @Test
    fun `earlier chunks are stable as the tail grows`() {
        val a = MarkdownChunker.chunks("# Title\n\nSome text that is")
        val b = MarkdownChunker.chunks("# Title\n\nSome text that is growing\n\nNew para")
        assertEquals(a[0], b[0])
        assertTrue(b.size > a.size)
    }

    @Test
    fun `blank input yields no chunks`() {
        assertTrue(MarkdownChunker.chunks("").isEmpty())
        assertTrue(MarkdownChunker.chunks("\n\n  \n").isEmpty())
    }

    @Test
    fun `duration formatting`() {
        assertEquals("8.1 s", formatDuration(8_100))
        assertEquals("1 min 8 s", formatDuration(68_000))
        assertEquals("0.3 s", formatDuration(300))
    }
}
