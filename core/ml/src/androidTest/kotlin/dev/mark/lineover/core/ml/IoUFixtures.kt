package dev.mark.lineover.core.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer

/** The CC0 photos of testdata/smart (assets), their reference masks and the IoU (Stage 16). */
abstract class IoUFixtures {
    protected val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    protected val assets = InstrumentationRegistry.getInstrumentation().context.assets

    protected val subjects = listOf("cat_tabby", "cat_gray", "labrador", "sheltie", "boxer")

    protected val network =
        object : NetworkStatus {
            override fun isOnline() = true

            override fun isUnmetered() = true
        }

    protected fun bitmap(name: String): Bitmap =
        assets.open(name).use { BitmapFactory.decodeStream(it) }.copy(Bitmap.Config.ARGB_8888, false)

    protected fun image(name: String): ModelImage {
        val b = bitmap("$name.jpg")
        val bytes = ByteBuffer.allocate(b.width * b.height * 4)
        b.copyPixelsToBuffer(bytes)
        return ModelImage(b.width, b.height, bytes.array())
    }

    protected fun truth(name: String): BooleanArray {
        val b = bitmap("${name}_mask.png")
        return BooleanArray(b.width * b.height) { (b.getPixel(it % b.width, it / b.width) and 0xff) > 127 }
    }

    /** IoU of [mask] (>= 0.5, resampled nearest to the reference's size) and [truth]. */
    protected fun iou(
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
}
