package dev.mark.lineover.feature.settings

import androidx.compose.foundation.layout.Column
import com.github.takahirom.roborazzi.captureRoboImage
import dev.mark.lineover.core.designsystem.LineOverTheme
import dev.mark.lineover.core.ui.adaptive.LoWindowInfo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings: the list (two panes on expanded windows) and single sections. */
abstract class SettingsScreenshots(
    private val widthClass: String,
) {
    private val twoPane: Boolean
        get() {
            val config = RuntimeEnvironment.getApplication().resources.configuration
            return LoWindowInfo.of(config.screenWidthDp.toFloat(), config.screenHeightDp.toFloat()).showsTwoPanes
        }

    private val state = SettingsState(versionName = "0.2.0")

    @Test
    fun settings() {
        val twoPane = twoPane
        captureRoboImage("settings/$widthClass/settings.png") {
            LineOverTheme {
                SettingsScreen(state, onIntent = {}, onBack = {}, onOpenSection = {}, onOpenGallery = {}, twoPane = twoPane)
            }
        }
    }

    @Test
    fun aboutSection() {
        captureRoboImage("settings/$widthClass/settings_about.png") {
            LineOverTheme { SettingsSectionScreen(SettingsSection.About, "0.2.0", onBack = {}, onOpenGallery = null) }
        }
    }

    @Test
    fun reservedSection() {
        captureRoboImage("settings/$widthClass/settings_export_defaults.png") {
            LineOverTheme { SettingsSectionScreen(SettingsSection.ExportDefaults, "0.2.0", onBack = {}, onOpenGallery = null) }
        }
    }

    @Test
    fun aiModelRows() {
        captureRoboImage("settings/$widthClass/ai_model_rows.png") {
            LineOverTheme {
                Column {
                    AiModelRow(AiModelEntry("seg", "Subject selection (sample)", 4_718_592, "Apache-2.0", downloaded = true), onDelete = {})
                    AiModelRow(AiModelEntry("sr", "Super resolution (sample)", 734_003, "MIT", downloaded = false), onDelete = {})
                }
            }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class CompactSettingsScreenshotTest : SettingsScreenshots("compact")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1024dp-h768dp-hdpi")
class ExpandedSettingsScreenshotTest : SettingsScreenshots("expanded")
