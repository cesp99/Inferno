package to.eyed.inferno.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import to.eyed.inferno.models.ImageDetail
import java.io.File
import kotlin.math.max

/** ImageUtil against real files: fixtures are synthesised at test time so nothing has to be pushed. */
@RunWith(AndroidJUnit4::class)
class ImageUtilDeviceTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var util: ImageUtil
    private lateinit var work: File

    @Before fun setUp() {
        util = ImageUtil(ctx)
        work = File(ctx.cacheDir, "imageutil-test").apply { deleteRecursively(); mkdirs() }
    }

    @After fun tearDown() { work.deleteRecursively() }

    /** Landscape gradient with a red top-left corner so rotation is observable. */
    private fun bitmap(w: Int, h: Int, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap {
        val b = Bitmap.createBitmap(w, h, config)
        val c = Canvas(b)
        c.drawColor(Color.rgb(40, 120, 200))
        c.drawRect(0f, 0f, w / 4f, h / 4f, Paint().apply { color = Color.RED })
        return b
    }

    private fun jpeg(name: String, bmp: Bitmap, quality: Int = 90): File =
        File(work, name).also { f -> f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) } }

    @Test fun importAppliesExifRotationAndClampsEdge() = runBlocking {
        val f = jpeg("portrait.jpg", bitmap(800, 400))
        ExifInterface(f.path).apply { setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes() }
        val att = util.importImage(Uri.fromFile(f), maxEdge = 300)
        // 800x400 rotated 90 -> 400x800 portrait, clamped to a 300 px long edge.
        assertEquals(150, att.width); assertEquals(300, att.height)
        val stored = BitmapFactory.decodeFile(att.path)
        assertEquals(150, stored.width); assertEquals(300, stored.height)
        // The red corner (top-left of the sensor image) lands top-right after a 90 degree clockwise rotation.
        val tr = stored.getPixel(stored.width - 5, 5)
        assertTrue("expected red at top-right, got ${Integer.toHexString(tr)}", Color.red(tr) > 200 && Color.green(tr) < 80)
        val thumb = BitmapFactory.decodeFile(att.thumbPath)
        assertEquals(256, max(thumb.width, thumb.height))
        assertEquals(64, att.id.length)
        util.deleteIfUnreferenced(att.id, 0)
        assertFalse(File(att.path).exists())
    }

    @Test fun sameFileImportsToSameIdTwiceAndDifferentFileDiffers() = runBlocking {
        val f = jpeg("a.jpg", bitmap(640, 480))
        val a1 = util.importImage(Uri.fromFile(f))
        val a2 = util.importImage(Uri.fromFile(f))
        assertEquals(a1.id, a2.id)
        assertEquals(a1.path, a2.path)
        val other = util.importImage(Uri.fromFile(jpeg("b.jpg", bitmap(640, 481))))
        assertNotEquals(a1.id, other.id)
        util.deleteIfUnreferenced(a1.id, 0); util.deleteIfUnreferenced(other.id, 0)
    }

    @Test fun largeImageDecodesWithinTransientBudget() = runBlocking {
        // 48 MP in RGB_565 costs ~96 MB to synthesise; the JPEG on disk is what importImage sees.
        val big = bitmap(8000, 6000, Bitmap.Config.RGB_565)
        val f = jpeg("big.jpg", big, quality = 60)
        big.recycle()
        System.gc(); Runtime.getRuntime().gc()
        val before = Debug.getNativeHeapAllocatedSize() + Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        val att = util.importImage(Uri.fromFile(f))
        val after = Debug.getNativeHeapAllocatedSize() + Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        assertEquals(1536, att.width); assertEquals(1152, att.height)
        // Without the header-listener sample size this decode would hold 192 MB; with it ~10-30 MB peak.
        assertTrue("transient allocation ${(after - before) / 1_000_000} MB", after - before < 60_000_000L)
        util.deleteIfUnreferenced(att.id, 0)
    }

    @Test fun unsupportedMimeThrowsImageException() = runBlocking {
        val txt = File(work, "notes.txt").apply { writeText("hello") }
        try { util.importImage(Uri.fromFile(txt)); fail("expected ImageException") } catch (e: ImageException) { }
        val bogus = File(work, "bogus.jpg").apply { writeBytes(ByteArray(100) { 7 }) }
        try { util.importImage(Uri.fromFile(bogus)); fail("expected ImageException") } catch (e: ImageException) { }
    }

    @Test fun promptImageMatchesPlaceholderAndCatalogClamp() = runBlocking {
        val att = util.importImage(Uri.fromFile(jpeg("p.jpg", bitmap(1600, 1200))))
        assertEquals(1536, att.width)
        val ph = util.placeholder(att, ImageDetail.BALANCED, catalogMaxEdgePx = 448)
        val pi = util.toPromptImage(att, ImageDetail.BALANCED, catalogMaxEdgePx = 448)
        assertEquals(448, max(pi.width, pi.height))
        assertEquals(ph.width, pi.width); assertEquals(ph.height, pi.height)
        assertEquals(pi.width * pi.height * 3, pi.rgb!!.size)
        assertEquals(att.id, pi.id)
        // Top-left pixel is inside the red rectangle.
        assertTrue((pi.rgb[0].toInt() and 0xff) > 200)
        val fast = util.toPromptImage(att, ImageDetail.FAST)
        assertEquals(336, max(fast.width, fast.height))
        val high = util.placeholder(att, ImageDetail.HIGH, catalogMaxEdgePx = 1024)
        assertEquals(1024, max(high.width, high.height))
        util.deleteIfUnreferenced(att.id, 0)
    }

    @Test fun sweepDeletesOnlyOldUnreferencedFiles() = runBlocking {
        val old = util.importImage(Uri.fromFile(jpeg("old.jpg", bitmap(200, 100))))
        val oldRef = util.importImage(Uri.fromFile(jpeg("oldref.jpg", bitmap(200, 101))))
        val fresh = util.importImage(Uri.fromFile(jpeg("fresh.jpg", bitmap(200, 102))))
        val twoDays = System.currentTimeMillis() - 48 * 3600_000L
        for (a in listOf(old, oldRef)) { File(a.path).setLastModified(twoDays); File(a.thumbPath).setLastModified(twoDays) }
        util.sweepOrphans(refsOf = { id -> if (id == oldRef.id) 1 else 0 })
        assertFalse(File(old.path).exists()); assertFalse(File(old.thumbPath).exists())
        assertTrue(File(oldRef.path).exists())
        assertTrue(File(fresh.path).exists())
        util.deleteIfUnreferenced(oldRef.id, 0); util.deleteIfUnreferenced(fresh.id, 0)
    }

    @Test fun cameraUriAndCacheClear() = runBlocking {
        val uri = util.newCameraUri()
        assertEquals("content", uri.scheme)
        assertEquals("${ctx.packageName}.fileprovider", uri.authority)
        assertTrue(uri.path!!.contains("camera/capture-"))
        ctx.contentResolver.openOutputStream(uri)!!.use { bitmap(64, 64).compress(Bitmap.CompressFormat.JPEG, 80, it) }
        assertEquals("image/jpeg", ctx.contentResolver.getType(uri))
        val att = util.importImage(uri)
        assertEquals(64, att.width)
        util.clearCache()
        assertTrue(File(ctx.cacheDir, "camera").listFiles().isNullOrEmpty())
        util.deleteIfUnreferenced(att.id, 0)
    }

    @Test fun generatedPngIsStoredWithThumb() = runBlocking {
        val png = java.io.ByteArrayOutputStream().also { bitmap(512, 384).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val g = util.saveGenerated(png)
        assertTrue(File(g.path).isFile); assertTrue(g.path.endsWith("/images/gen/${g.id}.png"))
        assertTrue(png.contentEquals(File(g.path).readBytes()))
        val thumb = BitmapFactory.decodeFile(g.thumbPath)
        assertEquals(256, thumb.width); assertEquals(192, thumb.height)
        // The sweep must never touch gen/.
        File(g.path).setLastModified(0); File(g.thumbPath).setLastModified(0)
        util.sweepOrphans(refsOf = { 0 })
        assertTrue(File(g.path).isFile)
        util.deleteGenerated(g)
        assertFalse(File(g.path).exists()); assertFalse(File(g.thumbPath).exists())
    }
}
