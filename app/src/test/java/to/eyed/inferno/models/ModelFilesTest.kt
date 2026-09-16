package to.eyed.inferno.models

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

class ModelFilesTest {
    private lateinit var root: File
    private lateinit var files: ModelFiles
    private val small = ModelCatalog.byId("lfm2.5-vl-450m-q8_0")!!

    @Before fun setUp() { root = tempDir("files"); files = testFiles(root) }
    @After fun tearDown() { root.deleteRecursively() }

    @Test fun layoutFollowsTheSpec() {
        assertEquals(File(root, "${small.id}/${small.text.fileName}"), files.textFile(small))
        assertEquals(File(root, "${small.id}/${small.mmproj!!.fileName}"), files.mmprojFile(small))
        assertEquals(File(root, "taesd/${ImageModelCatalog.taesd.fileName}"), files.fileFor(ImageModelCatalog.taesd.toDownload(ImageModelCatalog.TAESD_DIR_ID)))
        assertEquals(File(root, "imported/import-abcdef01"), files.dirFor("import-abcdef01"))
        val f = files.textFile(small)
        assertEquals(File(f.path + ".part"), files.partFile(f))
        assertEquals(File(f.path + ".part.json"), files.partJsonFile(f))
    }

    @Test fun scanFindsCompleteCatalogModelsOnly() = runBlocking {
        files.textFile(small).makeSparseGguf(small.text.sizeBytes)
        files.mmprojFile(small)!!.makeSparseGguf(small.mmproj!!.sizeBytes - 1)   // truncated projector
        var scan = files.scan()
        assertTrue(scan.models.isEmpty())
        assertFalse(files.isDownloaded(small.downloads))

        files.mmprojFile(small)!!.makeSparseGguf(small.mmproj.sizeBytes)
        scan = files.scan()
        val local = scan.models.single()
        assertEquals(small.id, local.id); assertEquals(small, local.catalog)
        assertTrue(local.hasVision); assertEquals(small.totalBytes, local.totalBytes)
        assertEquals(Quant.Q8_0, local.quant)
        assertTrue(files.isDownloaded(small.downloads))
        assertEquals(0L, files.remainingBytes(small.downloads))
    }

    @Test fun partialBytesAndRemaining() = runBlocking {
        val text = files.textFile(small)
        files.partFile(text).makeSparseGguf(1000)
        val scan = files.scan()
        assertEquals(mapOf(small.id to 1000L), scan.partialBytes)
        assertEquals(1000L, files.partialBytes(small.downloads))
        assertEquals(small.totalBytes - 1000L, files.remainingBytes(small.downloads))
        val info = files.storageInfo()
        assertEquals(1000L, info.partialBytes); assertEquals(1000L, info.modelsBytes)
        assertEquals(100L shl 30, info.freeBytes); assertEquals(128L shl 30, info.totalBytes)

        files.discardPartial(small.downloads)
        assertFalse(files.partFile(text).exists())
        assertTrue(files.scan().partialBytes.isEmpty())
    }

    @Test fun deleteRemovesTheWholeDirectory() = runBlocking {
        files.textFile(small).makeSparseGguf(10)
        files.partFile(files.mmprojFile(small)!!).makeSparseGguf(10)
        files.delete(small.id)
        assertFalse(files.dirFor(small.id).exists())
    }

    @Test fun ggufHeaderReadsGeneralKeysPastArrays() {
        val f = File(root, "h.gguf").apply { writeBytes(Gguf.bytes(arch = "qwen35", name = "Qwen Test", fileType = 7, totalSize = 2048)) }
        val h = GgufHeader.read(f)
        assertEquals("qwen35", h.architecture); assertEquals("Qwen Test", h.name); assertEquals(Quant.Q8_0, h.quant)
        assertEquals(Quant.OTHER, GgufHeader("x", null, 99).quant)
        assertEquals(Quant.Q4_K_M, GgufHeader("x", null, 15).quant)
        assertTrue(files.isGguf(f))
        assertFalse(files.isGguf(File(root, "nope.gguf").apply { writeBytes(ByteArray(16)) }))
    }

    @Test fun importWritesMetaAndIsIdempotentPerContent() = runBlocking {
        val a = Gguf.bytes(arch = "llama", name = "Alpha", fileType = 2, totalSize = 8192, seed = 1)
        val b = Gguf.bytes(arch = "llama", name = "Beta", fileType = 15, totalSize = 8192, seed = 2)
        val la = files.importFrom({ a.inputStream() }, "content://x/alpha.gguf", null, false, null)
        assertTrue(la.id.startsWith("import-") && la.id.length == "import-".length + 8)
        assertEquals("Alpha", la.displayName); assertEquals(Quant.Q4_0, la.quant); assertNull(la.catalog)
        assertEquals(File(root, "imported/${la.id}/model.gguf").path, la.textPath)
        assertTrue(File(root, "imported/${la.id}/meta.json").readText().contains("\"sha8\""))
        assertEquals(8192L, la.textBytes); assertFalse(la.hasVision)

        val lb = files.importFrom({ b.inputStream() }, null, "My Beta", false, null)
        assertNotEquals(la.id, lb.id); assertEquals("My Beta", lb.displayName); assertEquals(Quant.Q4_K_M, lb.quant)

        val again = files.importFrom({ a.inputStream() }, null, null, false, null)
        assertEquals("same bytes => same id", la.id, again.id)
        assertEquals(2, files.scan().models.size)
        assertTrue(files.scan().models.all { it.catalog == null })
        assertEquals(listOf(la.id, lb.id).sorted(), files.scan().models.map { it.id }.sorted())
    }

    @Test fun projectorPairing() = runBlocking {
        val model = files.importFrom({ Gguf.bytes(totalSize = 4096).inputStream() }, null, "M", false, null)
        val proj = Gguf.bytes(arch = "clip", name = null, fileType = 1, totalSize = 2048)
        val paired = files.importFrom({ proj.inputStream() }, null, null, true, model.id)
        assertEquals(model.id, paired.id); assertTrue(paired.hasVision)
        assertEquals(File(root, "imported/${model.id}/mmproj.gguf").path, paired.mmprojPath)
        assertEquals(2048L, paired.mmprojBytes)

        try { files.importFrom({ Gguf.bytes(arch = "llama").inputStream() }, null, null, true, model.id); fail() }
        catch (e: IOException) { assertTrue(e.message!!.contains("Not a projector")) }
        try { files.importFrom({ proj.inputStream() }, null, null, true, null); fail() }
        catch (e: IOException) { assertTrue(e.message!!.contains("Choose the model")) }
        try { files.importFrom({ proj.inputStream() }, null, null, false, null); fail() }
        catch (e: IOException) { assertTrue(e.message!!.contains("projector file")) }
        try { files.importFrom({ ByteArray(100).inputStream() }, null, null, false, null); fail() }
        catch (e: IOException) { assertEquals("Not a GGUF file", e.message) }
        assertTrue("staging dirs are cleaned up", files.importedRoot.listFiles()!!.none { it.name.startsWith(".staging") })
    }

    @Test fun sha8IsStableAndSizeSensitive() {
        val f = File(root, "s.bin").apply { writeBytes(ByteArray(1000) { it.toByte() }) }
        val h1 = ModelFiles.sha8(f)
        assertEquals(8, h1.length); assertEquals(h1, ModelFiles.sha8(f))
        f.appendBytes(byteArrayOf(0))
        assertNotEquals(h1, ModelFiles.sha8(f))
    }
}
