package dev.mark.lineover.feature.export

import android.net.Uri
import com.github.takahirom.roborazzi.captureRoboImage
import dev.mark.lineover.core.designsystem.LineOverTheme
import dev.mark.lineover.core.model.ExportColorSpace
import dev.mark.lineover.core.model.ExportFormat
import dev.mark.lineover.core.model.ExportSettings
import dev.mark.lineover.core.model.MetadataMode
import dev.mark.lineover.core.model.OutputSharpen
import dev.mark.lineover.core.model.ResizeMode
import dev.mark.lineover.core.model.WatermarkSettings
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The export sheet's states (Stage 12) at compact and expanded widths. */
abstract class ExportSheetScreenshots(
    private val width: String,
) {
    private val base = ExportState("Harbour at dusk", imageSize = 4080 to 3072, estimate = 4_850_000)

    private fun shot(
        name: String,
        state: ExportState,
    ) = captureRoboImage("export/$width/$name.png") { LineOverTheme { ExportSheet(state, onClose = {}) } }

    @Test
    fun exportSheet() = shot("export_sheet", base)

    @Test
    fun sixteenBitRec2020WithWatermark() =
        shot(
            "export_tiff16_watermark",
            base.copy(
                settings =
                    ExportSettings(
                        format = ExportFormat.Tiff,
                        bits = 16,
                        colorSpace = ExportColorSpace.Rec2020,
                        resizeMode = ResizeMode.LongEdge,
                        resizeValue = 3000f,
                        sharpen = OutputSharpen.Standard,
                        metadata = MetadataMode.CopyrightOnly,
                        watermark = WatermarkSettings(enabled = true),
                    ),
                estimate = 54_000_000,
            ),
        )

    @Test
    fun noHeifOrAvifEncoder() =
        shot(
            "export_no_avif",
            base.copy(capabilities = ExportCapabilities(ExportFormat.entries.toSet() - ExportFormat.Heif - ExportFormat.Avif)),
        )

    @Test
    fun running() = shot("export_running", base.copy(status = ExportStatus.Running("Harbour at dusk", ExportStep.Rendering, 0.42f)))

    @Test
    fun done() =
        shot(
            "export_done",
            base.copy(
                status =
                    ExportStatus.Done(
                        Uri.parse("content://media/1"),
                        "Harbour_LineOver.jpg",
                        "image/jpeg",
                        4_850_000,
                        4080,
                        3072,
                        true,
                    ),
            ),
        )

    @Test
    fun failed() =
        shot("export_failed", base.copy(status = ExportStatus.Failed("WebP is limited to 16383 px per side: choose a smaller size")))
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class ExportSheetScreenshotTest : ExportSheetScreenshots("compact")

/** Tablets and landscape: the sheet is centred and at most 640 dp wide. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-mdpi")
class ExpandedExportSheetScreenshotTest : ExportSheetScreenshots("expanded")
