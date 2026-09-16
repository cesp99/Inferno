package to.eyed.inferno.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.inferno.models.LocalModel

/**
 * fit() over a counting fake: 1 token per 4 characters of content, 256 per image, 8 per message of template
 * overhead. Checks the hysteresis (trim to 75 % only when the prompt does not fit), that trimming never splits a
 * message and always opens on a user turn, that the newest user turn survives, and the image fallback.
 */
class ContextManagerFitTest {
    private class CountingEngine : PlannerEngine {
        var counts = 0
        var lastImages: List<PromptImage> = emptyList()
        override suspend fun estimateMemory(model: LocalModel, config: ContextConfig): MemoryEstimate = error("unused")
        override suspend fun countPromptTokens(messages: List<PromptMessage>, images: List<PromptImage>): Int {
            counts++; lastImages = images
            return messages.sumOf { it.content.length / 4 + 8 } + messages.sumOf { it.imageIds.size } * 256
        }
    }

    private val engine = CountingEngine()
    private val cm = ContextManager(engine)

    /** [pairs] user/assistant turns of ~100 tokens each (400 chars -> 100 + 8 per message). */
    private fun history(pairs: Int, imagesOn: Set<Int> = emptySet()): List<PromptMessage> = (0 until pairs).flatMap { i ->
        listOf(PromptMessage("user", "u".repeat(400), if (i in imagesOn) listOf("img$i") else emptyList()), PromptMessage("assistant", "a".repeat(400)))
    }
    private fun images(ids: Collection<String>) = ids.map { PromptImage(it, 448, 448, ByteArray(3)) }

    @Test fun promptThatFitsIsUntouched() = runBlocking {
        val h = history(4)                                         // 8 x 108 = 864 tokens
        val fit = cm.fit("sys", h, emptyList(), nCtx = 2048, reserve = 512, trimmedBefore = 0)
        assertEquals(9, fit.messages.size); assertEquals("system", fit.messages.first().role)
        assertEquals(0, fit.droppedCount); assertEquals(0, fit.droppedImages); assertFalse(fit.trimmedThisTurn)
        assertEquals(864 + 8, fit.promptTokens); assertEquals(0, fit.trimmedBefore)
        assertEquals(1, engine.counts)
    }

    @Test fun blankSystemPromptIsOmitted() = runBlocking {
        val fit = cm.fit("  ", history(1), emptyList(), 2048, 512, 0)
        assertEquals(listOf("user", "assistant"), fit.messages.map { it.role })
    }

    @Test fun hysteresisDoesNotTrimBetween75And100Percent() = runBlocking {
        // 16 messages x 108 = 1728 tokens + 512 reserve = 2240 <= 2304 but > 0.75 x 2304: fits, so no trim.
        val fit = cm.fit(null, history(8), emptyList(), nCtx = 2304, reserve = 512, trimmedBefore = 0)
        assertEquals(0, fit.droppedCount); assertFalse(fit.trimmedThisTurn)
    }

    @Test fun overflowTrimsOldestPairsDownTo75PercentInOneStep() = runBlocking {
        // 20 messages x 108 = 2160 + 512 > 2048: drop pairs until tokens + reserve <= 1536 => keep 9 messages (972 tokens).
        val h = history(10)
        val fit = cm.fit(null, h, emptyList(), nCtx = 2048, reserve = 512, trimmedBefore = 0)
        assertTrue(fit.trimmedThisTurn)
        assertTrue("tokens=${fit.promptTokens}", fit.promptTokens + 512 <= 0.75 * 2048)
        assertEquals("user", fit.messages.first().role)                                  // window opens on a user turn
        assertEquals(fit.messages.size, h.size - fit.droppedCount)
        assertEquals(0, fit.droppedCount % 2)                                             // whole pairs
        assertEquals(fit.droppedCount, fit.trimmedBefore)
        assertEquals(h.last(), fit.messages.last())                                       // newest turn kept, never split
        // The next turn resumes from the persisted index: a stable prefix, no re-count of dropped history.
        val again = cm.fit(null, h, emptyList(), 2048, 512, trimmedBefore = fit.trimmedBefore)
        assertEquals(fit.messages, again.messages); assertEquals(0, again.droppedCount); assertFalse(again.trimmedThisTurn)
        assertEquals(fit.trimmedBefore, again.trimmedBefore)
    }

    @Test fun trimmedBeforeSnapsForwardToAUserTurn() = runBlocking {
        val h = history(4)
        val fit = cm.fit(null, h, emptyList(), 4096, 512, trimmedBefore = 1)                // index 1 is an assistant message
        assertEquals(2, fit.trimmedBefore); assertEquals("user", fit.messages.first().role); assertEquals(6, fit.messages.size)
        assertEquals(0, fit.droppedCount)                                                  // nothing dropped *this* turn
    }

    @Test fun theNewestUserTurnIsNeverDroppedEvenWhenItDoesNotFit() = runBlocking {
        val h = listOf(PromptMessage("user", "x".repeat(400 * 30)))                        // 3008 tokens alone
        val fit = cm.fit("sys", h, emptyList(), nCtx = 2048, reserve = 512, trimmedBefore = 0)
        assertEquals(2, fit.messages.size); assertEquals(0, fit.droppedCount)
        assertTrue(fit.promptTokens + 512 > 2048)                                          // caller gets NeedsTruncation from the engine
    }

    @Test fun imagesAreDroppedOldestFirstOnlyWhenTextTrimmingIsNotEnough() = runBlocking {
        // Two images in the newest turn (512 tokens) + 11 text tokens; nCtx 1024 with reserve 512 cannot hold both.
        val h = listOf(PromptMessage("user", "u".repeat(400), listOf("a", "b")), PromptMessage("assistant", "a".repeat(400)),
            PromptMessage("user", "please describe", listOf("c", "d")))
        val fit = cm.fit(null, h, images(listOf("a", "b", "c", "d")), nCtx = 1024, reserve = 512, trimmedBefore = 0)
        assertEquals(2, fit.droppedCount)                                                  // the old pair goes first (with a, b)
        assertEquals(1, fit.droppedImages)                                                 // then one more image, oldest first: "c"
        assertEquals(listOf("d"), fit.messages.single().imageIds)
        assertEquals(listOf("d"), fit.images.map { it.id })
        assertTrue(fit.trimmedThisTurn)
        assertTrue(fit.promptTokens + 512 <= 1024)
    }

    @Test fun imagesAreCountedWithPlaceholdersAndReturnedWithPixels() = runBlocking {
        val h = history(2, imagesOn = setOf(0, 1))
        val fit = cm.fit(null, h, images(listOf("img0", "img1", "unused")), 8192, 512, 0)
        assertTrue(engine.lastImages.all { it.rgb == null })                               // counting never ships pixels
        assertEquals(listOf("img0", "img1"), fit.images.map { it.id })                      // only referenced images, prompt order
        assertTrue(fit.images.all { it.rgb != null })
        assertNull(fit.images.firstOrNull { it.id == "unused" })
    }

    @Test fun reserveRuleAndSanitizer() {
        assertEquals(512, cm.reserveFor(GenerationParams(), 8192, thinkingOn = false))
        assertEquals(1024, cm.reserveFor(GenerationParams(), 8192, thinkingOn = true))
        assertEquals("look <_media_> here", ContextManager.sanitize("look <__media__> here"))
    }
}
