package dev.mark.lineover.core.engine

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mark.lineover.core.engine.testing.F32Image
import dev.mark.lineover.core.engine.testing.GoldenAssert
import dev.mark.lineover.core.engine.testing.NpyIo
import dev.mark.lineover.core.engine.testing.Tolerance
import dev.mark.lineover.core.engine.testing.U8Image
import dev.mark.lineover.core.io.source.SourceFormat
import dev.mark.lineover.core.model.CropAspect
import dev.mark.lineover.core.model.CropMath
import dev.mark.lineover.core.model.EditGraph
import dev.mark.lineover.core.model.EditGraphJson
import dev.mark.lineover.core.model.GeometryAdjustments
import dev.mark.lineover.core.model.GeometryMath
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The owner's real photos (testdata/photos: POCO F7 Ultra, Ultra HDR, Display P3,
 * 3072 x 4096) through the whole app path, against the Python reference
 * (testdata/golden/photos, 32 x 32 block means):
 *
 * - open: size, Display P3 format, the real gain map, the 2048 px preview;
 * - full resolution: the Android region decoder, P3 -> working space and the CPU ops on
 *   every 512 px tile, block means vs Python's decode of the same JPEG (8-bit tolerance);
 * - GPU: the phone-checklist edits on the 96 x 128 photo vs the reference (3/255).
 */
@RunWith(AndroidJUnit4::class)
class RealPhotoTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val testContext = instrumentation.context
    private val appContext = instrumentation.targetContext
    private val diffDir = File(appContext.cacheDir, "golden-diffs")

    private fun report(line: String) {
        Log.i(TAG, line)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$TAG: $line\n") })
    }

    private fun npy(path: String) = testContext.assets.open(path).use(NpyIo::readF32)

    /** The photo asset copied to a file once per run (the engine opens a Uri). */
    private fun photo(name: String): File {
        val file = File(photoDir, PHOTOS.getValue(name))
        if (file.exists()) return file
        file.parentFile?.mkdirs()
        val bytes = testContext.assets.open(PHOTOS.getValue(name)).use { it.readBytes() }
        file.writeBytes(bytes)
        return file
    }

    private fun graph(op: String?) = """{"schemaVersion":1,"layers":[{"id":"background","ops":[${op ?: ""}]}]}"""

    /** Full-resolution CPU render of [name] with [op], as 32 x 32 block means (96 x 128). */
    private fun renderBlocks(
        name: String,
        op: String?,
    ): F32Image =
        runBlocking {
            val session = requireNotNull(EngineSession.open(appContext, Uri.fromFile(photo(name))))
            try {
                assertNull(session.setGraph(EditGraphJson.decode(graph(op))))
                val bw = session.info.width / BLOCK
                val bh = session.info.height / BLOCK
                val sums = DoubleArray(bw * bh * CHANNELS)
                var tiles = 0
                val started = System.nanoTime()
                session.renderFullResolution({ tx: Int, ty: Int, w: Int, h: Int, pixels: FloatArray ->
                    tiles++
                    for (y in 0 until h) {
                        val by = (ty * TILE + y) / BLOCK
                        for (x in 0 until w) {
                            val block = (by * bw + (tx * TILE + x) / BLOCK) * CHANNELS
                            val p = (y * w + x) * CHANNELS
                            for (c in 0 until CHANNELS) sums[block + c] += pixels[p + c].toDouble()
                        }
                    }
                })
                report("$name ${op?.substringBefore(',') ?: "identity"}: $tiles tiles in ${(System.nanoTime() - started) / 1_000_000} ms")
                assertEquals(session.info.width / TILE * (session.info.height / TILE), tiles)
                val scale = 1.0 / (BLOCK * BLOCK)
                F32Image(bw, bh, CHANNELS, FloatArray(sums.size) { (sums[it] * scale).toFloat() })
            } finally {
                session.close()
            }
        }

    @Test
    fun theRealPhotosOpenAsDisplayP3WithTheirGainMaps() =
        runBlocking {
            for (name in PHOTOS.keys) {
                val session = requireNotNull(EngineSession.open(appContext, Uri.fromFile(photo(name))))
                try {
                    val info = session.info
                    report(
                        "$name: ${info.width}x${info.height} ${info.mimeType} ${info.colorSpaceName} as ${info.format}, " +
                            "orientation ${info.orientation}, preview ${session.preview.bitmap.width}x${session.preview.bitmap.height}",
                    )
                    assertEquals(3072, info.width)
                    assertEquals(4096, info.height)
                    assertEquals(SourceFormat.DisplayP38, info.format)
                    assertTrue("region decodable", info.regionDecodable)
                    assertEquals(PREVIEW_EDGE, maxOf(session.preview.bitmap.width, session.preview.bitmap.height))
                    val gainmap = checkNotNull(session.preview.gainmap) { "$name keeps its Ultra HDR gain map" }
                    report(
                        "$name gain map ${gainmap.gainmapContents.width}x${gainmap.gainmapContents.height}, " +
                            "ratio max ${gainmap.ratioMax.joinToString()}, full HDR at ${gainmap.displayRatioForFullHdr}",
                    )
                    assertTrue("a real gain map brightens highlights", gainmap.ratioMax.max() > 1f)
                } finally {
                    session.close()
                }
            }
        }

    /** Stage 8: version / Home thumbnails are the graph on a <= 512 px copy of the preview. */
    @Test
    fun thumbnailsRenderTheGraph() =
        runBlocking {
            val session = requireNotNull(EngineSession.open(appContext, Uri.fromFile(photo("bird"))))
            try {
                val plain = requireNotNull(session.thumbnail(EditGraphJson.decode(graph(null))))
                val bright = requireNotNull(session.thumbnail(EditGraphJson.decode(graph("""{"op":"exposure","ev":1.0}"""))))
                report("thumbnail ${plain.width}x${plain.height}")
                assertEquals(PREVIEW_EDGE / 4, maxOf(plain.width, plain.height))
                assertEquals(plain.width, bright.width)

                fun mean(b: android.graphics.Bitmap): Double {
                    val px = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
                    return px.sumOf { ((it shr 16) and 0xFF) + ((it shr 8) and 0xFF) + (it and 0xFF) }.toDouble() / px.size
                }
                assertTrue("+1 EV is brighter: ${mean(bright)} vs ${mean(plain)}", mean(bright) > mean(plain) + 20)
            } finally {
                session.close()
            }
        }

    @Test
    fun fullResolutionDecodeMatchesPython() {
        for (name in PHOTOS.keys) {
            GoldenAssert.assertMatches(
                renderBlocks(name, null),
                npy("photos/${name}_rec2020_f32.npy"),
                "photo_${name}_decode",
                DECODE,
                diffDir,
            )
        }
    }

    @Test
    fun fullResolutionChecklistEditsMatchPython() {
        for ((edit, case) in EDITS) {
            val (name, op) = case
            if (edit == HIGHLIGHTS_SHADOWS) continue // image-wide: preview coefficients, checked on the GPU below
            GoldenAssert.assertMatches(renderBlocks(name, op), npy("photos/${edit}_full_f32.npy"), "photo_${edit}_full", DECODE, diffDir)
        }
    }

    @Test
    fun gpuChecklistEditsOnTheRealPhotosMatchTheReference() {
        val engine = NativeEngine(File(appContext.cacheDir, "real-photo-gpu").apply { mkdirs() }.path, 64L shl 20, 64L shl 20)
        try {
            for ((edit, case) in EDITS) {
                val (name, op) = case
                val input = npy("photos/${name}_rec2020_f32.npy")
                val out = ByteArray(input.width * input.height * CHANNELS)
                assertNull(engine.gpuSelfTest(input.data, input.width, input.height, graph(op), out))
                GoldenAssert.assertMatches(
                    U8Image(input.width, input.height, CHANNELS, out),
                    premultiplied8(npy("photos/${edit}_gpu_srgb_display_f32.npy")),
                    "gpu_photo_$edit",
                    Tolerance.GPU_VS_CPU,
                    diffDir,
                )
            }
        } finally {
            engine.close()
        }
    }

    private fun premultiplied8(display: F32Image): U8Image {
        val out = ByteArray(display.data.size)
        for (i in 0 until display.data.size / CHANNELS) {
            val a = display.data[CHANNELS * i + 3]
            for (c in 0 until 3) out[CHANNELS * i + c] = (display.data[CHANNELS * i + c] * a * U8_MAX).roundToInt().toByte()
            out[CHANNELS * i + 3] = (a * U8_MAX).roundToInt().toByte()
        }
        return U8Image(display.width, display.height, CHANNELS, out)
    }

    /** Full-resolution output of [graph] as 32 x 32 block means (output size), and the tile count. */
    private fun outputBlocks(
        session: EngineSession,
        graphJson: String,
    ): Pair<F32Image, Int> =
        runBlocking {
            assertNull(session.setGraph(EditGraphJson.decode(graphJson)))
            val (width, height) = session.outputSize()
            val bw = width / BLOCK
            val bh = height / BLOCK
            val sums = DoubleArray(bw * bh * CHANNELS)
            var tiles = 0
            session.renderFullResolution({ tx: Int, ty: Int, w: Int, h: Int, pixels: FloatArray ->
                tiles++
                for (y in 0 until h) {
                    val by = (ty * TILE + y) / BLOCK
                    if (by >= bh) continue
                    for (x in 0 until w) {
                        val bx = (tx * TILE + x) / BLOCK
                        if (bx >= bw) continue
                        val block = (by * bw + bx) * CHANNELS
                        val p = (y * w + x) * CHANNELS
                        for (c in 0 until CHANNELS) sums[block + c] += pixels[p + c].toDouble()
                    }
                }
            })
            val scale = 1.0 / (BLOCK * BLOCK)
            F32Image(bw, bh, CHANNELS, FloatArray(sums.size) { (sums[it] * scale).toFloat() }) to tiles
        }

    /**
     * Stage 9 export path: the output tiles of a rotated photo are its tiles turned (a quarter
     * turn samples pixel centres exactly, so one resample changes nothing), and the Ultra HDR
     * gain map turns with it.
     */
    @Test
    fun aQuarterTurnRendersTheTurnedPhotoAndItsGainMap() =
        runBlocking {
            val session = requireNotNull(EngineSession.open(appContext, Uri.fromFile(photo("drop"))))
            try {
                val (plain, _) = outputBlocks(session, graph(null))
                val op = requireNotNull(GeometryAdjustments(quarterTurns = 1).toOp(session.info.width, session.info.height))
                val graph = EditGraph().withBackgroundOps(listOf(op))
                val (turned, tiles) = outputBlocks(session, EditGraphJson.encode(graph))
                assertEquals(session.info.height to session.info.width, session.outputSize())
                assertEquals((session.info.height / TILE) * (session.info.width / TILE), tiles)
                // Clockwise: output (x, y) shows source (y, H - 1 - x).
                var worst = 0f
                for (y in 0 until turned.height) {
                    for (x in 0 until turned.width) {
                        val sx = y
                        val sy = plain.height - 1 - x
                        val s = (sy * plain.width + sx) * CHANNELS
                        val t = (y * turned.width + x) * CHANNELS
                        for (c in 0 until 3) worst = maxOf(worst, abs(turned.data[t + c] - plain.data[s + c]))
                    }
                }
                report("quarter turn: max block difference $worst")
                assertTrue("turned blocks equal the photo's ($worst)", worst < 1e-3f)

                val gainmap = checkNotNull(session.preview.gainmap)
                val warped = checkNotNull(session.gainmapFor(graph))
                val contents = gainmap.gainmapContents
                assertEquals(contents.height, warped.gainmapContents.width)
                assertEquals(contents.width, warped.gainmapContents.height)
                // Output pixel (x, y) = gain map pixel (y, h - 1 - x).
                val gx = contents.width / 3
                val gy = contents.height / 4
                val expected = contents.getPixel(gx, gy)
                val actual = warped.gainmapContents.getPixel(contents.height - 1 - gy, gx)
                for (shift in listOf(0, 8, 16, 24)) {
                    val e = (expected shr shift) and 0xFF
                    val a = (actual shr shift) and 0xFF
                    assertTrue("gain map channel at $shift: $e vs $a", abs(e - a) <= 2)
                }
                assertEquals(gainmap.ratioMax.toList(), warped.ratioMax.toList())
            } finally {
                session.close()
            }
        }

    /** A 4:5 crop with straighten (constrained): the output has the crop's size and renders. */
    @Test
    fun aStraightenedFourFiveCropRendersAtItsOutputSize() =
        runBlocking {
            val session = requireNotNull(EngineSession.open(appContext, Uri.fromFile(photo("bird"))))
            try {
                val w = session.info.width
                val h = session.info.height
                val crop = CropMath.fitAspect(floatArrayOf(0f, 0f, 1f, 1f), 4f / 5f, w, h)
                val adjustments =
                    GeometryAdjustments(
                        cropLeft = crop[0],
                        cropTop = crop[1],
                        cropRight = crop[2],
                        cropBottom = crop[3],
                        aspect = CropAspect.FourFive,
                        straighten = 3f,
                    )
                val op = requireNotNull(adjustments.toOp(w, h))
                val (blocks, tiles) = outputBlocks(session, EditGraphJson.encode(EditGraph().withBackgroundOps(listOf(op))))
                assertEquals(op.outputWidth to op.outputHeight, session.outputSize())
                assertEquals(4.0 / 5.0, op.outputWidth.toDouble() / op.outputHeight, 0.01)
                val expectedTiles = ((op.outputWidth + TILE - 1) / TILE) * ((op.outputHeight + TILE - 1) / TILE)
                assertEquals(expectedTiles, tiles)
                // Constrained: no transparent wedge anywhere (every block fully opaque).
                for (i in 0 until blocks.width * blocks.height) assertEquals(1f, blocks.data[i * CHANNELS + 3], 1e-3f)
                report("4:5 straighten 3: ${op.outputWidth}x${op.outputHeight}, $tiles tiles")
            } finally {
                session.close()
            }
        }

    companion object {
        private const val TAG = "LineOverPhotos"
        private const val BLOCK = 32
        private const val TILE = 512
        private const val CHANNELS = 4
        private const val PREVIEW_EDGE = 2048
        private const val U8_MAX = 255f
        private const val HIGHLIGHTS_SHADOWS = "bird_highlights_shadows"

        /** An 8-bit decode (Android's libjpeg-turbo vs OpenCV's), averaged over 32 x 32 blocks. */
        private val DECODE = Tolerance.U8

        private val PHOTOS =
            mapOf(
                "drop" to "IMG_20261002_132928.jpg",
                "bird" to "IMG_20261028_114803.jpg",
                "marigold" to "IMG_20261031_165350.jpg",
            )

        // Kept in step with photo_test.cpp and reference.PHOTO_EDITS / PHOTO_REGION_EDITS.
        private val EDITS =
            mapOf(
                "drop_s_curve" to
                    (
                        "drop" to
                            """{"op":"curves","free":0.0,"rgb":[0,0,0.25,0.18,0.75,0.82,1,1],""" +
                            """"red":[],"green":[],"blue":[],"luma":[]}"""
                    ),
                "bird_teal_orange" to
                    (
                        "bird" to
                            """{"op":"color_grading","blending":50.0,"balance":0.0,"shadows":[200,60,0],"midtones":[0,0,0],""" +
                            """"highlights":[55,50,0],"global":[0,0,0]}"""
                    ),
                "marigold_orange_saturation" to
                    (
                        "marigold" to
                            """{"op":"hsl_mixer","hue":[0,0,0,0,0,0,0,0],"saturation":[0,60,0,0,0,0,0,0],""" +
                            """"luminance":[0,0,0,0,0,0,0,0]}"""
                    ),
                HIGHLIGHTS_SHADOWS to ("bird" to """{"op":"highlights_shadows","highlights":-80.0,"shadows":60.0}"""),
            )

        private val photoDir: File get() = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "real-photos")

        @JvmStatic
        @AfterClass
        fun deletePhotos() {
            photoDir.deleteRecursively()
        }
    }
}
