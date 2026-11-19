package dev.mark.lineover.feature.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mark.lineover.core.engine.EngineSession
import dev.mark.lineover.core.engine.ExportImage
import dev.mark.lineover.core.io.export.JpegSegments
import dev.mark.lineover.core.io.export.WebpChunks
import dev.mark.lineover.core.io.layers.LayerSource
import dev.mark.lineover.core.io.layers.LayerSources
import dev.mark.lineover.core.io.looks.LooksStore
import dev.mark.lineover.core.io.project.ProjectLibrary
import dev.mark.lineover.core.model.EditGraph
import dev.mark.lineover.core.model.ExportColorSpace
import dev.mark.lineover.core.model.ExportFormat
import dev.mark.lineover.core.model.ExportSettings
import dev.mark.lineover.core.model.Layer
import dev.mark.lineover.core.model.LayerMath
import dev.mark.lineover.core.model.LayerType
import dev.mark.lineover.core.model.MetadataMode
import dev.mark.lineover.core.model.OpParams
import dev.mark.lineover.core.model.ResizeMode
import dev.mark.lineover.core.model.WatermarkSettings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

/**
 * Stage 12 round trips on a device: export -> decode with Android's decoders -> compare with
 * the render the export started from; ICC presence (the decoder sees the colour space) and
 * EXIF stripping. The source is a synthetic 1200 x 900 Display P3 photo with camera EXIF and
 * a location, plus one of Mark's Ultra HDR photos for the gain map.
 */
@RunWith(AndroidJUnit4::class)
class ExportRoundTripTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext = InstrumentationRegistry.getInstrumentation().context
    private val dir = File(context.cacheDir, "export-test").apply { mkdirs() }
    private val runner =
        ExportRunner(
            context,
            object : ExportEnvironment {
                override val projects: ProjectLibrary get() = error("not used")
                override val looks: LooksStore? = null
                override val versionName = "test"
                override val layers =
                    object : LayerSources {
                        override fun import(uri: String): LayerSource? = null

                        override fun file(name: String): File? = File(dir, name).takeIf { it.isFile }
                    }
            },
        )

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /** Saturated P3 patches, gradients and a grey ramp; EXIF with a camera and a location. */
    private fun syntheticPhoto(): File {
        val file = File(dir, "synthetic.jpg")
        if (file.exists()) return file
        val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888, false, ColorSpace.get(ColorSpace.Named.DISPLAY_P3))
        val canvas = Canvas(bitmap)
        val paint = Paint()
        for (x in 0 until W step 4) {
            paint.color = Color.rgb(x * 255 / W, 128, 255 - x * 255 / W)
            canvas.drawRect(x.toFloat(), 0f, x + 4f, H / 2f, paint)
        }
        val patches = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.CYAN, Color.MAGENTA, Color.WHITE, Color.BLACK)
        patches.forEachIndexed { i, c ->
            paint.color = c
            canvas.drawRect(i * W / 8f, H / 2f, (i + 1) * W / 8f, H * 3 / 4f, paint)
        }
        for (x in 0 until W) {
            paint.color = Color.rgb(x * 255 / W, x * 255 / W, x * 255 / W)
            canvas.drawRect(x.toFloat(), H * 3 / 4f, x + 1f, H.toFloat(), paint)
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        bitmap.recycle()
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_MAKE, "LineOverTest")
            setAttribute(ExifInterface.TAG_MODEL, "Synthetic")
            setLatLong(41.39, 2.17)
            saveAttributes()
        }
        return file
    }

    private fun ultraHdrPhoto(): File {
        val file = File(dir, "IMG_20261028_114803.jpg")
        if (!file.exists()) testContext.assets.open(file.name).use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    private fun export(
        settings: ExportSettings,
        source: File = syntheticPhoto(),
        graph: EditGraph = EditGraph(),
    ): File =
        runBlocking {
            val session = requireNotNull(EngineSession.open(context, Uri.fromFile(source)))
            session.use { runner.render(it, ExportRunner.RenderJob(graph, settings.validated(), "synthetic.jpg"), File(dir, "work")).first }
        }

    /** The pixels the export encodes: the same render, processed, as an 8-bit Bitmap. */
    private fun reference(
        settings: ExportSettings,
        source: File = syntheticPhoto(),
    ): Bitmap =
        runBlocking {
            val s = settings.validated()
            val session = requireNotNull(EngineSession.open(context, Uri.fromFile(source)))
            session.use {
                it.setGraph(EditGraph())
                it.renderForExport(File(dir, "ref")).use { image ->
                    val (w, h) = s.outputSize(image.width, image.height)
                    image.process(w, h, s.sharpen.amount, null)
                    image.toBitmap(s.colorSpace)
                }
            }
        }

    /** The colour space the decoder reads from the file (its ICC profile), before any conversion. */
    private fun sourceColorSpace(file: File): ColorSpace? {
        var space: ColorSpace? = null
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            space = info.colorSpace
            decoder.setTargetSize(1, 1)
        }
        return space
    }

    private fun decode(file: File): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ -> decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }

    private fun pixels(bitmap: Bitmap): IntArray =
        IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }

    /** (max, mean) |difference| per channel in 1/255 units, and PSNR in dB. */
    private fun compare(
        a: Bitmap,
        b: Bitmap,
    ): Triple<Int, Double, Double> {
        assertEquals(a.width, b.width)
        assertEquals(a.height, b.height)
        val pa = pixels(a)
        val pb = pixels(b)
        var worst = 0
        var sum = 0.0
        var squares = 0.0
        for (i in pa.indices) {
            for (shift in intArrayOf(16, 8, 0)) {
                val d = abs(((pa[i] shr shift) and 0xFF) - ((pb[i] shr shift) and 0xFF))
                worst = max(worst, d)
                sum += d
                squares += d.toDouble() * d
            }
        }
        val n = pa.size * 3.0
        val mse = squares / n
        return Triple(worst, sum / n, if (mse == 0.0) Double.POSITIVE_INFINITY else 10 * log10(255.0 * 255.0 / mse))
    }

    @Test
    fun jpegInDisplayP3RoundTripsWithItsIccProfile() {
        val settings = ExportSettings(quality = 100, colorSpace = ExportColorSpace.DisplayP3)
        val file = export(settings)
        assertNotNull("ICC profile", JpegSegments.iccOf(file.readBytes()))
        assertEquals(ColorSpace.get(ColorSpace.Named.DISPLAY_P3), sourceColorSpace(file))
        val decoded = decode(file)
        assertEquals(ColorSpace.get(ColorSpace.Named.DISPLAY_P3), decoded.colorSpace)
        val (worst, mean, psnr) = compare(decoded, reference(settings))
        Log.i(TAG, "JPEG P3 q100: max $worst mean $mean PSNR $psnr")
        assertTrue("mean |diff| $mean", mean <= 1.5)
        assertTrue("PSNR $psnr", psnr >= 38.0)
    }

    @Test
    fun srgbJpegGetsLineOversIccProfile() {
        val file = export(ExportSettings(colorSpace = ExportColorSpace.Srgb))
        val icc = JpegSegments.iccOf(file.readBytes())
        assertNotNull(icc)
        assertEquals(ColorSpace.get(ColorSpace.Named.SRGB), decode(file).colorSpace)
    }

    @Test
    fun pngAndWebpLosslessMatchTheRender() {
        for (settings in listOf(
            ExportSettings(format = ExportFormat.Png, colorSpace = ExportColorSpace.DisplayP3),
            ExportSettings(format = ExportFormat.Webp, webpLossless = true, colorSpace = ExportColorSpace.DisplayP3),
        )) {
            val file = export(settings)
            val decoded = decode(file)
            assertEquals(settings.format.label, ColorSpace.get(ColorSpace.Named.DISPLAY_P3), decoded.colorSpace)
            // The files hold the render's codes exactly (output_test and check_export_files.py read
            // them back without colour management). Android colour-manages the decode from the
            // embedded profile into its own Display P3, whose colorants differ from LineOver's by
            // s15Fixed16 rounding: at most one code here and there.
            val (worst, mean, _) = compare(decoded, reference(settings))
            Log.i(TAG, "${settings.format.label}: max $worst mean $mean")
            assertTrue("${settings.format.label} max |diff| $worst", worst <= 1)
            assertTrue("${settings.format.label} mean |diff| $mean", mean <= 0.1)
        }
        val webp = WebpChunks.parse(export(ExportSettings(format = ExportFormat.Webp, colorSpace = ExportColorSpace.DisplayP3)).readBytes())
        assertArrayEquals(ExportImage.iccProfile(ExportColorSpace.DisplayP3), webp.first { it.fourcc == "ICCP" }.data)
        assertTrue(webp.any { it.fourcc == "EXIF" })
    }

    @Test
    fun sixteenBitPngAndTiffAgreeAndCarryRec2020() {
        val tiff = export(ExportSettings(format = ExportFormat.Tiff, bits = 16, colorSpace = ExportColorSpace.Rec2020))
        val (codes, icc) = readTiff16(tiff)
        assertArrayEquals(ExportImage.iccProfile(ExportColorSpace.Rec2020), icc)
        val file = export(ExportSettings(format = ExportFormat.Png, bits = 16, colorSpace = ExportColorSpace.Rec2020))
        // The decoder reads the file's ICC profile as Android's BT.2020 ...
        assertEquals(ColorSpace.get(ColorSpace.Named.BT2020), sourceColorSpace(file))
        // ... and, asked for that space, gives the encoded values as half floats.
        val bt2020 = ColorSpace.get(ColorSpace.Named.BT2020)
        val png =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(bt2020)
            }
        assertEquals(Bitmap.Config.RGBA_F16, png.config)
        val buffer = ByteBuffer.allocate(png.byteCount).order(ByteOrder.nativeOrder())
        png.copyPixelsToBuffer(buffer)
        buffer.rewind()
        val halves = buffer.asShortBuffer()
        var worst = 0.0
        for (i in 0 until png.width * png.height) {
            for (c in 0 until 3) {
                val v = android.util.Half.toFloat(halves.get(i * 4 + c))
                worst = max(worst, abs(v - codes[i * 3 + c] / 65535.0))
            }
        }
        // Half floats hold 11 significant bits: within 1/1024 of the 16-bit codes.
        assertTrue("PNG 16 vs TIFF 16: $worst", worst <= 1.0 / 1024)
    }

    @Test
    fun resizeAndWatermarkShapeTheOutput() {
        val plain = ExportSettings(format = ExportFormat.Png, resizeMode = ResizeMode.LongEdge, resizeValue = 600f)
        val marked = plain.copy(watermark = WatermarkSettings(enabled = true, text = "LineOver", opacity = 100f, scale = 30f))
        val a = decode(export(plain))
        val b = decode(export(marked))
        assertEquals(600, a.width)
        assertEquals(450, a.height)
        // The mark sits bottom right: that corner changed, the top left did not.
        val pa = pixels(a)
        val pb = pixels(b)

        fun changed(
            x0: Int,
            y0: Int,
            x1: Int,
            y1: Int,
        ) = (y0 until y1).sumOf { y -> (x0 until x1).count { x -> pa[y * 600 + x] != pb[y * 600 + x] } }
        assertTrue(changed(400, 380, 600, 450) > 500)
        assertEquals(0, changed(0, 0, 200, 150))
    }

    /** Stage 13: a photo layer is composited into the export (at export size), hidden or 0 % layers are not. */
    @Test
    fun layersCompositeIntoTheExport() {
        val red = Bitmap.createBitmap(LAYER_W, LAYER_H, Bitmap.Config.ARGB_8888)
        red.eraseColor(Color.RED)
        File(dir, "red.png").outputStream().use { red.compress(Bitmap.CompressFormat.PNG, 100, it) }
        red.recycle()
        val scale = W.toFloat() / LAYER_W
        val layer =
            Layer(
                "red",
                type = LayerType.Raster,
                name = "Red",
                source = "red.png",
                sourceSize = listOf(LAYER_W.toFloat(), LAYER_H.toFloat()),
                transform = listOf(scale, 0f, 0f, scale, 0f, 0f),
            )
        val settings = ExportSettings(format = ExportFormat.Png, colorSpace = ExportColorSpace.Srgb)
        val plain = pixels(decode(export(settings)))

        fun withLayer(l: Layer) = pixels(decode(export(settings, graph = LayerMath.add(EditGraph().withBackgroundOps(emptyList()), l))))
        val covered = withLayer(layer)
        // Inside the frame (bilinear edges blend with the transparent outside): pure red.
        for (y in BORDER until H - BORDER step STEP) {
            for (x in BORDER until W - BORDER step STEP) {
                val c = covered[y * W + x]
                assertTrue("($x, $y) = ${Integer.toHexString(c)}", Color.red(c) >= 252 && Color.green(c) <= 3 && Color.blue(c) <= 3)
            }
        }
        for (off in listOf(layer.copy(visible = false), layer.copy(opacity = 0f))) {
            val same = withLayer(off)
            val worst = plain.indices.maxOf { i -> channelDiff(plain[i], same[i]) }
            assertTrue("an invisible layer changed the export by $worst", worst <= 1)
        }
    }

    private fun channelDiff(
        a: Int,
        b: Int,
    ): Int = maxOf(abs(Color.red(a) - Color.red(b)), abs(Color.green(a) - Color.green(b)), abs(Color.blue(a) - Color.blue(b)))

    @Test
    fun exifIsStrippedOfTheLocationByDefault() {
        val kept = ExifInterface(export(ExportSettings()))
        assertEquals("LineOverTest", kept.getAttribute(ExifInterface.TAG_MAKE))
        assertNull(kept.latLong)
        assertEquals(ExifInterface.ORIENTATION_NORMAL, kept.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
        val located = ExifInterface(export(ExportSettings(removeLocation = false)))
        assertNotNull(located.latLong)
        val none = export(ExportSettings(metadata = MetadataMode.None))
        assertNull(JpegSegments.exifOf(none.readBytes()))
        assertNull(ExifInterface(none).getAttribute(ExifInterface.TAG_MAKE))
    }

    @Test
    fun ultraHdrKeepsThePhotosGainMap() {
        val settings =
            ExportSettings(
                format = ExportFormat.UltraHdr,
                colorSpace = ExportColorSpace.DisplayP3,
                resizeMode = ResizeMode.LongEdge,
                resizeValue = 2048f,
            )
        val file = export(settings, source = ultraHdrPhoto())
        val decoded = decode(file)
        assertTrue("gain map", decoded.hasGainmap())
        assertEquals(2048, max(decoded.width, decoded.height))
        assertEquals(ColorSpace.get(ColorSpace.Named.DISPLAY_P3), decoded.colorSpace)
        assertNotNull(JpegSegments.iccOf(file.readBytes()))
    }

    @Test
    fun ultraHdrRebuildsAGainMapFromHighlights() {
        // An SDR source pushed 2 stops over: the highlights above white become the gain map.
        val graph = EditGraph().withBackgroundOps(listOf(OpParams.Exposure(2f)))
        val decoded = decode(export(ExportSettings(format = ExportFormat.UltraHdr), graph = graph))
        assertTrue(decoded.hasGainmap())
        val gainmap = requireNotNull(decoded.gainmap)
        assertTrue("ratio ${gainmap.ratioMax[0]}", gainmap.ratioMax[0] > 1.5f)
    }

    @Test
    fun heifAndAvifWorkWhereTheDeviceHasAnEncoder() {
        // What the sheet offers: encoders found AND a probe file decoded (cached per OS build).
        val capabilities = ExportCapabilities.verify(context)
        for (format in listOf(ExportFormat.Heif, ExportFormat.Avif)) {
            if (!capabilities.supports(format)) {
                // The sheet hides it (the brief's IF WRONG); nothing to export here.
                Log.i(TAG, "${format.label}: no encoder on this device, hidden")
                assertFalse(capabilities.formats.contains(format))
                continue
            }
            val file = export(ExportSettings(format = format, quality = 90))
            val decoded = decode(file)
            assertEquals(W, decoded.width)
            assertEquals(H, decoded.height)
            val (_, mean, psnr) = compare(decoded, reference(ExportSettings(format = format)))
            Log.i(TAG, "${format.label}: mean $mean PSNR $psnr")
            assertTrue("${format.label} PSNR $psnr", psnr >= 30.0)
            assertEquals("LineOverTest", ExifInterface(file).getAttribute(ExifInterface.TAG_MAKE))
        }
    }

    /** RGB 16-bit codes and the ICC profile of LineOver's baseline TIFF. */
    @Suppress("CyclomaticComplexMethod") // one branch per TIFF tag
    private fun readTiff16(file: File): Pair<IntArray, ByteArray> {
        val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val ifd = b.getInt(4)
        val n = b.getShort(ifd).toInt()
        var width = 0
        var height = 0
        var rowsPerStrip = 0
        var strips = 0
        var stripOffsets = 0
        var icc = ByteArray(0)
        for (i in 0 until n) {
            val e = ifd + 2 + 12 * i
            val tag = b.getShort(e).toInt() and 0xFFFF
            val type = b.getShort(e + 2).toInt()
            val count = b.getInt(e + 4)
            val size =
                count * (
                    if (type == 3) {
                        2
                    } else if (type == 4) {
                        4
                    } else {
                        1
                    }
                )
            val at = if (size > 4) b.getInt(e + 8) else e + 8
            val first = if (type == 3) b.getShort(at).toInt() and 0xFFFF else b.getInt(at)
            when (tag) {
                256 -> {
                    width = first
                }

                257 -> {
                    height = first
                }

                278 -> {
                    rowsPerStrip = first
                }

                273 -> {
                    strips = count
                    stripOffsets = at
                }

                34675 -> {
                    icc = ByteArray(count).also { arr -> for (k in 0 until count) arr[k] = b.get(at + k) }
                }
            }
        }
        val codes = IntArray(width * height * 3)
        for (s in 0 until strips) {
            var at = b.getInt(stripOffsets + 4 * s)
            for (y in s * rowsPerStrip until minOf(height, (s + 1) * rowsPerStrip)) {
                for (k in 0 until width * 3) {
                    codes[y * width * 3 + k] = b.getShort(at).toInt() and 0xFFFF
                    at += 2
                }
            }
        }
        return codes to icc
    }

    private companion object {
        const val TAG = "ExportRoundTripTest"
        const val W = 1200
        const val H = 900
        const val LAYER_W = 120
        const val LAYER_H = 90
        const val BORDER = 12
        const val STEP = 37
    }
}
