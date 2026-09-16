package to.eyed.inferno.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import to.eyed.inferno.models.ImageDetail

/** Pure functions behind ImageUtil (geometry, hashing, naming) and the ChatRepository title rule. */
class ImageUtilTest {
    @Test fun sampleSizeIsFloorOfLongEdgeRatio() {
        assertEquals(1, ImageMath.sampleSize(1000, 800, 1536))
        assertEquals(1, ImageMath.sampleSize(1536, 1536, 1536))
        assertEquals(7, ImageMath.sampleSize(12000, 9000, 1536))   // 108 MP: decodes at ~1714 px, ~9 MB instead of 430 MB
        assertEquals(26, ImageMath.sampleSize(9000, 12000, 448))
        assertEquals(10, ImageMath.sampleSize(10, 10, 0))           // maxEdge clamped to 1: never divides by zero
    }

    @Test fun fitEdgeNeverUpscalesAndKeepsAspect() {
        assertEquals(ImageMath.Size(300, 200), ImageMath.fitEdge(300, 200, 1536))
        assertEquals(ImageMath.Size(1536, 1024), ImageMath.fitEdge(3000, 2000, 1536))
        assertEquals(ImageMath.Size(1024, 1536), ImageMath.fitEdge(2000, 3000, 1536))
        assertEquals(ImageMath.Size(448, 448), ImageMath.fitEdge(4000, 4000, 448))
        assertEquals(ImageMath.Size(448, 1), ImageMath.fitEdge(100000, 10, 448))   // extreme aspect: at least 1 px
        assertEquals(ImageMath.Size(1, 1), ImageMath.fitEdge(0, 0, 448))
    }

    @Test fun promptEdgeFollowsTheAddendumClamps() {
        assertEquals(336, ImageMath.promptEdge(ImageDetail.FAST))
        assertEquals(448, ImageMath.promptEdge(ImageDetail.BALANCED))
        assertEquals(ImageDetail.HIGH.maxEdgePx, ImageMath.promptEdge(ImageDetail.HIGH))            // imports: no catalog cap
        assertEquals(1024, ImageMath.promptEdge(ImageDetail.HIGH, 1024))                            // HIGH = catalog max
        assertEquals(448, ImageMath.promptEdge(ImageDetail.BALANCED, 1024))
        assertEquals(400, ImageMath.promptEdge(ImageDetail.BALANCED, 400))                          // catalog cap below the preset wins
        assertEquals(336, ImageMath.promptEdge(ImageDetail.FAST, 448))
    }

    @Test fun promptSizeMatchesBetweenPlaceholderAndEncode() {
        val s = ImageMath.promptSize(1536, 1152, ImageDetail.BALANCED, 448)
        assertEquals(ImageMath.Size(448, 336), s)
        assertEquals(448 * 336 * 3, s.width * s.height * 3)
    }

    @Test fun sha256HexIsLowercaseAndStable() {
        // NIST vector for "abc"
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ImageMath.sha256Hex("abc".toByteArray()))
        assertEquals(ImageMath.sha256Hex(byteArrayOf(1, 2, 3)), ImageMath.sha256Hex(byteArrayOf(1, 2, 3)))
        assertEquals(64, ImageMath.sha256Hex(ByteArray(0)).length)
    }

    @Test fun rgb888PackingDropsAlphaAndKeepsOrder() {
        val px = intArrayOf(0xFF102030.toInt(), 0x00FFFFFF, 0x80000000.toInt())
        val rgb = ImageMath.toRgb888(px)
        assertEquals(9, rgb.size)
        assertEquals(listOf(0x10, 0x20, 0x30, 0xFF, 0xFF, 0xFF, 0, 0, 0), rgb.map { it.toInt() and 0xff })
    }

    @Test fun idFromFileNameAcceptsOnlyAttachmentNames() {
        val sha = "a".repeat(64)
        assertEquals(sha, ImageMath.idFromFileName("$sha.jpg"))
        assertEquals(sha, ImageMath.idFromFileName("$sha.thumb.jpg"))
        assertNull(ImageMath.idFromFileName("$sha.png"))
        assertNull(ImageMath.idFromFileName("$sha.jpg.1234.tmp"))
        assertNull(ImageMath.idFromFileName("gen"))
        assertNull(ImageMath.idFromFileName("short.jpg"))
    }

    @Test fun titleRuleUsesFirstLineTrimmedTo48() {
        assertEquals("", ChatRepository.titleFrom(""))
        assertEquals("", ChatRepository.titleFrom("  \n\n "))
        assertEquals("Hello there", ChatRepository.titleFrom("\n  Hello there  \nsecond line"))
        val long = "x".repeat(48)
        assertEquals(long, ChatRepository.titleFrom(long))
        val longer = "word ".repeat(20).trim()
        val t = ChatRepository.titleFrom(longer)
        assertEquals(49, t.length)
        assertEquals('…', t.last())
        assertEquals(longer.take(48).trimEnd(), t.dropLast(1))
    }
}
