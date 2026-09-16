package to.eyed.inferno.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.inferno.BuildConfig

class ModelCatalogTest {

    @Test
    fun visibleRowsInRankedOrder() {
        assertEquals(
            listOf(
                "qwen3.5-2b-q4_0", "minicpm-v-4.6-q4_0", "lfm2.5-vl-1.6b-q4_0", "gemma-4-e2b-q4_0", "lfm2.5-vl-3b-q4_0",
                "qwen3.5-0.8b-q8_0", "qwen3-vl-2b-q4_0", "qwen3.5-4b-q4_0", "lfm2.5-vl-450m-q8_0",
            ),
            ModelCatalog.visible.map { it.id },
        )
        assertEquals(ModelCatalog.models.size, ModelCatalog.visible.size)
    }

    @Test
    fun idsAreUniqueAndResolvable() {
        val ids = ModelCatalog.models.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        ids.forEach { assertNotNull(ModelCatalog.byId(it)) }
        assertNull(ModelCatalog.byId("nope"))
    }

    @Test
    fun exactlyOneRecommended() {
        assertEquals(listOf("qwen3.5-2b-q4_0"), ModelCatalog.models.filter { it.recommended }.map { it.id })
        assertSame(ModelCatalog.byId("qwen3.5-2b-q4_0"), ModelCatalog.recommended)
        assertEquals(ModelCatalog.recommended, ModelCatalog.visible.first())
    }

    @Test
    fun everyRowRunsOnTheBundledLlamaBuild() {
        ModelCatalog.models.forEach { assertTrue(it.id, it.minLlamaBuild <= BuildConfig.LLAMA_BUILD_NUMBER) }
    }

    @Test
    fun fileSpecsAreConsistent() {
        ModelCatalog.models.forEach { m ->
            m.files.forEach { f ->
                assertTrue("${m.id}: url", f.url.startsWith("https://huggingface.co/") && f.url.contains("/resolve/main/"))
                assertEquals("${m.id}: file name must be the URL tail", f.url.substringAfterLast('/'), f.fileName)
                assertTrue("${m.id}: size", f.sizeBytes > 50_000_000L)
                assertTrue("${m.id}: sha256", f.sha256?.length == 64)
                assertTrue(f.fileName.endsWith(".gguf"))
            }
            assertNotNull("${m.id}: every v1 row ships a projector", m.mmproj)
            assertTrue(m.defaultCtx in 2048..m.contextMax && m.defaultCtx % 256 == 0)
            assertTrue(m.kvBytesPerTokenF16 > 0 && m.paramsB > 0f && m.encoderPeakBytes > 0)
            assertTrue(m.blurb.isNotBlank() && m.license.isNotBlank() && m.estTgTps.isNotBlank())
        }
    }

    @Test
    fun spec122Corrections() {
        val gemma = ModelCatalog.byId("gemma-4-e2b-q4_0")!!
        assertEquals("https://huggingface.co/google/gemma-4-E2B-it-qat-q4_0-gguf/resolve/main/gemma-4-E2B_q4_0-it.gguf", gemma.text.url)
        assertEquals(3_349_516_256L, gemma.text.sizeBytes)
        assertEquals(557_368_064L, gemma.mmproj!!.sizeBytes)
        assertEquals(2600, gemma.encodeMsAt448)
        assertNull("Gemma 4 thinking is a system-turn marker, never an assistant prefix", gemma.thinking.enablePrefix)

        val minicpm = ModelCatalog.byId("minicpm-v-4.6-q4_0")!!
        assertEquals("https://huggingface.co/ggml-org/MiniCPM-V-4.6-GGUF/resolve/main/mmproj-MiniCPM-V-4.6-Q8_0.gguf", minicpm.mmproj!!.url)
        assertEquals(727_954_528L, minicpm.mmproj.sizeBytes)
        assertEquals("llava-uhd slicing: 448 px is a hard clamp (kernel-panic incident)", 448, minicpm.maxImageEdgePx)
        assertFalse(minicpm.dynamicResolution)
        assertTrue(minicpm in ModelCatalog.visible)

        val lfm3b = ModelCatalog.byId("lfm2.5-vl-3b-q4_0")!!
        assertEquals("LFM Open License v1.0", lfm3b.license)
        assertEquals(ModelTier.QUALITY, lfm3b.tier)
    }

    @Test
    fun thinkingPrefixesFollowTheFamily() {
        val qwen = ModelCatalog.byId("qwen3.5-2b-q4_0")!!.thinking
        assertTrue(qwen.hasTags)
        assertEquals("<think>\n\n</think>\n\n", qwen.disablePrefix)
        assertEquals("<think>\n", qwen.enablePrefix)
        assertFalse(qwen.defaultOn)
        ModelCatalog.models.filter { it.family == ModelFamily.LFM2VL }.forEach { assertFalse(it.id, it.thinking.hasTags) }
    }

    @Test
    fun imageDetailClampsPerAddendum122() {
        assertEquals(448, ImageDetail.BALANCED.maxEdgePx)
        assertEquals(336, ImageDetail.FAST.maxEdgePx)
        assertTrue(ImageDetail.HIGH.maxEdgePx >= ModelCatalog.models.maxOf { it.maxImageEdgePx })
    }

    @Test
    fun downloadableMappingsKeepTheLayout() {
        val m = ModelCatalog.recommended
        val dl = m.downloads
        assertEquals(listOf(m.text.fileName, m.mmproj!!.fileName), dl.map { it.fileName })
        assertTrue(dl.all { it.subdir == m.id && it.isGguf && !it.isSafetensors })
        assertEquals(m.totalBytes, dl.sumOf { it.sizeBytes })

        val img = ImageModelCatalog.sdxs.downloads
        assertEquals(listOf(ImageModelCatalog.sdxs.id, ImageModelCatalog.TAESD_DIR_ID), img.map { it.subdir })
        assertTrue(img[0].isGguf)
        assertTrue(img[1].isSafetensors)
        assertEquals(ImageModelCatalog.taesd.sizeBytes, img[1].sizeBytes)
    }
}
