package dev.mark.lineover.core.io.export

import androidx.exifinterface.media.ExifInterface
import dev.mark.lineover.core.model.ExportColorSpace
import dev.mark.lineover.core.model.ExportSettings
import dev.mark.lineover.core.model.MetadataMode
import java.io.File
import java.text.Normalizer
import java.util.Base64

/**
 * The metadata of an exported file (Stage 12), by [ExportSettings.metadata]:
 * - Keep all: the photo's camera, exposure, lens, date and description tags; location (GPS)
 *   only when "Remove location" is off.
 * - Copyright only: Artist and Copyright.
 * - None: no EXIF at all.
 * Every block also says Software = LineOver, and (all) Orientation = 1 (the pixels are
 * already upright), the exported size and the EXIF colour space. The block is written by
 * ExifInterface into a tiny JPEG and taken out as the TIFF-structured payload that JPEG
 * (APP1), PNG (eXIf), WebP (EXIF) and HEIF carry.
 */
object ExportMetadata {
    /** Camera, exposure, lens and date tags copied by "Keep all". */
    val CAMERA_TAGS =
        listOf(
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
            ExifInterface.TAG_LENS_SPECIFICATION, ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_OFFSET_TIME_DIGITIZED, ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            ExifInterface.TAG_SUBSEC_TIME_DIGITIZED, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_EXPOSURE_PROGRAM, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            ExifInterface.TAG_METERING_MODE, ExifInterface.TAG_FLASH, ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_EXPOSURE_MODE,
            ExifInterface.TAG_SCENE_CAPTURE_TYPE, ExifInterface.TAG_APERTURE_VALUE, ExifInterface.TAG_SHUTTER_SPEED_VALUE,
            ExifInterface.TAG_BRIGHTNESS_VALUE, ExifInterface.TAG_MAX_APERTURE_VALUE, ExifInterface.TAG_LIGHT_SOURCE,
            ExifInterface.TAG_DIGITAL_ZOOM_RATIO, ExifInterface.TAG_CONTRAST, ExifInterface.TAG_SATURATION,
            ExifInterface.TAG_SHARPNESS, ExifInterface.TAG_SUBJECT_DISTANCE_RANGE, ExifInterface.TAG_SENSING_METHOD,
            ExifInterface.TAG_IMAGE_DESCRIPTION, ExifInterface.TAG_USER_COMMENT, ExifInterface.TAG_BODY_SERIAL_NUMBER,
            ExifInterface.TAG_CAMERA_OWNER_NAME,
        )

    val COPYRIGHT_TAGS = listOf(ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT)

    val GPS_TAGS =
        listOf(
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_GPS_PROCESSING_METHOD,
            ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_IMG_DIRECTION_REF, ExifInterface.TAG_GPS_SPEED,
            ExifInterface.TAG_GPS_SPEED_REF, ExifInterface.TAG_GPS_TRACK, ExifInterface.TAG_GPS_TRACK_REF,
            ExifInterface.TAG_GPS_H_POSITIONING_ERROR, ExifInterface.TAG_GPS_MAP_DATUM,
        )

    private const val COLOR_SPACE_SRGB = "1"
    private const val COLOR_SPACE_UNCALIBRATED = "65535"
    private const val APP1_LIMIT = 60_000

    // A 1 x 1 greyscale JPEG: the carrier ExifInterface writes the block into.
    internal val CARRIER: ByteArray =
        Base64.getDecoder().decode(
            "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDABALDA4MChAODQ4SERATGCgaGBYWGDEjJR0oOjM9PDkzODdASFxOQERXRTc4UG1RV19iZ2hnPk1xeXBk" +
                "eFxlZ2P/wAALCAABAAEBAREA/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIh" +
                "MUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqD" +
                "hIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/9oACAEBAAA/" +
                "ACv/2Q==",
        )

    /** The tags and values the export writes ([source] = the original photo's EXIF, may be null). */
    fun tags(
        source: ExifInterface?,
        settings: ExportSettings,
        width: Int,
        height: Int,
        software: String,
    ): Map<String, String> {
        if (settings.metadata == MetadataMode.None) return emptyMap()
        val out = LinkedHashMap<String, String>()
        val copied =
            when (settings.metadata) {
                MetadataMode.All -> CAMERA_TAGS + COPYRIGHT_TAGS + (if (settings.removeLocation) emptyList() else GPS_TAGS)
                else -> COPYRIGHT_TAGS
            }
        for (tag in copied) source?.getAttribute(tag)?.takeIf { it.isNotBlank() }?.let { out[tag] = it }
        if (out[ExifInterface.TAG_COPYRIGHT].isNullOrBlank() && settings.copyright.isNotBlank()) {
            out[ExifInterface.TAG_COPYRIGHT] = ascii(settings.copyright.trim())
        }
        out[ExifInterface.TAG_SOFTWARE] = software
        if (settings.metadata == MetadataMode.All) {
            out[ExifInterface.TAG_ORIENTATION] = ExifInterface.ORIENTATION_NORMAL.toString()
            out[ExifInterface.TAG_PIXEL_X_DIMENSION] = width.toString()
            out[ExifInterface.TAG_PIXEL_Y_DIMENSION] = height.toString()
            out[ExifInterface.TAG_COLOR_SPACE] =
                if (settings.colorSpace == ExportColorSpace.Srgb) COLOR_SPACE_SRGB else COLOR_SPACE_UNCALIBRATED
        }
        return out
    }

    /**
     * The EXIF payload (TIFF structure, "MM\0*" / "II*\0") for [tags], or null when there are
     * none (Metadata: None). [scratch] is a directory for the carrier file.
     */
    fun exifBlock(
        tags: Map<String, String>,
        scratch: File,
    ): ByteArray? {
        if (tags.isEmpty()) return null
        scratch.mkdirs()
        val carrier = File.createTempFile("exif", ".jpg", scratch)
        try {
            carrier.writeBytes(CARRIER)
            val exif = ExifInterface(carrier)
            tags.forEach { (tag, value) -> exif.setAttribute(tag, value) }
            exif.saveAttributes()
            val block = JpegSegments.exifOf(carrier.readBytes()) ?: return null
            // One APP1 segment holds at most 64 KB; a long user comment must not break the file.
            return if (block.size > APP1_LIMIT) exifBlock(tags - ExifInterface.TAG_USER_COMMENT, scratch) else block
        } finally {
            carrier.delete()
        }
    }

    /**
     * EXIF text tags are ASCII (the writer turns anything else into "?"): "©" becomes "(C)",
     * "®" "(R)", accents are dropped ("é" -> "e") and other characters are left out.
     */
    fun ascii(text: String): String {
        val replaced = text.replace("©", "(C)").replace("®", "(R)").replace("™", "(TM)")
        val stripped = Normalizer.normalize(replaced, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return stripped.filter { it.code in ASCII_PRINTABLE }
    }

    private val ASCII_PRINTABLE = 0x20..0x7E

    /** TIFF text tags for the native writer: make, model, software, date, artist, copyright. */
    fun tiffText(tags: Map<String, String>): Array<String> =
        arrayOf(
            tags[ExifInterface.TAG_MAKE].orEmpty(),
            tags[ExifInterface.TAG_MODEL].orEmpty(),
            tags[ExifInterface.TAG_SOFTWARE].orEmpty(),
            tags[ExifInterface.TAG_DATETIME].orEmpty(),
            tags[ExifInterface.TAG_ARTIST].orEmpty(),
            tags[ExifInterface.TAG_COPYRIGHT].orEmpty(),
        )
}
