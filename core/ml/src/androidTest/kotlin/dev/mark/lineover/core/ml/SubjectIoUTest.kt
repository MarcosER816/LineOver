package dev.mark.lineover.core.ml

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 16, the Definition of Done: Select subject (ML Kit Subject Segmentation, installed by
 * Google Play services) reaches IoU >= 0.85 against the reference mask of each of five CC0 photos
 * (testdata/smart, see SOURCES.md).
 *
 * Play services downloads optional ML Kit modules through the Play Store, so CI runs this class on
 * a google_apis_playstore emulator (ci.yml); the other emulators run every other test.
 */
@RunWith(AndroidJUnit4::class)
class SubjectIoUTest : IoUFixtures() {
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

    private companion object {
        const val TAG = "SubjectIoU"
        const val SUBJECT_IOU = 0.85
    }
}
