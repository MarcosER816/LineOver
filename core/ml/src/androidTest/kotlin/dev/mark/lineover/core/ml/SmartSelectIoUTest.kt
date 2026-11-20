package dev.mark.lineover.core.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer

/**
 * Stage 16, the Definition of Done: the real models on five CC0 photos against reference masks
 * (testdata/smart, see SOURCES.md). Select subject (ML Kit, installed by Google Play services)
 * must reach IoU >= 0.85 on each; Select sky (downloaded and SHA-256 checked by ModelStore) on the
 * landscape. Needs the network on first run, as the app does.
 */
@RunWith(AndroidJUnit4::class)
class SmartSelectIoUTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets

    private val subjects = listOf("cat_tabby", "cat_gray", "labrador", "sheltie", "boxer")

    private val network =
        object : NetworkStatus {
            override fun isOnline() = true

            override fun isUnmetered() = true
        }

    private fun bitmap(name: String): Bitmap = assets.open(name).use { BitmapFactory.decodeStream(it) }.copy(Bitmap.Config.ARGB_8888, false)

    private fun image(name: String): ModelImage {
        val b = bitmap("$name.jpg")
        val bytes = ByteBuffer.allocate(b.width * b.height * 4)
        b.copyPixelsToBuffer(bytes)
        return ModelImage(b.width, b.height, bytes.array())
    }

    private fun truth(name: String): BooleanArray {
        val b = bitmap("${name}_mask.png")
        return BooleanArray(b.width * b.height) { (b.getPixel(it % b.width, it / b.width) and 0xff) > 127 }
    }

    /** IoU of [mask] (>= 0.5, resampled nearest to the reference's size) and [truth]. */
    private fun iou(
        mask: ModelMask,
        truth: BooleanArray,
        w: Int,
        h: Int,
    ): Double {
        var both = 0
        var either = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val mx = minOf(mask.width - 1, (x + 0.5f).times(mask.width).div(w).toInt())
                val my = minOf(mask.height - 1, (y + 0.5f).times(mask.height).div(h).toInt())
                val a = mask.values[my * mask.width + mx] >= 0.5f
                val t = truth[y * w + x]
                if (a && t) both++
                if (a || t) either++
            }
        }
        return both.toDouble() / maxOf(either, 1)
    }

    @Test
    fun selectSubjectReachesIoU085OnEveryPhoto() =
        runBlocking {
            val subject = SubjectSegmenter(context)
            val state = subject.install { null }
            assertEquals("ML Kit subject segmentation module (Google Play services)", ModelState.Ready, state)
            val scores =
                subjects.associateWith { name ->
                    val img = image(name)
                    val mask = subject.segment(img)!!
                    iou(mask, truth(name), img.width, img.height)
                }
            Log.i(TAG, "Select subject IoU: $scores")
            scores.forEach { (name, score) -> assertTrue("$name: IoU $score < 0.85", score >= SUBJECT_IOU) }
            subject.close()
        }

    @Test
    fun selectSkyFindsTheSky() =
        runBlocking {
            val dir = File(context.cacheDir, "iou-models").apply { mkdirs() }
            val store = ModelStore(dir, HttpTransport(), network, { false }, listOf(ModelCatalog.SKY))
            assertEquals(ModelState.Ready, store.download(ModelCatalog.SKY))
            val picker = AcceleratorPicker(PrefsAcceleratorMemory(context))
            val sky = SkySegmenter.open(context, store.file(ModelCatalog.SKY), picker)!!
            val img = image("sky")
            val score = iou(sky.runner.segment(img), truth("sky"), img.width, img.height)
            Log.i(TAG, "Select sky IoU $score on ${sky.accelerator} (${sky.trials})")
            assertTrue("sky: IoU $score < 0.9", score >= SKY_IOU)
            // No sky on the boxer's lawn.
            val dog = image("boxer")
            assertTrue(
                sky.runner
                    .segment(dog)
                    .values
                    .count { it >= 0.5f } < dog.width * dog.height / 50,
            )
            sky.runner.close()
        }

    private companion object {
        const val TAG = "SmartSelectIoU"
        const val SUBJECT_IOU = 0.85
        const val SKY_IOU = 0.9
    }
}
