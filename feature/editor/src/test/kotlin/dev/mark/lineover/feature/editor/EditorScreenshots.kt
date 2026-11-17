package dev.mark.lineover.feature.editor

import com.github.takahirom.roborazzi.captureRoboImage
import dev.mark.lineover.core.designsystem.LineOverTheme
import dev.mark.lineover.core.model.Project
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

abstract class EditorScreenshots(
    private val widthClass: String,
) {
    @Test
    fun placeholder() {
        val project = Project(1, "Harbour at dusk", "content://photo/1", null, 0, 0)
        captureRoboImage("editor/$widthClass/editor_placeholder.png") {
            LineOverTheme { EditorScreen(EditorState(project, loaded = true), onBack = {}, onExport = {}) }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class CompactEditorScreenshotTest : EditorScreenshots("compact")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w891dp-h411dp-xhdpi")
class LandscapeEditorScreenshotTest : EditorScreenshots("landscape")
