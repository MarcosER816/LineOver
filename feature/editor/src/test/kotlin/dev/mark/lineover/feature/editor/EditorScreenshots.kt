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
import dev.mark.lineover.core.model.CropAspect
import dev.mark.lineover.core.model.CropMath
import dev.mark.lineover.core.model.EditGraph
import dev.mark.lineover.core.model.EditHistory
import dev.mark.lineover.core.model.GeometryAdjustments
import dev.mark.lineover.core.model.GeometryMath
import dev.mark.lineover.core.model.GuideLine
import dev.mark.lineover.core.model.HistoryLabels
import dev.mark.lineover.core.model.HslBand
import dev.mark.lineover.core.model.HslChannel
import dev.mark.lineover.core.model.LightAdjustments
import dev.mark.lineover.core.model.LightParam
import dev.mark.lineover.core.model.OpParams
import dev.mark.lineover.core.model.ProjectDocument
import dev.mark.lineover.core.model.Snapshot
import dev.mark.lineover.core.model.ToolCategory
import dev.mark.lineover.core.model.UprightMode
import dev.mark.lineover.core.model.Version
import dev.mark.lineover.core.ui.tools.ToolRegistry
import dev.mark.lineover.feature.editor.canvas.ImageSummary
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

    /**
     * Stage 9: Crop and Optics. The canvas stand-in fills the view; the overlay draws the crop
     * on the fitted frame (dimmed outside, 48 dp handles, the thirds grid while dragging).
     */
    private fun geometryState(
        tool: String,
        geometry: GeometryAdjustments,
        dragging: Boolean = false,
    ) = base(ToolRegistry[tool]!!.category!!).copy(
        image = ImageSummary(4000, 3000, 2048, 1536, "Display P3", "8-bit Display P3", ultraHdr = true),
        geometry = geometry,
        panel = EditorPanel.Tool(tool),
        cropDragging = dragging,
    )

    @Test
    fun cropPanels() {
        val fourFive = CropMath.fitAspect(floatArrayOf(0f, 0f, 1f, 1f), 0.8f, 4000, 3000)
        val crop =
            GeometryAdjustments(
                cropLeft = fourFive[0],
                cropTop = fourFive[1],
                cropRight = fourFive[2],
                cropBottom = fourFive[3],
                aspect = CropAspect.FourFive,
            )
        shot("crop_panel", geometryState(CROP_TOOL, crop))
        shot("crop_dragging_thirds", geometryState(CROP_TOOL, crop, dragging = true))
        shot("crop_straighten", geometryState(STRAIGHTEN_TOOL, crop.copy(straighten = 4.5f), dragging = true))
        shot(
            "crop_perspective",
            geometryState(
                PERSPECTIVE_CROP_TOOL,
                GeometryAdjustments(perspectiveCorners = listOf(0.1f, 0.15f, 0.85f, 0.05f, 0.9f, 0.9f, 0.05f, 0.8f)),
            ),
        )
    }

    @Test
    fun opticsPanels() {
        shot(
            "optics_upright",
            geometryState(UPRIGHT_TOOL, GeometryAdjustments(upright = UprightMode.Auto, uprightPitch = 6f, vertical = 10f)),
        )
        shot(
            "optics_upright_guided",
            geometryState(
                UPRIGHT_TOOL,
                GeometryAdjustments(
                    upright = UprightMode.Guided,
                    guides = listOf(GuideLine(0.2f, 0.1f, 0.24f, 0.9f), GuideLine(0.8f, 0.1f, 0.75f, 0.9f)),
                ),
            ),
        )
        shot("optics_distortion", geometryState(DISTORTION_TOOL, GeometryAdjustments(distortion = 25f)))
        shot("optics_chromatic", geometryState(CHROMATIC_TOOL, GeometryAdjustments(chromaticRed = 30f, chromaticBlue = -15f)))
        shot("optics_vignetting", geometryState(LENS_VIGNETTING_TOOL, GeometryAdjustments(vignetting = 40f)))
    }

    @Test
    fun favoritesWithPinnedTools() =
        shot(
            "category_favorites_pinned",
            base(ToolCategory.Favorites).copy(favorites = listOf("light.exposure", "retouch.spot_healing", "color.hsl_color_mixer")),
        )

    // A tool that is still reserved (Light tools have real panels since Stage 5).
    @Test
    fun toolPanelPlaceholder() = shot("tool_panel", base(ToolCategory.Effects).copy(panel = EditorPanel.Tool("effects.texture")))

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

    /** Stage 7: the curves panel in the editor (full-width editor over the canvas). */
    @Test
    fun curvesPanel() =
        shot(
            "grade_curves",
            base().copy(panel = EditorPanel.Tool("light.curves_point"), grade = SAMPLE_GRADE),
        )

    @Test
    fun colorGradingPanel() =
        shot(
            "grade_color_grading",
            base(ToolCategory.Color).copy(panel = EditorPanel.Tool("color.color_grading"), grade = SAMPLE_GRADE),
        )

    @Test
    fun renderStats() = shot("render_stats", base().copy(debuggable = true, renderStats = true))

    @Test
    fun layersSheet() = shot("sheet_layers", base().copy(panel = EditorPanel.Layers))

    @Test
    fun addLayerMenu() = screenShot("menu_add_layer", base().copy(panel = EditorPanel.Layers, menu = EditorMenu.AddLayer))

    /** A worked-on project: five steps (one undone), two snapshots, two versions with thumbnails. */
    private fun historyState(tab: HistoryTab): EditorState {
        var history = EditHistory.start()
        var ops = listOf<OpParams>()
        listOf(
            OpParams.Exposure(0.7f) to "exposure.ev",
            OpParams.Contrast(25f) to "contrast.amount",
            OpParams.HighlightsShadows(-40f, 0f) to "highlights_shadows.highlights",
            OpParams.Vibrance(30f) to "vibrance.amount",
            OpParams.Whites(15f) to "whites.amount",
        ).forEach { (op, _) ->
            val before = EditGraph().withBackgroundOps(ops)
            ops = ops + op
            val after = EditGraph().withBackgroundOps(ops)
            val change = HistoryLabels.describe(before, after)
            history = history.record(after, change.label, change.key)
        }
        history = history.undo()
        val second = EditHistory.start(EditGraph().withBackgroundOps(listOf(OpParams.Saturation(-100f))), "From Version 1")
        val document =
            ProjectDocument(
                versions = listOf(Version("v1", "Version 1", 1L, history), Version("v2", "Black & white", 2L, second)),
                activeVersion = "v1",
                snapshots =
                    listOf(
                        Snapshot("s1", "Base exposure", 1_700_000_000_000L, history.entries[1].graph),
                        Snapshot("s2", "Before colour", 1_700_000_600_000L, history.entries[3].graph),
                    ),
            )

        fun thumb(color: Int) =
            android.graphics.Bitmap
                .createBitmap(40, 30, android.graphics.Bitmap.Config.ARGB_8888)
                .apply { eraseColor(color) }
        return base().copy(
            panel = EditorPanel.History,
            historyTab = tab,
            history = history,
            graph = history.current,
            document = document,
            versionThumbnails = mapOf("v1" to thumb(0xFF4A7BB7.toInt()), "v2" to thumb(0xFF8A8A8A.toInt())),
        )
    }

    @Test
    fun historySheet() {
        HistoryTab.entries.forEach { tab -> shot("sheet_history_${tab.name.lowercase()}", historyState(tab)) }
        shot("sheet_history_preview", historyState(HistoryTab.History).copy(preview = HistoryPreview.Step(2)))
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
