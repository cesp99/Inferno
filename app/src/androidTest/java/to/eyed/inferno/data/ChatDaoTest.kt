package to.eyed.inferno.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import to.eyed.inferno.data.db.ChatDatabase
import to.eyed.inferno.data.db.ConversationEntity
import to.eyed.inferno.data.db.GeneratedImageEntity
import to.eyed.inferno.data.db.MessageEntity
import to.eyed.inferno.data.db.MessageImageEntity
import to.eyed.inferno.imagegen.GenerationMeta
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class ChatDaoTest {
    private lateinit var db: ChatDatabase
    private lateinit var images: ImageUtil
    private lateinit var repo: ChatRepository
    private val dao get() = db.dao()

    @Before fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = ChatDatabase.inMemory(ctx)
        images = ImageUtil(ctx)
        repo = ChatRepository(dao, images)
    }

    @After fun tearDown() { db.close() }

    private fun conv(id: String, updatedAt: Long, pinned: Boolean = false) =
        ConversationEntity(id = id, title = "", createdAt = updatedAt, updatedAt = updatedAt, pinned = pinned, modelId = null)

    private fun msg(id: String, cid: String, idx: Int, role: String = "user", finish: String? = null) = MessageEntity(
        id = id, conversationId = cid, orderIndex = idx, role = role, content = "m$idx", createdAt = idx.toLong(),
        finishReason = finish, promptTokens = 100, reusedTokens = 40, generatedTokens = 25, prefillMs = 300, decodeMs = 1200,
        imageEncodeMs = 5200, kvUsedTokens = 125, nCtx = 8192, paramsJson = """{"temperature":0.7}""", templateName = "chatml",
        templateSupported = false, modelId = "lfm2.5-vl-1.6b-q4_0",
    )

    /** Creates a fake attachment file pair so the repository's file release can be observed. */
    private fun fakeAttachment(seed: Int): Attachment {
        val id = ImageMath.sha256Hex(byteArrayOf(seed.toByte()))
        images.imagesDir.mkdirs()
        images.pathOf(id).writeBytes(byteArrayOf(seed.toByte()))
        images.thumbOf(id).writeBytes(byteArrayOf(seed.toByte()))
        return images.attachment(id, 640, 480)
    }

    @Test fun extendedColumnsRoundTripAndStreamingRowHasNullFinish() = runBlocking {
        dao.upsert(conv("c1", 10))
        dao.upsert(msg("u1", "c1", 0))
        dao.upsert(msg("a1", "c1", 1, role = "assistant", finish = "EOS"))
        dao.upsert(msg("a2", "c1", 2, role = "assistant", finish = null))
        val rows = dao.messagesOnce("c1")
        assertEquals(listOf("u1", "a1", "a2"), rows.map { it.message.id })
        val a1 = rows[1].message
        assertEquals(40, a1.reusedTokens); assertEquals(5200L, a1.imageEncodeMs); assertEquals(125, a1.kvUsedTokens)
        assertEquals(8192, a1.nCtx); assertEquals("""{"temperature":0.7}""", a1.paramsJson); assertEquals("chatml", a1.templateName)
        assertFalse(a1.templateSupported); assertEquals("EOS", a1.finishReason)
        assertNull(rows[2].message.finishReason)
        // Repository view: the streaming/interrupted assistant row reports interrupted, the user row has no stats.
        val msgs = repo.messagesOnce("c1")
        assertNull(msgs[0].stats)
        assertEquals(false, msgs[1].stats!!.interrupted)
        assertEquals(true, msgs[2].stats!!.interrupted)
        assertEquals(25 * 1000.0 / 1200, msgs[1].stats!!.decodeTps, 1e-9)
    }

    @Test fun conversationsOrderPinnedFirstThenMostRecent() = runBlocking {
        dao.upsert(conv("old", 1)); dao.upsert(conv("new", 3)); dao.upsert(conv("pinnedOld", 2, pinned = true))
        assertEquals(listOf("pinnedOld", "new", "old"), dao.conversations().first().map { it.id })
        dao.setPinned("pinnedOld", false)
        assertEquals(listOf("new", "pinnedOld", "old"), dao.conversations().first().map { it.id })
    }

    @Test fun cascadeDeleteRemovesMessagesAndImageRows() = runBlocking {
        dao.upsert(conv("c1", 1))
        dao.upsert(msg("u1", "c1", 0))
        dao.upsertImages(listOf(MessageImageEntity("u1", "img", 0, 10, 10)))
        assertEquals(1, dao.imageRefs("img"))
        dao.deleteConversation("c1")
        assertEquals(0, dao.messagesOnce("c1").size)
        assertEquals(0, dao.imageRefs("img"))
    }

    @Test fun deleteFromKeepsEarlierMessages() = runBlocking {
        dao.upsert(conv("c1", 1))
        (0..4).forEach { dao.upsert(msg("m$it", "c1", it)) }
        dao.deleteFrom("c1", 3)
        assertEquals(listOf(0, 1, 2), dao.messagesOnce("c1").map { it.message.orderIndex })
        assertEquals(2, dao.maxOrderIndex("c1"))
        assertEquals(-1, dao.maxOrderIndex("nope"))
    }

    @Test fun repositoryTitleOrderAndAttachments() = runBlocking {
        val c = repo.create("qwen")
        assertEquals("New chat", c.displayTitle)
        val att = fakeAttachment(1)
        val u = repo.appendUser(c.id, "First line of a question\nsecond line", listOf(att))
        assertEquals(0, u.orderIndex)
        assertEquals(1, u.images.size)
        assertEquals(att.id, u.images[0].id)
        val a = repo.appendAssistant(c.id, "Hel", null, null)
        assertEquals(1, a.orderIndex)
        assertTrue(a.stats!!.interrupted)
        repo.updateAssistant(a.id, "Hello", "thought", MessageStats(promptTokens = 12, generatedTokens = 3, prefillMs = 10, decodeMs = 20, finishReason = "EOS", modelId = "qwen"))
        val m = repo.message(a.id)!!
        assertEquals("Hello", m.content); assertEquals("thought", m.thinking); assertEquals("EOS", m.stats!!.finishReason)
        val conv = repo.conversations.first().single()
        assertEquals("First line of a question", conv.title)
        assertEquals("qwen", conv.modelId)
        val fromFlow = repo.messages(c.id).first()
        assertEquals(listOf("user", "assistant"), fromFlow.map { it.role })
        assertEquals(att.path, fromFlow[0].images[0].path)
        assertTrue(File(fromFlow[0].images[0].thumbPath).exists())
        images.deleteIfUnreferenced(att.id, 0)
    }

    @Test fun truncateAndDeleteReleaseOnlyOrphanedFiles() = runBlocking {
        val shared = fakeAttachment(2)
        val only = fakeAttachment(3)
        val c = repo.create(null)
        repo.appendUser(c.id, "keep", listOf(shared))
        repo.appendAssistant(c.id, "ok", null, null)
        repo.appendUser(c.id, "drop", listOf(shared, only))
        repo.truncateFrom(c.id, 2)
        assertTrue("shared image still referenced by message 0", File(shared.path).exists())
        assertFalse("orphaned image deleted", File(only.path).exists())
        assertFalse(File(only.thumbPath).exists())
        assertEquals(2, repo.messagesOnce(c.id).size)
        // A second conversation referencing the same image keeps it alive across delete().
        val c2 = repo.create(null)
        repo.appendUser(c2.id, "also", listOf(shared))
        repo.delete(c.id)
        assertTrue(File(shared.path).exists())
        assertEquals(1, dao.imageRefs(shared.id))
        repo.deleteAll()
        assertFalse(File(shared.path).exists())
        assertEquals(0, repo.conversations.first().size)
    }

    @Test fun generatedImagesDao() = runBlocking {
        val gdao = db.generatedImages()
        val e = GeneratedImageEntity(
            id = "g1", modelId = "sdxs-512-q8_0", prompt = "an owl", negative = "", width = 512, height = 512, steps = 1, seed = 42,
            sampler = "EULER", totalMs = 12000, createdAt = 5, path = "/x/g1.png", thumbPath = "/x/g1.thumb.jpg",
        )
        gdao.insert(e)
        gdao.insert(e.copy(id = "g2", createdAt = 9))
        assertEquals(listOf("g2", "g1"), gdao.observeAll().first().map { it.id })
        assertEquals(e, gdao.byId("g1"))
        assertNotNull(gdao.byId("g2"))
        gdao.delete("g1")
        assertNull(gdao.byId("g1"))
        assertEquals(1, gdao.count())
    }

    @Test fun generatedImageStoreWritesFilesAndRowTogether() = runBlocking {
        val store = GeneratedImageStoreImpl(db.generatedImages(), images)
        val png = ByteArrayOutputStream().also { Bitmap.createBitmap(384, 512, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val meta = GenerationMeta(modelId = "dreamshaper-8-lcm-q8_0", prompt = "a lantern", negative = "", width = 384, height = 512,
            steps = 4, seed = 1234567890123L, sampler = "LCM", totalMs = 33000, createdAt = 77)
        val r = store.save(png, meta)
        assertEquals(36, r.id.length)
        assertTrue(r.path.endsWith("/images/gen/${r.id}.png")); assertTrue(File(r.path).isFile)
        assertEquals(1234567890123L, r.seed); assertEquals("LCM", r.sampler); assertEquals(77L, r.createdAt)
        val thumbPath = r.thumbPath!!
        val thumb = BitmapFactory.decodeFile(thumbPath)
        assertEquals(192, thumb.width); assertEquals(256, thumb.height)
        assertEquals(r, store.byId(r.id))
        assertEquals(listOf(r.id), store.all.first().map { it.id })
        store.delete(r.id)
        assertNull(store.byId(r.id))
        assertFalse(File(r.path).exists()); assertFalse(File(thumbPath).exists())
        assertEquals(0, db.generatedImages().count())
    }
}
