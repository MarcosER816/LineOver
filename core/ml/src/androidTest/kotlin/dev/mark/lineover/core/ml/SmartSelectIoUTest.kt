package dev.mark.lineover.core.ml

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Stage 16: Select sky (downloaded and SHA-256 checked by ModelStore, run by LiteRT) on a CC0
 * landscape against its reference mask (testdata/smart, see SOURCES.md). Select subject's IoU
 * test is SubjectIoUTest (it needs a Play Store image).
 */
@RunWith(AndroidJUnit4::class)
class SmartSelectIoUTest : IoUFixtures() {
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
        const val SKY_IOU = 0.9
    }
}
