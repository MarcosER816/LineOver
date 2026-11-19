// The registry is a data table: its numbers are stage numbers, matching docs/TOOL_CATALOG.md.
@file:Suppress("MagicNumber")

package dev.mark.lineover.core.ui.tools

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Immutable
import dev.mark.lineover.core.designsystem.LoIcons
import dev.mark.lineover.core.model.ToolCategory
import dev.mark.lineover.core.model.ToolCategory.Ai
import dev.mark.lineover.core.model.ToolCategory.Auto
import dev.mark.lineover.core.model.ToolCategory.Color
import dev.mark.lineover.core.model.ToolCategory.Crop
import dev.mark.lineover.core.model.ToolCategory.Detail
import dev.mark.lineover.core.model.ToolCategory.Effects
import dev.mark.lineover.core.model.ToolCategory.Favorites
import dev.mark.lineover.core.model.ToolCategory.Filters
import dev.mark.lineover.core.model.ToolCategory.Light
import dev.mark.lineover.core.model.ToolCategory.Liquify
import dev.mark.lineover.core.model.ToolCategory.Masks
import dev.mark.lineover.core.model.ToolCategory.Optics
import dev.mark.lineover.core.model.ToolCategory.Paint
import dev.mark.lineover.core.model.ToolCategory.Presets
import dev.mark.lineover.core.model.ToolCategory.Retouch
import dev.mark.lineover.core.model.ToolCategory.Select
import dev.mark.lineover.core.model.ToolCategory.Shapes
import dev.mark.lineover.core.model.ToolCategory.Text

/** What kind of panel a tool opens once it is built (placeholders show it as a hint). */
enum class PanelType(
    val label: String,
) {
    Sliders("Sliders"),
    Curve("Curve editor"),
    ColorWheels("Colour wheels"),
    Mixer("Per-colour mixer"),
    Gallery("Gallery of looks"),
    Action("One-tap action"),
    Brush("Brush on the canvas"),
    Selection("Selection on the canvas"),
    Mask("Mask on the canvas"),
    Transform("Handles on the canvas"),
    TextEditor("Text editor"),
    AiModel("On-device AI model"),
    Overlay("Canvas overlay"),
    Sheet("Section of a sheet"),
    Workflow("Workflow screen"),
}

/** Where a tool's slot lives outside the category bar (the catalog's Category column). */
enum class ToolPlace(
    val catalogLabel: String?,
) {
    /** In a category's tool strip (`cat/<Category>/nn`). */
    Strip(null),

    /** Overlay drawn on the canvas, toggled from the More menu (`canvas/overlay/…`). */
    CanvasOverlay(null),
    Shell("shell"),
    ExportSheet("export sheet"),
    LayersPanel("layers panel"),
    Import("import"),
    Home("home"),
}

/**
 * One tool of LineOver. [id] is stable forever (favourites and project files store it);
 * [slot] is its reserved place in the frozen layout (rule R9).
 */
@Immutable
data class ToolSpec(
    val id: String,
    val name: String,
    val category: ToolCategory?,
    val place: ToolPlace,
    val slot: String,
    val stage: Int,
    val panelType: PanelType,
    @param:DrawableRes val icon: Int,
    val synonyms: List<String> = emptyList(),
    val done: Boolean = false,
) {
    /** The text of the catalog's Category column. */
    val catalogCategory: String
        get() = place.catalogLabel?.let { "— ($it)" } ?: requireNotNull(category).label
}

/**
 * The single source of truth for every tool, in catalog order. docs/TOOL_CATALOG.md is
 * checked against this list by `ToolCatalogTest`, so the two can never drift apart.
 */
object ToolRegistry {
    val all: List<ToolSpec> = buildTools()

    private val byId: Map<String, ToolSpec> = all.associateBy { it.id }

    operator fun get(id: String): ToolSpec? = byId[id]

    /** The tools of [category]'s tool strip, in slot order. */
    fun strip(category: ToolCategory): List<ToolSpec> = all.filter { it.category == category && it.place == ToolPlace.Strip }

    fun inPlace(place: ToolPlace): List<ToolSpec> = all.filter { it.place == place }
}

@Suppress("LongMethod")
private fun buildTools(): List<ToolSpec> =
    ToolListBuilder()
        .apply {
            strip(Favorites, "favorites.favorites", "Favorites", 28, PanelType.Gallery, LoIcons.Star, "pinned", "starred", "my tools")

            strip(Presets, "presets.presets", "Presets", 11, PanelType.Gallery, LoIcons.Style, "looks", "filters", "styles")
            strip(Presets, "presets.profiles", "Profiles", 11, PanelType.Gallery, LoIcons.PhotoCamera, "camera profile", "base look")
            strip(Presets, "presets.3d_luts", "3D LUTs", 11, PanelType.Gallery, LoIcons.FilterVintage, "lut", "cube", "color lookup")
            strip(
                Presets,
                "presets.copy_paste_settings",
                "Copy/paste settings",
                11,
                PanelType.Action,
                LoIcons.ContentCopy,
                "copy settings",
                "paste settings",
                "copy edits",
            )

            strip(Auto, "auto.auto", "Auto", 11, PanelType.Action, LoIcons.AutoAwesome, "auto fix", "auto enhance", "one tap", "magic")

            strip(
                Light,
                "light.exposure",
                "Exposure",
                5,
                PanelType.Sliders,
                LoIcons.Exposure,
                "ev",
                "brighten",
                "darken",
                "stops",
                done = true,
            )
            strip(Light, "light.contrast", "Contrast", 5, PanelType.Sliders, LoIcons.Contrast, "punch", done = true)
            strip(
                Light,
                "light.highlights",
                "Highlights",
                5,
                PanelType.Sliders,
                LoIcons.WbSunny,
                "bright areas",
                "recover sky",
                done = true,
            )
            strip(Light, "light.shadows", "Shadows", 5, PanelType.Sliders, LoIcons.DarkMode, "dark areas", "lift shadows", done = true)
            strip(Light, "light.whites", "Whites", 5, PanelType.Sliders, LoIcons.BrightnessHigh, "white point", done = true)
            strip(Light, "light.blacks", "Blacks", 5, PanelType.Sliders, LoIcons.BrightnessLow, "black point", done = true)
            strip(Light, "light.brightness", "Brightness", 5, PanelType.Sliders, LoIcons.Brightness6, "lightness", "midtones", done = true)
            strip(
                Light,
                "light.curves_point",
                "Curves (point)",
                7,
                PanelType.Curve,
                LoIcons.ShowChart,
                "tone curve",
                "rgb curve",
                done = true,
            )
            strip(
                Light,
                "light.curves_parametric",
                "Curves (parametric)",
                7,
                PanelType.Curve,
                LoIcons.SsidChart,
                "region curve",
                done = true,
            )
            strip(Light, "light.levels", "Levels", 7, PanelType.Curve, LoIcons.Equalizer, "input levels", "gamma", done = true)
            other(
                Light,
                ToolPlace.CanvasOverlay,
                "light.histogram",
                "Histogram",
                "canvas/overlay/histogram",
                5,
                PanelType.Overlay,
                LoIcons.BarChart,
                done = true,
            )
            other(
                Light,
                ToolPlace.CanvasOverlay,
                "light.clipping_warnings",
                "Clipping warnings",
                "canvas/overlay/clipping",
                5,
                PanelType.Overlay,
                LoIcons.Warning,
                "blown highlights",
                "clipped shadows",
                done = true,
            )

            strip(
                Color,
                "color.white_balance",
                "White balance",
                6,
                PanelType.Sliders,
                LoIcons.WbAuto,
                "wb",
                "temperature",
                "tint",
                "warm",
                "cool",
                "eyedropper",
                done = true,
            )
            strip(Color, "color.vibrance", "Vibrance", 6, PanelType.Sliders, LoIcons.Colorize, done = true)
            strip(
                Color,
                "color.saturation",
                "Saturation",
                6,
                PanelType.Sliders,
                LoIcons.Opacity,
                "colourfulness",
                "desaturate",
                done = true,
            )
            strip(
                Color,
                "color.hsl_color_mixer",
                "HSL / Color Mixer",
                6,
                PanelType.Mixer,
                LoIcons.Tune,
                "hue",
                "hsl",
                "colour mixer",
                done = true,
            )
            strip(Color, "color.invert", "Invert", 6, PanelType.Action, LoIcons.InvertColors, "negative", done = true)
            strip(Color, "color.posterize", "Posterize", 6, PanelType.Sliders, LoIcons.Wallpaper, done = true)
            strip(Color, "color.threshold", "Threshold", 6, PanelType.Sliders, LoIcons.Tonality, done = true)
            strip(
                Color,
                "color.color_grading",
                "Color Grading",
                7,
                PanelType.ColorWheels,
                LoIcons.Colors,
                "colour wheels",
                "grade",
                done = true,
            )
            strip(Color, "color.split_toning", "Split toning", 7, PanelType.ColorWheels, LoIcons.Gradient, "duotone", done = true)
            strip(Color, "color.color_balance", "Color Balance", 7, PanelType.Sliders, LoIcons.Palette, done = true)
            strip(Color, "color.selective_color", "Selective Color", 7, PanelType.Mixer, LoIcons.FormatColorFill, done = true)
            strip(Color, "color.channel_mixer", "Channel Mixer", 7, PanelType.Mixer, LoIcons.Equalizer, "rgb channels", done = true)
            strip(
                Color,
                "color.black_and_white",
                "Black & White",
                7,
                PanelType.Mixer,
                LoIcons.FilterBAndW,
                "bw",
                "b&w",
                "monochrome",
                "grayscale",
                "greyscale",
                done = true,
            )
            strip(
                Color,
                "color.photo_filter",
                "Photo Filter",
                7,
                PanelType.Sliders,
                LoIcons.PhotoFilter,
                "warming filter",
                "cooling filter",
                done = true,
            )
            strip(Color, "color.gradient_map", "Gradient Map", 7, PanelType.Gallery, LoIcons.BlurLinear, done = true)
            strip(
                Color,
                "color.calibration",
                "Calibration",
                7,
                PanelType.Sliders,
                LoIcons.Science,
                "camera calibration",
                "primaries",
                done = true,
            )

            strip(Effects, "effects.texture", "Texture", 10, PanelType.Sliders, LoIcons.Texture, done = true)
            strip(Effects, "effects.clarity", "Clarity", 10, PanelType.Sliders, LoIcons.Deblur, "local contrast", "structure", done = true)
            strip(Effects, "effects.dehaze", "Dehaze", 10, PanelType.Sliders, LoIcons.FilterDrama, "haze", "fog", done = true)
            strip(Effects, "effects.vignette", "Vignette", 10, PanelType.Sliders, LoIcons.Vignette, "dark corners", done = true)
            strip(Effects, "effects.grain", "Grain", 10, PanelType.Sliders, LoIcons.Grain, "film grain", "noise", done = true)

            strip(Detail, "detail.sharpen", "Sharpen", 10, PanelType.Sliders, LoIcons.Deblur, "sharpness", "crisp", done = true)
            strip(
                Detail,
                "detail.noise_reduction",
                "Noise reduction",
                10,
                PanelType.Sliders,
                LoIcons.BlurMedium,
                "denoise",
                "grain removal",
                done = true,
            )

            strip(
                Optics,
                "optics.upright",
                "Upright",
                9,
                PanelType.Transform,
                LoIcons.Straighten,
                "keystone",
                "verticals",
                "perspective",
                done = true,
            )
            strip(
                Optics,
                "optics.lens_distortion",
                "Lens distortion",
                9,
                PanelType.Sliders,
                LoIcons.Lens,
                "barrel",
                "pincushion",
                done = true,
            )
            strip(
                Optics,
                "optics.chromatic_aberration",
                "Chromatic aberration",
                9,
                PanelType.Sliders,
                LoIcons.Flare,
                "fringing",
                "purple fringe",
                done = true,
            )
            strip(
                Optics,
                "optics.lens_vignetting",
                "Lens vignetting",
                9,
                PanelType.Sliders,
                LoIcons.Vignette,
                "corner shading",
                done = true,
            )

            strip(Crop, "crop.crop", "Crop", 9, PanelType.Transform, LoIcons.Crop, "trim", "cut", done = true)
            strip(
                Crop,
                "crop.aspect_ratios",
                "Aspect ratios",
                9,
                PanelType.Transform,
                LoIcons.AspectRatio,
                "ratio",
                "square",
                "16:9",
                done = true,
            )
            strip(
                Crop,
                "crop.straighten",
                "Straighten",
                9,
                PanelType.Transform,
                LoIcons.Straighten,
                "level",
                "horizon",
                "tilt",
                done = true,
            )
            strip(Crop, "crop.rotate", "Rotate", 9, PanelType.Action, LoIcons.RotateRight, "turn", done = true)
            strip(Crop, "crop.flip", "Flip", 9, PanelType.Action, LoIcons.Flip, "mirror", done = true)
            strip(Crop, "crop.perspective_crop", "Perspective crop", 9, PanelType.Transform, LoIcons.CropFree, "keystone crop", done = true)

            strip(
                Masks,
                "masks.linear_gradient_mask",
                "Linear gradient mask",
                17,
                PanelType.Mask,
                LoIcons.BlurLinear,
                "graduated filter",
                "gradient",
            )
            strip(Masks, "masks.radial_gradient_mask", "Radial gradient mask", 17, PanelType.Mask, LoIcons.BlurCircular, "radial filter")
            strip(Masks, "masks.brush_mask", "Brush mask", 17, PanelType.Mask, LoIcons.Brush, "paint mask", "local brush")
            strip(Masks, "masks.color_range_mask", "Color range mask", 17, PanelType.Mask, LoIcons.Colorize)
            strip(Masks, "masks.luminance_range_mask", "Luminance range mask", 17, PanelType.Mask, LoIcons.Tonality, "brightness range")
            strip(Masks, "masks.depth_range_mask", "Depth range mask", 17, PanelType.Mask, LoIcons.Stacks, "depth map")
            strip(Masks, "masks.subject_mask", "Subject mask", 17, PanelType.Mask, LoIcons.FramePerson, "select subject", "person")
            strip(Masks, "masks.sky_mask", "Sky mask", 17, PanelType.Mask, LoIcons.Landscape, "select sky")
            strip(Masks, "masks.background_mask", "Background mask", 17, PanelType.Mask, LoIcons.BackgroundReplace, "select background")
            strip(Masks, "masks.people_mask", "People mask", 17, PanelType.Mask, LoIcons.Face, "faces", "skin", "portrait")

            strip(Select, "select.marquee", "Marquee", 15, PanelType.Selection, LoIcons.SelectAll, "rectangle select", "ellipse select")
            strip(Select, "select.lasso", "Lasso", 15, PanelType.Selection, LoIcons.LassoSelect, "freehand select")
            strip(Select, "select.polygonal_lasso", "Polygonal lasso", 15, PanelType.Selection, LoIcons.Polyline)
            strip(Select, "select.magic_wand", "Magic wand", 15, PanelType.Selection, LoIcons.AutoFixHigh, "wand", "similar colour")
            strip(Select, "select.color_range", "Color range", 15, PanelType.Selection, LoIcons.Colorize)
            strip(Select, "select.magnetic_lasso", "Magnetic lasso", 16, PanelType.Selection, LoIcons.Gesture, "edge snapping")
            strip(Select, "select.quick_selection", "Quick selection", 16, PanelType.Selection, LoIcons.AdsClick, "smart brush select")
            strip(Select, "select.object_select", "Object select", 16, PanelType.Selection, LoIcons.ArrowSelectorTool, "select object")
            strip(
                Select,
                "select.subject_select",
                "Subject select",
                16,
                PanelType.Selection,
                LoIcons.FramePerson,
                "select subject",
                "cut out",
            )
            strip(Select, "select.sky_select", "Sky select", 16, PanelType.Selection, LoIcons.Landscape)
            strip(Select, "select.refine_edge", "Refine edge", 16, PanelType.Selection, LoIcons.Deselect, "refine hair", "select and mask")

            strip(
                Retouch,
                "retouch.spot_healing",
                "Spot healing",
                19,
                PanelType.Brush,
                LoIcons.Healing,
                "heal",
                "blemish",
                "spot",
                "pimple",
                "dust",
            )
            strip(Retouch, "retouch.healing_brush", "Healing brush", 19, PanelType.Brush, LoIcons.Healing, "heal", "repair")
            strip(
                Retouch,
                "retouch.clone_stamp",
                "Clone stamp",
                19,
                PanelType.Brush,
                LoIcons.ContentCopy,
                "clone",
                "stamp",
                "duplicate area",
            )
            strip(Retouch, "retouch.patch", "Patch", 19, PanelType.Brush, LoIcons.LibraryAdd, "patch tool", "heal")
            strip(Retouch, "retouch.red_eye", "Red-eye", 19, PanelType.Brush, LoIcons.Visibility, "red eye", "eyes")
            strip(Retouch, "retouch.dodge", "Dodge", 19, PanelType.Brush, LoIcons.BrightnessHigh, "lighten area")
            strip(Retouch, "retouch.burn", "Burn", 19, PanelType.Brush, LoIcons.BrightnessLow, "darken area")
            strip(Retouch, "retouch.sponge", "Sponge", 19, PanelType.Brush, LoIcons.WaterFull, "saturate area", "desaturate area")
            strip(Retouch, "retouch.blur", "Blur", 19, PanelType.Brush, LoIcons.BlurOn, "soften", "blur brush")
            strip(Retouch, "retouch.sharpen", "Sharpen", 19, PanelType.Brush, LoIcons.Deblur, "sharpen brush")
            strip(Retouch, "retouch.smudge", "Smudge", 19, PanelType.Brush, LoIcons.Gesture, "smear", "finger")
            strip(
                Retouch,
                "retouch.content_aware_fill",
                "Content-aware fill",
                20,
                PanelType.Selection,
                LoIcons.FormatColorFill,
                "fill",
                "remove",
            )
            strip(
                Retouch,
                "retouch.ai_remove",
                "AI remove",
                20,
                PanelType.AiModel,
                LoIcons.InkEraser,
                "remove",
                "erase object",
                "remove object",
                "object eraser",
                "magic eraser",
                "remove people",
                "erase",
            )
            strip(
                Retouch,
                "retouch.content_aware_scale",
                "Content-aware scale",
                20,
                PanelType.Transform,
                LoIcons.PhotoSizeSelectLarge,
                "smart resize",
            )

            strip(Paint, "paint.brush", "Brush", 18, PanelType.Brush, LoIcons.Brush, "paint", "draw")
            strip(Paint, "paint.pencil", "Pencil", 18, PanelType.Brush, LoIcons.Edit, "draw", "sketch")
            strip(Paint, "paint.eraser", "Eraser", 18, PanelType.Brush, LoIcons.InkEraser, "rubber")
            strip(Paint, "paint.mixer_brush", "Mixer brush", 18, PanelType.Brush, LoIcons.FormatPaint, "wet paint", "blend paint")
            strip(Paint, "paint.gradient", "Gradient", 18, PanelType.Brush, LoIcons.Gradient, "gradient fill")
            strip(Paint, "paint.paint_bucket", "Paint bucket", 18, PanelType.Brush, LoIcons.FormatColorFill, "bucket", "flood fill")
            strip(Paint, "paint.pattern", "Pattern", 18, PanelType.Brush, LoIcons.Pattern, "pattern fill", "texture fill")
            strip(Paint, "paint.color_picker", "Color picker", 18, PanelType.Brush, LoIcons.Colorize, "eyedropper", "pick colour", "sample")

            strip(Filters, "filters.gaussian_blur", "Gaussian blur", 21, PanelType.Sliders, LoIcons.BlurOn, "blur", "soften")
            strip(Filters, "filters.box_blur", "Box blur", 21, PanelType.Sliders, LoIcons.BlurMedium, "blur")
            strip(Filters, "filters.motion_blur", "Motion blur", 21, PanelType.Sliders, LoIcons.MotionBlur, "speed", "movement")
            strip(Filters, "filters.radial_blur", "Radial blur", 21, PanelType.Sliders, LoIcons.BlurCircular, "zoom blur", "spin blur")
            strip(Filters, "filters.lens_blur", "Lens blur", 21, PanelType.Sliders, LoIcons.LensBlur, "depth of field", "background blur")
            strip(Filters, "filters.surface_blur", "Surface blur", 21, PanelType.Sliders, LoIcons.BlurShort, "smooth skin")
            strip(Filters, "filters.tilt_shift", "Tilt-shift", 21, PanelType.Sliders, LoIcons.FilterTiltShift, "miniature")
            strip(Filters, "filters.bokeh", "Bokeh", 21, PanelType.Sliders, LoIcons.BubbleChart, "light balls")
            strip(Filters, "filters.high_pass", "High pass", 21, PanelType.Sliders, LoIcons.Contrast, "sharpen layer")
            strip(Filters, "filters.emboss", "Emboss", 21, PanelType.Sliders, LoIcons.Texture, "relief")
            strip(Filters, "filters.distort", "Distort", 21, PanelType.Sliders, LoIcons.Transform, "twirl", "pinch", "ripple", "wave")
            strip(Filters, "filters.noise", "Noise", 21, PanelType.Sliders, LoIcons.Grain, "add noise")
            strip(Filters, "filters.pixelate", "Pixelate", 21, PanelType.Sliders, LoIcons.Grid4x4, "mosaic", "censor")
            strip(Filters, "filters.stylize", "Stylize", 21, PanelType.Sliders, LoIcons.AutoAwesomeMosaic, "oil paint", "sketch", "cartoon")
            strip(Filters, "filters.filter_gallery", "Filter gallery", 21, PanelType.Gallery, LoIcons.GalleryThumbnail, "all filters")

            strip(Liquify, "liquify.liquify", "Liquify", 22, PanelType.Brush, LoIcons.WaterDrop, "push", "warp brush", "reshape")
            strip(
                Liquify,
                "liquify.face_aware_liquify",
                "Face-aware liquify",
                22,
                PanelType.AiModel,
                LoIcons.Face,
                "face shape",
                "slim face",
                "eyes",
            )
            strip(Liquify, "liquify.free_transform", "Free transform", 22, PanelType.Transform, LoIcons.OpenWith, "scale", "skew", "resize")
            strip(Liquify, "liquify.warp", "Warp", 22, PanelType.Transform, LoIcons.Transform, "mesh warp", "bend")
            strip(Liquify, "liquify.puppet_warp", "Puppet warp", 22, PanelType.Transform, LoIcons.Gesture, "pins", "pose")

            strip(Text, "text.text", "Text", 23, PanelType.TextEditor, LoIcons.TextFields, "type", "caption", "words", "font", "title")

            strip(Shapes, "shapes.shapes", "Shapes", 24, PanelType.Transform, LoIcons.Shapes, "rectangle", "circle", "arrow", "line")
            strip(Shapes, "shapes.pen", "Pen", 24, PanelType.Transform, LoIcons.InkPen, "path", "bezier", "vector")
            strip(Shapes, "shapes.curvature_pen", "Curvature pen", 24, PanelType.Transform, LoIcons.Draw, "curve path")
            strip(
                Shapes,
                "shapes.path_selection",
                "Path selection",
                24,
                PanelType.Selection,
                LoIcons.ArrowSelectorTool,
                "select path",
                "anchor",
            )

            strip(
                Ai,
                "ai.background_removal",
                "Background removal",
                26,
                PanelType.AiModel,
                LoIcons.BackgroundReplace,
                "remove background",
                "cut out",
                "remove bg",
            )
            strip(
                Ai,
                "ai.super_resolution",
                "Super resolution",
                26,
                PanelType.AiModel,
                LoIcons.PhotoSizeSelectLarge,
                "upscale",
                "enlarge",
                "enhance",
            )
            strip(Ai, "ai.ai_denoise", "AI denoise", 26, PanelType.AiModel, LoIcons.BlurOff, "denoise", "noise")
            strip(Ai, "ai.sky_replacement", "Sky replacement", 26, PanelType.AiModel, LoIcons.Landscape, "replace sky", "new sky")
            strip(
                Ai,
                "ai.portrait_retouch",
                "Portrait retouch",
                26,
                PanelType.AiModel,
                LoIcons.FaceRetouchingNatural,
                "skin smoothing",
                "beauty",
            )
            strip(Ai, "ai.depth_blur", "Depth blur", 26, PanelType.AiModel, LoIcons.LensBlur, "portrait mode", "bokeh", "background blur")
            strip(
                Ai,
                "ai.generative_fill",
                "Generative fill",
                26,
                PanelType.AiModel,
                LoIcons.AutoAwesome,
                "expand",
                "fill",
                "outpaint",
                "inpaint",
            )

            shell(
                "history.undo_redo",
                "Undo / redo",
                "topbar/undo-redo",
                8,
                PanelType.Action,
                LoIcons.Undo,
                "undo",
                "redo",
                "step back",
                done = true,
            )
            shell(
                "history.history_list",
                "History list",
                "panel/history/list",
                8,
                PanelType.Sheet,
                LoIcons.History,
                "history",
                "steps",
                done = true,
            )
            shell(
                "history.snapshots",
                "Snapshots",
                "panel/history/snapshots",
                8,
                PanelType.Sheet,
                LoIcons.Bookmark,
                "snapshot",
                "save state",
                done = true,
            )
            shell(
                "history.versions",
                "Versions",
                "panel/history/versions",
                8,
                PanelType.Sheet,
                LoIcons.Stacks,
                "version",
                "variants",
                done = true,
            )
            shell(
                "history.before_after",
                "Before / after",
                "topbar/before-after",
                5,
                PanelType.Action,
                LoIcons.Compare,
                "compare",
                "original",
                done = true,
            )
            shell(
                "history.project_file",
                "Project file (.loproj)",
                "home/projects",
                8,
                PanelType.Workflow,
                LoIcons.Folder,
                "loproj",
                "save project",
                done = true,
            )

            exportOption("export.jpeg", "JPEG", "format/01", LoIcons.Image, "jpg")
            exportOption("export.png", "PNG", "format/02", LoIcons.Image, "lossless")
            exportOption("export.webp", "WebP", "format/03", LoIcons.Image)
            exportOption("export.heif", "HEIF", "format/04", LoIcons.Image, "heic")
            exportOption("export.avif", "AVIF", "format/05", LoIcons.Image)
            exportOption("export.tiff", "TIFF", "format/06", LoIcons.Image, "tif", "16 bit")
            exportOption("export.ultra_hdr", "Ultra HDR", "format/07", LoIcons.HdrOn, "hdr", "gain map")
            exportOption("export.resize", "Resize", "options/01", LoIcons.PhotoSizeSelectLarge, "size", "long edge", "megapixels")
            exportOption("export.metadata", "Metadata", "options/02", LoIcons.Info, "exif", "location", "gps")
            exportOption("export.watermark", "Watermark", "options/03", LoIcons.TextFields, "signature", "copyright")
            exportOption("export.icc_profile", "ICC profile", "options/04", LoIcons.Lens, "colour space", "srgb", "display p3")

            layers("layers.raster_layer", "Raster layer", "add/01", 13, PanelType.Sheet, LoIcons.Layers, "new layer", "pixel layer")
            layers("layers.adjustment_layer", "Adjustment layer", "add/02", 13, PanelType.Sheet, LoIcons.Tune)
            layers("layers.group", "Group", "add/03", 13, PanelType.Sheet, LoIcons.Folder, "layer group", "folder")
            layers("layers.smart_object", "Smart object", "add/04", 13, PanelType.Sheet, LoIcons.DataObject, "embedded")
            layers("layers.opacity", "Opacity", "props/01", 13, PanelType.Sheet, LoIcons.Opacity, "transparency")
            layers("layers.fill", "Fill", "props/02", 13, PanelType.Sheet, LoIcons.FormatColorFill, "fill opacity")
            layers(
                "layers.blend_modes",
                "Blend modes (27)",
                "props/03",
                13,
                PanelType.Sheet,
                LoIcons.Layers,
                "blend",
                "multiply",
                "screen",
                "overlay",
            )
            layers("layers.layer_mask", "Layer mask", "mask/01", 14, PanelType.Mask, LoIcons.Gradient, "mask")
            layers("layers.clipping_mask", "Clipping mask", "mask/02", 14, PanelType.Mask, LoIcons.FlipToFront, "clip")
            layers("layers.vector_mask", "Vector mask", "mask/03", 24, PanelType.Mask, LoIcons.InkPen, "path mask")

            other(
                null,
                ToolPlace.Import,
                "raw.raw_development",
                "RAW development",
                "home/import/raw",
                25,
                PanelType.Sliders,
                LoIcons.PhotoCamera,
                "raw",
                "dng",
                "nef",
                "cr3",
                "arw",
            )

            home("merge.hdr_merge", "HDR merge", "home/merge/01", 27, LoIcons.HdrOn, "hdr", "bracketing", "exposure merge")
            home("merge.panorama", "Panorama", "home/merge/02", 27, LoIcons.Panorama, "stitch", "pano")
            home("merge.focus_stacking", "Focus stacking", "home/merge/03", 27, LoIcons.CenterFocusStrong, "focus stack", "macro")

            shell("workflow.batch", "Batch", "home/batch", 28, PanelType.Workflow, LoIcons.Stacks, "batch edit", "many photos")
            shell("workflow.sync_settings", "Sync settings", "home/sync-settings", 28, PanelType.Workflow, LoIcons.Sync, "apply to all")
            shell(
                "workflow.tool_search",
                "Tool search",
                "topbar/tool-search",
                3,
                PanelType.Workflow,
                LoIcons.Search,
                "find tool",
                done = true,
            )
            shell(
                "workflow.mini_tutorials",
                "Mini tutorials",
                "settings/tutorials",
                28,
                PanelType.Workflow,
                LoIcons.School,
                "help",
                "tutorial",
                "learn",
            )
            shell(
                "workflow.guides_grid_rulers",
                "Guides / grid / rulers",
                "canvas/overlay/guides",
                28,
                PanelType.Overlay,
                LoIcons.GridOn,
                "grid",
                "guides",
                "rulers",
                "rule of thirds",
            )
        }.tools

private class ToolListBuilder {
    val tools = mutableListOf<ToolSpec>()
    private val positions = mutableMapOf<ToolCategory, Int>()

    @Suppress("LongParameterList")
    fun strip(
        category: ToolCategory,
        id: String,
        name: String,
        stage: Int,
        panelType: PanelType,
        @DrawableRes icon: Int,
        vararg synonyms: String,
        done: Boolean = false,
    ) {
        val position = positions.getOrDefault(category, 0) + 1
        positions[category] = position
        val slot = "cat/${category.label}/${position.toString().padStart(2, '0')}"
        tools += ToolSpec(id, name, category, ToolPlace.Strip, slot, stage, panelType, icon, synonyms.toList(), done)
    }

    @Suppress("LongParameterList")
    fun other(
        category: ToolCategory?,
        place: ToolPlace,
        id: String,
        name: String,
        slot: String,
        stage: Int,
        panelType: PanelType,
        @DrawableRes icon: Int,
        vararg synonyms: String,
        done: Boolean = false,
    ) {
        tools += ToolSpec(id, name, category, place, slot, stage, panelType, icon, synonyms.toList(), done)
    }

    @Suppress("LongParameterList")
    fun shell(
        id: String,
        name: String,
        slot: String,
        stage: Int,
        panelType: PanelType,
        @DrawableRes icon: Int,
        vararg synonyms: String,
        done: Boolean = false,
    ) {
        tools += ToolSpec(id, name, null, ToolPlace.Shell, slot, stage, panelType, icon, synonyms.toList(), done)
    }

    fun exportOption(
        id: String,
        name: String,
        slot: String,
        @DrawableRes icon: Int,
        vararg synonyms: String,
    ) {
        tools +=
            ToolSpec(id, name, null, ToolPlace.ExportSheet, "sheet/export/$slot", EXPORT_STAGE, PanelType.Sheet, icon, synonyms.toList())
    }

    @Suppress("LongParameterList")
    fun layers(
        id: String,
        name: String,
        slot: String,
        stage: Int,
        panelType: PanelType,
        @DrawableRes icon: Int,
        vararg synonyms: String,
    ) {
        tools += ToolSpec(id, name, null, ToolPlace.LayersPanel, "panel/layers/$slot", stage, panelType, icon, synonyms.toList())
    }

    @Suppress("LongParameterList")
    fun home(
        id: String,
        name: String,
        slot: String,
        stage: Int,
        @DrawableRes icon: Int,
        vararg synonyms: String,
    ) {
        tools += ToolSpec(id, name, null, ToolPlace.Home, slot, stage, PanelType.Workflow, icon, synonyms.toList())
    }

    private companion object {
        const val EXPORT_STAGE = 12
    }
}
