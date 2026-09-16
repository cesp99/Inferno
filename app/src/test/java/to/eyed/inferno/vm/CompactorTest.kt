package to.eyed.inferno.vm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import to.eyed.inferno.data.ChatMessage
import to.eyed.inferno.data.ChatRepository
import to.eyed.inferno.engine.ContextConfig
import to.eyed.inferno.engine.ContextManager
import to.eyed.inferno.engine.EngineState
import to.eyed.inferno.engine.EngineTestFixtures
import to.eyed.inferno.engine.FakeNative
import to.eyed.inferno.engine.GenerationParams
import to.eyed.inferno.engine.InferenceEngine
import to.eyed.inferno.engine.ThermalGovernor
import to.eyed.inferno.models.ImageDetail

/**
 * The COMPACT flow end to end on the host JVM: a real InferenceEngine + ContextManager over the scripted FakeNative
 * and an in-memory store. Covers the summary row (what it covers, how many messages), the next prompt being built
 * from summary + recent turns, a chained compaction that folds the previous summary in, and Stop leaving the chat
 * exactly as it was.
 */
class CompactorTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val native = FakeNative()
    private val thermal = object : ThermalGovernor(null, scope, workerTids = { IntArray(0) }) {}
    private val engine = InferenceEngine(EngineTestFixtures.cpu({ 4L * EngineTestFixtures.GB }), thermal, scope, null, native)
    private val cm = ContextManager(engine)
    private val store = MemoryStore()
    private val compactor = Compactor(cm, EngineCompaction(engine, EngineTestFixtures.qwenThinking, GenerationParams(), ImageDetail.BALANCED), store)
    private val nCtx = 8192

    /** Plain list of ChatMessage rows per chat, the way ChatRepository hands them out (ordered by orderIndex). */
    private class MemoryStore : CompactionStore {
        val rows = HashMap<String, MutableList<ChatMessage>>()
        val trims = ArrayList<Pair<String, Int>>()
        var nextId = 0
        fun add(id: String, role: String, content: String): ChatMessage {
            val list = rows.getOrPut(id) { ArrayList() }
            val m = ChatMessage("m${nextId++}", role, content, null, System.currentTimeMillis(), emptyList(), null, (list.lastOrNull()?.orderIndex ?: -1) + 1)
            list += m
            return m
        }
        override suspend fun messages(conversationId: String): List<ChatMessage> = rows[conversationId].orEmpty().toList()
        override suspend fun appendSummary(conversationId: String, text: String, compactedThrough: Int, compactedCount: Int): ChatMessage {
            val list = rows.getOrPut(conversationId) { ArrayList() }
            val m = ChatMessage("s${nextId++}", ChatRepository.ROLE_SUMMARY, text, null, System.currentTimeMillis(), emptyList(), null,
                (list.lastOrNull()?.orderIndex ?: -1) + 1, compactedThrough, compactedCount)
            list += m
            return m
        }
        override suspend fun setTrimmedBefore(conversationId: String, orderIndex: Int) { trims += conversationId to orderIndex }
    }

    @Before fun load() = runBlocking {
        engine.load(EngineTestFixtures.local(), ContextConfig(nCtx = nCtx, nBatch = 1024, nUbatch = 1024), ImageDetail.BALANCED, useMmap = true, visionAllowed = true)
        native.pieces = listOf("- user is Carlo\n", "- wants a phone app")
    }
    @After fun tearDown() { runBlocking { withTimeout(5_000) { engine.unload() } }; scope.cancel() }

    /** [pairs] user/assistant exchanges of ~100 tokens each (400 chars). */
    private fun fill(id: String, pairs: Int, from: Int = 0) {
        for (i in from until from + pairs) { store.add(id, ChatRepository.ROLE_USER, "question $i " + "u".repeat(400)); store.add(id, ChatRepository.ROLE_ASSISTANT, "answer $i " + "a".repeat(400)) }
    }
    private fun requestTranscript(): String = String(native.lastMessages[1].content, Charsets.UTF_8)

    @Test fun summaryIsPersistedAtTheCompactionPoint() = runBlocking {
        fill("c1", 10)
        val r = compactor.compact("c1", "Be terse.", nCtx, reserve = 512)!!
        val s = r.summary
        assertEquals(ChatRepository.ROLE_SUMMARY, s.role)
        assertEquals("- user is Carlo\n- wants a phone app", s.content)
        assertEquals(11, s.compactedThrough)                                              // messages 0..11 folded, 12..19 (4 turns) kept
        assertEquals(12, s.compactedCount)
        assertEquals(0, r.droppedMaterial)
        assertEquals(21, store.messages("c1").size)                                       // appended after the turns it covers
        assertEquals(listOf("c1" to 0), store.trims)                                        // the rolling window start is reset
        // The request: fixed instruction as system, the transcript as one user turn, thinking off, 512-token cap.
        assertEquals(ContextManager.COMPACT_INSTRUCTION, String(native.lastMessages[0].content, Charsets.UTF_8))
        assertEquals("system", native.lastMessages[0].role)
        val transcript = requestTranscript()
        assertTrue(transcript.startsWith("User: question 0 "))
        assertTrue(transcript.contains("Assistant: answer 5 "))
        assertFalse(transcript.contains("question 6 "))
        assertEquals("<think>\n\n</think>\n\n", String(native.lastPrefix!!))
        assertTrue(native.calls.any { it == "generateStart(nPredict=512,images=0)" })
        assertTrue(engine.state.value is EngineState.Ready)
    }

    @Test fun theNextPromptIsBuiltFromTheSummaryAndTheRecentTurns() = runBlocking {
        fill("c1", 10)
        compactor.compact("c1", null, nCtx, 512)
        val view = MemoryView.of(store.messages("c1"))
        assertNotNull(view.summary)
        assertEquals((12..19).toList(), view.recent.map { it.orderIndex })
        assertTrue(view.recent.none { it.isSummary })
        val prompt = view.prompt()
        assertEquals("user", prompt.first().role); assertTrue(prompt.first().content.startsWith("question 6 "))
        assertEquals("Summary of the earlier conversation (your own notes):\n- user is Carlo\n- wants a phone app",
            ContextManager.systemWith(null, view.summary!!.content))
        assertEquals("sys\n\nSummary of the earlier conversation (your own notes):\n- user is Carlo\n- wants a phone app",
            ContextManager.systemWith("sys", view.summary!!.content))
    }

    @Test fun chainedCompactionFoldsThePreviousSummaryIn() = runBlocking {
        fill("c1", 10)
        val first = compactor.compact("c1", null, nCtx, 512)!!.summary
        fill("c1", 6, from = 10)                                                          // orderIndex 21..32 (the summary took 20)
        native.pieces = listOf("- second pass")
        val second = compactor.compact("c1", null, nCtx, 512)!!.summary
        assertEquals("- second pass", second.content)
        assertTrue(requestTranscript().startsWith("Your earlier notes (previous summary):\n${first.content}\n\nUser: question 6 "))
        assertFalse(requestTranscript().contains("question 0 "))                           // already inside the previous summary
        // 8 recent + 12 new = 20 turns; keep the last 4 turns => 12 more messages folded, cumulative 24.
        assertEquals(24, second.compactedCount)
        assertEquals(24, second.compactedThrough)
        val view = MemoryView.of(store.messages("c1"))
        assertEquals(second.id, view.summary!!.id)
        assertEquals((25..32).toList(), view.recent.map { it.orderIndex })
        assertNotNull(store.messages("c1").firstOrNull { it.id == first.id })              // the old card stays in the history
    }

    @Test fun nothingToCompactReturnsNullAndPersistsNothing() = runBlocking {
        store.add("c2", ChatRepository.ROLE_USER, "hi")
        assertNull(compactor.compact("c2", null, nCtx, 512))
        assertEquals(1, store.messages("c2").size)
        assertFalse(native.calls.any { it.startsWith("generateStart") })
    }

    @Test fun cancelLeavesTheChatUntouched() = runBlocking {
        fill("c1", 10)
        native.blockUntilCancel = true
        val before = store.messages("c1")
        val job = launch(Dispatchers.Default) { compactor.compact("c1", null, nCtx, 512) }
        withTimeout(5_000) { while (native.calls.none { it.startsWith("generateStart") }) delay(5) }
        job.cancel(); withTimeout(5_000) { job.join() }
        assertEquals(before, store.messages("c1"))
        assertTrue(store.trims.isEmpty())
        assertTrue(native.cancelCount.get() >= 1)
        withTimeout(5_000) { while (engine.state.value !is EngineState.Ready) delay(5) }
    }
}
