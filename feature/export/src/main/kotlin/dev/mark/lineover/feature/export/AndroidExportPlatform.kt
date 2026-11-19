package dev.mark.lineover.feature.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import dev.mark.lineover.core.io.project.DocumentLoad
import dev.mark.lineover.core.io.project.ProjectLibrary
import dev.mark.lineover.core.io.source.SourceReader
import dev.mark.lineover.core.model.ExportEstimate
import dev.mark.lineover.core.model.ExportSettings
import dev.mark.lineover.core.model.OpParams
import dev.mark.lineover.core.model.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

/** [ExportPlatform] on the device: the export service, codecs, the project's thumbnail. */
class AndroidExportPlatform(
    context: Context,
    private val library: ProjectLibrary,
) : ExportPlatform {
    private val app = context.applicationContext

    override val capabilities: ExportCapabilities
        get() = ExportCapabilities.cached(app) ?: ExportCapabilities.UNVERIFIED

    override suspend fun verifiedCapabilities(): ExportCapabilities = withContext(Dispatchers.Default) { ExportCapabilities.verify(app) }

    override fun start(request: ExportRequest) = ExportService.start(app, request)

    override fun cancel() = ExportService.cancel(app)

    override suspend fun imageSize(project: Project): Pair<Int, Int>? =
        withContext(Dispatchers.IO) {
            val graph = (library.loadDocument(project) as? DocumentLoad.Loaded)?.document?.graph
            val geometry =
                graph
                    ?.layers
                    ?.firstOrNull()
                    ?.ops
                    ?.filterIsInstance<OpParams.Geometry>()
                    ?.firstOrNull()
            if (geometry != null) return@withContext geometry.outputWidth to geometry.outputHeight
            SourceReader(app.contentResolver).info(Uri.parse(project.sourceUri))?.let { it.width to it.height }
        }

    override suspend fun sample(
        project: Project,
        settings: ExportSettings,
    ): ExportEstimate.Sample? =
        withContext(Dispatchers.Default) {
            val path = project.thumbPath ?: return@withContext null
            val thumb = BitmapFactory.decodeFile(path) ?: return@withContext null
            try {
                val pixels = (thumb.width * thumb.height).toFloat()

                fun bpp(
                    format: Bitmap.CompressFormat,
                    quality: Int,
                ): Float {
                    val out = ByteArrayOutputStream()
                    thumb.compress(format, quality, out)
                    return out.size() / pixels
                }
                ExportEstimate.Sample(
                    jpeg = bpp(Bitmap.CompressFormat.JPEG, settings.quality),
                    png = bpp(Bitmap.CompressFormat.PNG, PNG_QUALITY),
                    webp = bpp(Bitmap.CompressFormat.WEBP_LOSSY, settings.quality),
                    webpLossless = bpp(Bitmap.CompressFormat.WEBP_LOSSLESS, LOSSLESS_EFFORT),
                )
            } finally {
                thumb.recycle()
            }
        }

    override suspend fun importLogo(uri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val bitmap =
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(app.contentResolver, uri)) { decoder, info, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val long = max(info.size.width, info.size.height)
                        if (long > MAX_LOGO_EDGE) decoder.setTargetSampleSize((long + MAX_LOGO_EDGE - 1) / MAX_LOGO_EDGE)
                    }
                val file = Watermarks.logoFile(app)
                file.parentFile?.mkdirs()
                val temp = File(file.parentFile, "logo.tmp")
                temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
                bitmap.recycle()
                temp.renameTo(file)
            }.getOrDefault(false)
        }

    override fun hasLogo(): Boolean = Watermarks.logoFile(app).isFile

    private companion object {
        const val PNG_QUALITY = 100
        const val LOSSLESS_EFFORT = 100
        const val MAX_LOGO_EDGE = 2048
    }
}
