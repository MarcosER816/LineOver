package dev.mark.lineover

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.mark.lineover.core.designsystem.LoColors
import dev.mark.lineover.core.designsystem.LoIcons
import dev.mark.lineover.core.designsystem.LoSpacing
import dev.mark.lineover.core.designsystem.LoType
import dev.mark.lineover.core.designsystem.component.LoChip
import dev.mark.lineover.core.designsystem.component.LoIconButton
import dev.mark.lineover.core.ml.ModelImage
import dev.mark.lineover.core.ml.ModelMask
import dev.mark.lineover.core.ml.ModelState
import dev.mark.lineover.core.ml.SubjectSegmenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.Locale

/** Debug menu: the component gallery and the AI self-test. */
@Composable
internal fun DebugMenu(
    onBack: () -> Unit,
    onGallery: () -> Unit,
    onAiSelfTest: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(LoColors.Bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LoIconButton(LoIcons.ArrowBack, "Back", onClick = onBack)
            Text("Debug", style = LoType.Headline, color = LoColors.TextPrimary)
        }
        Column(Modifier.padding(LoSpacing.M), verticalArrangement = Arrangement.spacedBy(LoSpacing.S)) {
            LoChip("Component gallery", selected = false, onClick = onGallery)
            LoChip("AI self-test (Select subject IoU)", selected = false, onClick = onAiSelfTest, leadingIcon = LoIcons.AutoFixHigh)
        }
    }
}

/**
 * Stage 16, plan B for the subject IoU test (R11): ML Kit's subject module cannot run on the CI
 * emulators (its graph returns no tensor there), so this runs SubjectIoUTest's check on the phone:
 * Select subject on the five CC0 photos of testdata/smart against their reference masks, IoU >= 0.85
 * each, and the time of each run. Debug builds only.
 */
@Composable
internal fun AiSelfTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lines = remember { mutableStateListOf<String>() }
    var running by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(LoColors.Bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LoIconButton(LoIcons.ArrowBack, "Back", onClick = onBack)
            Text("AI self-test", style = LoType.Headline, color = LoColors.TextPrimary)
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(LoSpacing.M),
            verticalArrangement = Arrangement.spacedBy(LoSpacing.Xs),
        ) {
            Text(
                "Runs Select subject (ML Kit) on 5 CC0 photos and compares each result with its reference mask. " +
                    "Pass: IoU ≥ 0.85 on every photo. The first run installs the model (needs the network).",
                style = LoType.Body,
                color = LoColors.TextSecondary,
            )
            LoChip(
                if (running) "Running…" else "Run the IoU test",
                selected = false,
                enabled = !running,
                onClick = {
                    running = true
                    lines.clear()
                    scope.launch {
                        runSubjectIoU(context) { lines += it }
                        running = false
                    }
                },
            )
            lines.forEach { Text(it, style = LoType.Body, color = LoColors.TextPrimary) }
        }
    }
}

private val PHOTOS = listOf("cat_tabby", "cat_gray", "labrador", "sheltie", "boxer")
private const val PASS_IOU = 0.85

private suspend fun runSubjectIoU(
    context: Context,
    report: (String) -> Unit,
) {
    val segmenter = SubjectSegmenter(context)
    try {
        val state = segmenter.install { null }
        if (state != ModelState.Ready) return report("Model not ready: $state")
        var passed = 0
        for (name in PHOTOS) {
            val (image, truth) = withContext(Dispatchers.IO) { photo(context, name) to mask(context, name) }
            val start = System.nanoTime()
            val result = segmenter.segment(image)
            val ms = (System.nanoTime() - start) / NANOS_PER_MS
            if (result == null) {
                report("$name: no result")
                continue
            }
            val score = iou(result, truth, image.width, image.height)
            if (score >= PASS_IOU) passed++
            report(String.format(Locale.ROOT, "%s: IoU %.3f %s (%d ms)", name, score, if (score >= PASS_IOU) "PASS" else "FAIL", ms))
        }
        report("$passed / ${PHOTOS.size} passed (IoU ≥ $PASS_IOU)")
    } finally {
        segmenter.close()
    }
}

private fun bitmap(
    context: Context,
    name: String,
): Bitmap =
    context.assets
        .open(name)
        .use { BitmapFactory.decodeStream(it) }
        .copy(Bitmap.Config.ARGB_8888, false)

private fun photo(
    context: Context,
    name: String,
): ModelImage {
    val b = bitmap(context, "$name.jpg")
    val bytes = ByteBuffer.allocate(b.width * b.height * RGBA)
    b.copyPixelsToBuffer(bytes)
    return ModelImage(b.width, b.height, bytes.array())
}

private fun mask(
    context: Context,
    name: String,
): BooleanArray {
    val b = bitmap(context, "${name}_mask.png")
    return BooleanArray(b.width * b.height) { (b.getPixel(it % b.width, it / b.width) and BYTE) > HALF_BYTE }
}

/** IoU of [mask] (>= 0.5, nearest to the reference's size) and [truth] (the same as SubjectIoUTest). */
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
            val mx = minOf(mask.width - 1, ((x + HALF) * mask.width / w).toInt())
            val my = minOf(mask.height - 1, ((y + HALF) * mask.height / h).toInt())
            val a = mask.values[my * mask.width + mx] >= HALF
            val t = truth[y * w + x]
            if (a && t) both++
            if (a || t) either++
        }
    }
    return both.toDouble() / maxOf(either, 1)
}

private const val RGBA = 4
private const val BYTE = 0xff
private const val HALF_BYTE = 127
private const val HALF = 0.5f
private const val NANOS_PER_MS = 1_000_000L
