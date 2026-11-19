package dev.mark.lineover.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Export file formats (Stage 12), in the sheet's slot order. */
@Serializable
enum class ExportFormat(
    val id: String,
    val label: String,
    val extension: String,
    val mimeType: String,
    /** Bit depths it can write. */
    val bits: List<Int>,
    /** Colour spaces it can tag (HEIF / AVIF go through the platform encoder, which writes sRGB). */
    val spaces: List<ExportColorSpace>,
) {
    @SerialName("jpeg")
    Jpeg("export.jpeg", "JPEG", "jpg", "image/jpeg", listOf(8), ExportColorSpace.SDR),

    @SerialName("png")
    Png("export.png", "PNG", "png", "image/png", listOf(8, 16), ExportColorSpace.entries),

    @SerialName("webp")
    Webp("export.webp", "WebP", "webp", "image/webp", listOf(8), ExportColorSpace.SDR),

    @SerialName("heif")
    Heif("export.heif", "HEIF", "heic", "image/heif", listOf(8), listOf(ExportColorSpace.Srgb)),

    @SerialName("avif")
    Avif("export.avif", "AVIF", "avif", "image/avif", listOf(8), listOf(ExportColorSpace.Srgb)),

    @SerialName("tiff")
    Tiff("export.tiff", "TIFF", "tif", "image/tiff", listOf(8, 16), ExportColorSpace.entries),

    @SerialName("ultra_hdr")
    UltraHdr("export.ultra_hdr", "Ultra HDR", "jpg", "image/jpeg", listOf(8), ExportColorSpace.SDR),
    ;

    /** Lossy encoders take a quality (WebP also has a lossless mode). */
    val lossy: Boolean get() = this != Png && this != Tiff

    val hasQuality: Boolean get() = lossy
}

/** Output colour spaces; [id] is the engine's (output.h Space). Rec. 2020 needs 16 bits. */
@Serializable
enum class ExportColorSpace(
    val id: Int,
    val label: String,
) {
    @SerialName("srgb")
    Srgb(0, "sRGB"),

    @SerialName("p3")
    DisplayP3(1, "Display P3"),

    @SerialName("rec2020")
    Rec2020(2, "Rec. 2020"),
    ;

    companion object {
        val SDR = listOf(Srgb, DisplayP3)
    }
}

@Serializable
enum class ResizeMode {
    @SerialName("original")
    Original,

    @SerialName("long_edge")
    LongEdge,

    @SerialName("megapixels")
    Megapixels,

    @SerialName("percent")
    Percent,
}

@Serializable
enum class OutputSharpen(
    val label: String,
    /** output.h kSharpen*; 0 = off. */
    val amount: Float,
) {
    @SerialName("none")
    None("Off", 0f),

    @SerialName("low")
    Low("Low", 0.35f),

    @SerialName("standard")
    Standard("Standard", 0.7f),

    @SerialName("high")
    High("High", 1.2f),
}

@Serializable
enum class MetadataMode(
    val label: String,
) {
    @SerialName("all")
    All("Keep all"),

    @SerialName("copyright")
    CopyrightOnly("Copyright only"),

    @SerialName("none")
    None("None"),
}

@Serializable
enum class WatermarkKind {
    @SerialName("text")
    Text,

    @SerialName("image")
    Image,
}

@Serializable
enum class WatermarkPosition(
    val label: String,
    /** Anchor in 0..1 of the free space (0 = left / top). */
    val fx: Float,
    val fy: Float,
) {
    @SerialName("top_left")
    TopLeft("Top left", 0f, 0f),

    @SerialName("top_right")
    TopRight("Top right", 1f, 0f),

    @SerialName("center")
    Center("Centre", 0.5f, 0.5f),

    @SerialName("bottom_left")
    BottomLeft("Bottom left", 0f, 1f),

    @SerialName("bottom_right")
    BottomRight("Bottom right", 1f, 1f),
}

@Serializable
data class WatermarkSettings(
    val enabled: Boolean = false,
    val kind: WatermarkKind = WatermarkKind.Text,
    val text: String = DEFAULT_TEXT,
    val position: WatermarkPosition = WatermarkPosition.BottomRight,
    /** 0..100 %. */
    val opacity: Float = 70f,
    /** Width of the mark, % of the image's long edge (2..50). */
    val scale: Float = 18f,
) {
    companion object {
        const val DEFAULT_TEXT = "© Mark"
    }
}

/**
 * Everything the export sheet sets (remembered between exports). [validated] makes any
 * combination writable: a format's bit depths and colour spaces win, Rec. 2020 needs 16 bits.
 */
@Serializable
data class ExportSettings(
    val format: ExportFormat = ExportFormat.Jpeg,
    /** 1..100. */
    val quality: Int = DEFAULT_QUALITY,
    val webpLossless: Boolean = false,
    val bits: Int = 8,
    val colorSpace: ExportColorSpace = ExportColorSpace.DisplayP3,
    val resizeMode: ResizeMode = ResizeMode.Original,
    /** Pixels (long edge), megapixels or percent, by [resizeMode]. */
    val resizeValue: Float = DEFAULT_LONG_EDGE,
    val sharpen: OutputSharpen = OutputSharpen.None,
    val metadata: MetadataMode = MetadataMode.All,
    val removeLocation: Boolean = true,
    val copyright: String = WatermarkSettings.DEFAULT_TEXT,
    val watermark: WatermarkSettings = WatermarkSettings(),
) {
    fun validated(): ExportSettings {
        val f = format
        val b = if (bits in f.bits) bits else f.bits.first()
        val space =
            when {
                colorSpace !in f.spaces -> f.spaces.firstOrNull { it == ExportColorSpace.DisplayP3 } ?: f.spaces.first()
                colorSpace == ExportColorSpace.Rec2020 && b < WIDE_BITS -> ExportColorSpace.DisplayP3
                else -> colorSpace
            }
        val value =
            when (resizeMode) {
                ResizeMode.Original -> resizeValue
                ResizeMode.LongEdge -> resizeValue.coerceIn(MIN_EDGE.toFloat(), MAX_EDGE.toFloat())
                ResizeMode.Megapixels -> resizeValue.coerceIn(MIN_MEGAPIXELS, MAX_MEGAPIXELS)
                ResizeMode.Percent -> resizeValue.coerceIn(MIN_PERCENT, MAX_PERCENT)
            }
        return copy(
            quality = quality.coerceIn(1, MAX_QUALITY),
            bits = b,
            colorSpace = space,
            resizeValue = value,
            watermark =
                watermark.copy(
                    opacity = watermark.opacity.coerceIn(0f, MAX_PERCENT),
                    scale = watermark.scale.coerceIn(MIN_MARK_SCALE, MAX_MARK_SCALE),
                ),
        )
    }

    /** The exported size for an edited image of [width] x [height]; never larger than it. */
    fun outputSize(
        width: Int,
        height: Int,
    ): Pair<Int, Int> {
        val long = max(width, height).toFloat()
        val factor =
            when (resizeMode) {
                ResizeMode.Original -> 1f
                ResizeMode.LongEdge -> resizeValue / long
                ResizeMode.Megapixels -> sqrt(resizeValue * MEGA / (width.toFloat() * height))
                ResizeMode.Percent -> resizeValue / MAX_PERCENT
            }.coerceAtMost(1f)
        if (factor >= 1f) return width to height
        return max(1, (width * factor).roundToInt()) to max(1, (height * factor).roundToInt())
    }

    /** "IMG_1234_LineOver.jpg": the photo's name with the format's extension. */
    fun fileName(baseName: String): String {
        val stem = baseName.substringBeforeLast('.').ifBlank { "LineOver" }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return "${stem}_LineOver.${format.extension}"
    }

    companion object {
        const val DEFAULT_QUALITY = 90
        const val MAX_QUALITY = 100
        const val DEFAULT_LONG_EDGE = 2048f
        const val WIDE_BITS = 16
        const val MIN_EDGE = 64
        const val MAX_EDGE = 32768
        const val MIN_MEGAPIXELS = 0.1f
        const val MAX_MEGAPIXELS = 400f
        const val MIN_PERCENT = 1f
        const val MAX_PERCENT = 100f
        const val MIN_MARK_SCALE = 2f
        const val MAX_MARK_SCALE = 50f
        private const val MEGA = 1_000_000f

        /** Quick export: the factory defaults, whatever the sheet last used. */
        val QUICK = ExportSettings()

        private val json = Json { ignoreUnknownKeys = true }

        fun encode(settings: ExportSettings): String = json.encodeToString(serializer(), settings)

        /** The saved settings, or the defaults when there are none or they cannot be read. */
        fun decode(text: String?): ExportSettings =
            text?.let { runCatching { json.decodeFromString(serializer(), it).validated() }.getOrNull() } ?: ExportSettings()
    }
}
