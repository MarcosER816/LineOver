package dev.mark.lineover.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.mark.lineover.core.designsystem.LineOverTheme
import dev.mark.lineover.core.designsystem.LoColors
import dev.mark.lineover.core.engine.CompareMode
import dev.mark.lineover.core.engine.Histogram
import dev.mark.lineover.core.model.HslBand
import dev.mark.lineover.core.model.HslChannel
import dev.mark.lineover.core.model.LightAdjustments
import dev.mark.lineover.core.model.LightParam
import dev.mark.lineover.core.model.ToolCategory
import dev.mark.lineover.core.ui.tools.ToolRegistry
import dev.mark.lineover.feature.editor.canvas.PickMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The frozen editor layout (Stage 3): the strip of every category, every side sheet, the
 * menus, tool search and the coach marks, at compact and expanded widths. Robolectric has
 * no GL, so the canvas is a stand-in drawn with the token gradient (never a Compose Image
 * of the photo in production code).
 */
abstract class EditorScreenshots(
    private val widthClass: String,
) {
    @get:Rule
    val compose = createComposeRule()

    private val project = FakeEditorLibrary.PROJECT

    private fun base(category: ToolCategory = ToolCategory.Light) = EditorState(project = project, loaded = true, category = category)

    private var holder: MutableState<EditorState>? = null

    /**
     * Captures [state] through the compose test rule, which runs effects and frames until
     * idle (e.g. the category bar scrolling a restored category into view) before capturing.
     */
    private fun shot(
        name: String,
        state: EditorState,
    ) {
        val current =
            holder ?: mutableStateOf(state).also { h ->
                holder = h
                compose.setContent {
                    LineOverTheme {
                        EditorScreen(
                            h.value,
                            onIntent = {},
                            onBack = {},
                            onExport = {},
                            canvas = { StandInPhoto(it) },
                            autoFocusSearch = false,
                        )
                    }
                }
            }
        current.value = state
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("editor/$widthClass/$name.png")
    }

    /** Menus are popups (their own windows): capture the whole screen. */
    private fun screenShot(
        name: String,
        state: EditorState,
    ) {
        compose.setContent {
            LineOverTheme {
                EditorScreen(
                    state,
                    onIntent = {},
                    onBack = {},
                    onExport = {},
                    canvas = { StandInPhoto(it) },
                    autoFocusSearch = false,
                )
            }
        }
        compose.waitForIdle()
        captureScreenRoboImage("editor/$widthClass/$name.png")
    }

    @Test
    fun categoryStrips() {
        ToolCategory.entries.forEach { category -> shot("category_${category.id}", base(category)) }
    }

    @Test
    fun favoritesWithPinnedTools() =
        shot(
            "category_favorites_pinned",
            base(ToolCategory.Favorites).copy(favorites = listOf("light.exposure", "retouch.spot_healing", "color.hsl_color_mixer")),
        )

    // A tool that is still reserved (Light tools have real panels since Stage 5).
    @Test
    fun toolPanelPlaceholder() = shot("tool_panel", base(ToolCategory.Color).copy(panel = EditorPanel.Tool("color.color_grading")))

    /** Stage 5: the Light panel (collapsed on phones: the selected slider; all of them when wide). */
    @Test
    fun lightPanel() =
        shot("light_panel", base().copy(panel = EditorPanel.Tool("light.shadows"), lightParam = LightParam.Shadows, light = SAMPLE_LIGHT))

    @Test
    fun lightPanelSwipeMode() =
        shot(
            "light_panel_swipe",
            base().copy(
                panel = EditorPanel.Tool("light.exposure"),
                light = SAMPLE_LIGHT,
                swipeMode = true,
                swiping = true,
            ),
        )

    /** Histogram (drawn from engine counts), clipping badge and the swipe HUD over the canvas. */
    @Test
    fun lightOverlays() =
        shot(
            "light_overlays",
            base().copy(
                canvas = FakeEditorCanvas().apply { histogram.value = sampleHistogram() },
                overlays = setOf(EditorState.HISTOGRAM_TOOL, EditorState.CLIPPING_TOOL),
                light = SAMPLE_LIGHT,
                swipeMode = true,
                swiping = true,
            ),
        )

    @Test
    fun compareHold() = shot("compare_hold", base().copy(showingOriginal = true))

    @Test
    fun compareSplit() = shot("compare_split", base().copy(compare = CompareMode.Split, split = 0.4f))

    @Test
    fun compareSideBySide() = shot("compare_side_by_side", base().copy(compare = CompareMode.SideBySide))

    /** Stage 6: the white-balance panel in the editor, and the eyedropper loupe over the canvas. */
    @Test
    fun whiteBalancePanelWithLoupe() =
        shot(
            "color_white_balance_loupe",
            base(ToolCategory.Color).copy(
                panel = EditorPanel.Tool("color.white_balance"),
                color = SAMPLE_COLOR,
                pickMode = PickMode.Eyedropper,
                loupe = Loupe(240f, 520f, FloatArray(9 * 9 * 4) { i -> if (i % 4 == 3) 1f else 0.05f + (i / 4 % 9) * 0.06f }),
            ),
        )

    @Test
    fun hslPanelWithTargetedHud() =
        shot(
            "color_hsl_targeted",
            base(ToolCategory.Color).copy(
                panel = EditorPanel.Tool("color.hsl_color_mixer"),
                color = SAMPLE_COLOR,
                hslChannel = HslChannel.Saturation,
                pickMode = PickMode.Targeted,
                targeted = TargetedHud(HslBand.Orange, HslChannel.Saturation, 35f),
            ),
        )

    @Test
    fun renderStats() = shot("render_stats", base().copy(debuggable = true, renderStats = true))

    @Test
    fun layersSheet() = shot("sheet_layers", base().copy(panel = EditorPanel.Layers))

    @Test
    fun addLayerMenu() = screenShot("menu_add_layer", base().copy(panel = EditorPanel.Layers, menu = EditorMenu.AddLayer))

    @Test
    fun historySheet() {
        HistoryTab.entries.forEach { tab ->
            shot("sheet_history_${tab.name.lowercase()}", base().copy(panel = EditorPanel.History, historyTab = tab))
        }
    }

    @Test
    fun masksSheet() = shot("sheet_masks", base(ToolCategory.Masks))

    @Test
    fun addMaskMenu() = screenShot("menu_add_mask", base(ToolCategory.Masks).copy(menu = EditorMenu.AddMask))

    @Test
    fun selectionOptionsBar() = shot("selection_options", base(ToolCategory.Select).copy(panel = EditorPanel.Tool("select.lasso")))

    @Test
    fun moreMenu() = screenShot("menu_more", base().copy(menu = EditorMenu.More, overlays = setOf("light.histogram")))

    @Test
    fun canvasOverlays() =
        shot(
            "canvas_overlays",
            base().copy(overlays = setOf("light.histogram", "light.clipping_warnings", "workflow.guides_grid_rulers")),
        )

    @Test
    fun info() = shot("panel_info", base().copy(panel = EditorPanel.Info, previewUnavailable = true))

    @Test
    fun toolSearch() = shot("tool_search", base().copy(searchOpen = true, query = "heal"))

    @Test
    fun toolSearchSynonym() = shot("tool_search_synonym", base().copy(searchOpen = true, query = "erase object"))

    @Test
    fun toolSearchEmpty() = shot("tool_search_empty", base().copy(searchOpen = true))

    @Test
    fun coachMarks() {
        (0 until EditorState.COACH_STEPS).forEach { step -> shot("coach_$step", base().copy(coachStep = step)) }
    }

    @Test
    fun unsupportedFormat() = shot("preview_unavailable", base().copy(previewUnavailable = true))

    @Test
    fun everyStripToolHasALabelThatFits() {
        // Not a picture: guards the strip data the screenshots above rely on.
        ToolCategory.entries.filter { it != ToolCategory.Favorites }.forEach { category ->
            check(ToolRegistry.strip(category).isNotEmpty()) { "$category is empty" }
        }
    }
}

internal val SAMPLE_LIGHT =
    LightAdjustments()
        .with(LightParam.Exposure, 0.35f)
        .with(LightParam.Contrast, 18f)
        .with(LightParam.Highlights, -42f)
        .with(LightParam.Shadows, 37f)
        .with(LightParam.Blacks, -8f)

/** Smooth, deterministic counts standing in for an engine histogram. */
internal fun sampleHistogram(): Histogram {
    val bins = IntArray(Histogram.SIZE)
    val centres = floatArrayOf(150f, 110f, 90f, 115f)
    for (c in 0 until Histogram.CHANNELS) {
        for (i in 0 until Histogram.BINS) {
            val d = (i - centres[c]) / 45f
            bins[c * Histogram.BINS + i] =
                (1000 * kotlin.math.exp(-d * d) + 120 * kotlin.math.exp(-((i - 20) / 12f).let { it * it })).toInt()
        }
    }
    bins[Histogram.BINS - 1] = 2500 // clipped highlights in red
    return Histogram(bins)
}

/** A stand-in for the GL canvas in screenshots: a "photo" made of design tokens. */
@Composable
private fun StandInPhoto(modifier: Modifier) {
    Box(modifier.padding(horizontal = 24.dp, vertical = 48.dp)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        listOf(LoColors.TrackTemperatureCool, LoColors.WheelCenter, LoColors.TrackTemperatureWarm),
                    ),
                ),
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class CompactEditorScreenshotTest : EditorScreenshots("compact")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-mdpi")
class ExpandedEditorScreenshotTest : EditorScreenshots("expanded")

/** A phone in landscape uses the wide layout too (rail + right column). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w891dp-h411dp-xhdpi")
class LandscapeEditorScreenshotTest {
    @Test
    fun landscapePhone() {
        captureRoboImage("editor/landscape/editor_landscape.png") {
            LineOverTheme {
                EditorScreen(
                    EditorState(project = FakeEditorLibrary.PROJECT, loaded = true, panel = EditorPanel.Tool("light.exposure")),
                    onIntent = {},
                    onBack = {},
                    onExport = {},
                    canvas = { StandInPhoto(it) },
                )
            }
        }
    }
}
