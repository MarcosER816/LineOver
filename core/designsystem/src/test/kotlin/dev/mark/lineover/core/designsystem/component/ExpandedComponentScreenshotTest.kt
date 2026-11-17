package dev.mark.lineover.core.designsystem.component

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Tablet / unfolded foldable landscape (expanded width, 1024 dp); tall window, see the compact test. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1024dp-h1400dp-hdpi")
class ExpandedComponentScreenshotTest : ComponentScreenshots("expanded")
