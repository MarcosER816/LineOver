package dev.mark.lineover.core.designsystem.component

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Phone portrait (compact width, 411 dp). The window is taller than a phone so that a
 * whole state sheet fits in one image; the width is what the test is about.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h1800dp-xhdpi")
class CompactComponentScreenshotTest : ComponentScreenshots("compact")
