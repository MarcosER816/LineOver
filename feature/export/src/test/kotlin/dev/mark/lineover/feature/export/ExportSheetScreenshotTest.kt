package dev.mark.lineover.feature.export

import com.github.takahirom.roborazzi.captureRoboImage
import dev.mark.lineover.core.designsystem.LineOverTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class ExportSheetScreenshotTest {
    @Test
    fun exportSheet() {
        captureRoboImage("export/compact/export_sheet.png") {
            LineOverTheme { ExportSheet(ExportState("Harbour at dusk"), onClose = {}) }
        }
    }
}

/** Tablets and landscape: the sheet is centred and at most 640 dp wide. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-mdpi")
class ExpandedExportSheetScreenshotTest {
    @Test
    fun exportSheet() {
        captureRoboImage("export/expanded/export_sheet.png") {
            LineOverTheme { ExportSheet(ExportState("Harbour at dusk"), onClose = {}) }
        }
    }
}
