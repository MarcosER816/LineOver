package dev.mark.lineover.feature.export

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import dev.mark.lineover.core.engine.EngineSession
import dev.mark.lineover.core.io.export.ExportDestinations
import dev.mark.lineover.core.io.export.ExportMetadata
import dev.mark.lineover.core.io.export.ExportTarget
import dev.mark.lineover.core.io.layers.LayerSources
import dev.mark.lineover.core.io.looks.LooksStore
import dev.mark.lineover.core.io.project.DocumentLoad
import dev.mark.lineover.core.io.project.ProjectLibrary
import dev.mark.lineover.core.model.EditGraph
import dev.mark.lineover.core.model.ExportFormat
import dev.mark.lineover.core.model.ExportSettings
import dev.mark.lineover.core.model.LayerMath
import dev.mark.lineover.core.model.OpParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.IOException
import java.util.UUID

/** What the export needs from the app (the Application implements [Provider]). */
interface ExportEnvironment {
    val projects: ProjectLibrary
    val looks: LooksStore?
    val versionName: String

    /** The images of the edit's layers (Stage 13). */
    val layers: LayerSources? get() = null

    interface Provider {
        val exportEnvironment: ExportEnvironment
    }
}

/** One export: which project, with what settings, to where. */
data class ExportRequest(
    val projectId: Long,
    val settings: ExportSettings,
    val target: ExportTarget = ExportTarget.Pictures,
)

/** The steps an export reports, in order. */
enum class ExportStep(
    val label: String,
) {
    Opening("Opening the photo"),
    Rendering("Rendering at full resolution"),
    Finishing("Resizing and finishing"),
    Encoding("Encoding"),
    Saving("Saving"),
}

sealed interface ExportStatus {
    data object Idle : ExportStatus

    data class Running(
        val projectName: String,
        val step: ExportStep,
        /** 0..1 over the whole export. */
        val progress: Float,
    ) : ExportStatus

    data class Done(
        val uri: Uri,
        val fileName: String,
        val mimeType: String,
        val bytes: Long,
        val width: Int,
        val height: Int,
        val inPictures: Boolean,
    ) : ExportStatus

    data class Failed(
        val message: String,
    ) : ExportStatus

    data object Cancelled : ExportStatus
}

/** The process-wide export state the sheet and the notification follow. */
object ExportJobs {
    private val mutable = MutableStateFlow<ExportStatus>(ExportStatus.Idle)
    val status: StateFlow<ExportStatus> = mutable.asStateFlow()

    val running: Boolean get() = status.value is ExportStatus.Running

    internal fun update(status: ExportStatus) {
        mutable.value = status
    }

    /** Back to idle once the sheet has shown a finished export. */
    fun acknowledge() {
        if (mutable.value !is ExportStatus.Running) mutable.value = ExportStatus.Idle
    }
}

/**
 * Runs one export on the calling (background) dispatcher: the project's edit is rendered at
 * full resolution on the CPU reference path into a mapped float image, resized, sharpened,
 * watermarked, converted with the target ICC (precision stays float until the encoder),
 * encoded, and copied to Pictures/LineOver or the picked document.
 */
class ExportRunner(
    private val context: Context,
    private val environment: ExportEnvironment,
) {
    suspend fun run(
        request: ExportRequest,
        report: (ExportStatus.Running) -> Unit,
    ): ExportStatus.Done {
        val settings = request.settings.validated()
        val project = environment.projects.observe(request.projectId).first() ?: fail("The project no longer exists")
        val document =
            (environment.projects.loadDocument(project) as? DocumentLoad.Loaded)?.document
                ?: fail("The project's edit cannot be read")
        val graph = document.graph

        fun step(
            s: ExportStep,
            p: Float,
        ) = report(ExportStatus.Running(project.name, s, p))
        step(ExportStep.Opening, 0f)
        val work = File(context.cacheDir, "export/${UUID.randomUUID()}")
        try {
            val source = Uri.parse(project.sourceUri)
            val session = EngineSession.open(context, source) ?: fail("This photo's format cannot be exported yet")
            val (file, size) =
                session.use {
                    registerLuts(session, LayerMath.allOps(graph))
                    render(session, RenderJob(graph, settings, project.name), work, ::step)
                }
            step(ExportStep.Saving, ENCODE_SHARE)
            val uri = ExportDestinations(context.contentResolver).publish(file, request.target, file.name, settings.format.mimeType)
            return ExportStatus.Done(
                uri,
                file.name,
                settings.format.mimeType,
                file.length(),
                size.first,
                size.second,
                request.target == ExportTarget.Pictures,
            )
        } finally {
            work.deleteRecursively()
        }
    }

    /** What [render] exports: an edit, its settings and the photo's name (for the file name). */
    internal data class RenderJob(
        val graph: EditGraph,
        val settings: ExportSettings,
        val name: String,
    )

    /**
     * Renders [job]'s edit on [session] at full resolution and encodes it into a file in
     * [work]; returns the file and its pixel size.
     */
    internal suspend fun render(
        session: EngineSession,
        job: RenderJob,
        work: File,
        step: (ExportStep, Float) -> Unit = { _, _ -> },
    ): Pair<File, Pair<Int, Int>> {
        val (graph, settings, name) = job
        val source = session.info.uri
        // Layer images at export size (Stage 13), dropped again once the pixels are rendered.
        registerLayers(session, graph)
        val image =
            try {
                session.setGraph(graph)?.let { fail("The edit could not be applied: $it") }
                session.renderForExport(work) { step(ExportStep.Rendering, RENDER_SHARE * it) }
            } finally {
                session.releaseFullLayerImages()
            }
        image.use {
            val (width, height) = settings.outputSize(image.width, image.height)
            checkLimits(settings.format, width, height)
            step(ExportStep.Finishing, RENDER_SHARE)
            val mark = Watermarks.render(context, settings.watermark, width, height)
            image.process(width, height, settings.sharpen.amount, mark)
            val tags = ExportMetadata.tags(sourceExif(source), settings, width, height, "LineOver ${environment.versionName}")
            val exif = ExportMetadata.exifBlock(tags, work)
            step(ExportStep.Encoding, FINISH_SHARE)
            val file = File(work, settings.fileName(name))
            val gainmap =
                if (settings.format == ExportFormat.UltraHdr) {
                    session.gainmapFor(graph) ?: image.rebuildGainmap(settings.colorSpace)
                } else {
                    null
                }
            ExportEncoders.encode(image, settings, file, ExportEncoders.Metadata(exif, tags, gainmap))
            return file to (width to height)
        }
    }

    private fun registerLuts(
        session: EngineSession,
        ops: List<OpParams>,
    ) {
        val looks = environment.looks ?: return
        for (lut in ops.filterIsInstance<OpParams.Lut>()) {
            val table = looks.loadLut(lut.id)
            if (table == null ||
                !session.registerLut(lut.id, table)
            ) {
                Log.w(TAG, "LUT ${lut.name} is not on this device; exported without it")
            }
        }
    }

    /** The layer images at export size; a missing one is left out (the engine skips its layer). */
    private fun registerLayers(
        session: EngineSession,
        graph: EditGraph,
    ) {
        val store = environment.layers
        for (name in LayerMath.sources(graph)) {
            val file = store?.file(name)
            if (file == null || !session.registerLayerImage(name, file, full = true)) {
                Log.w(TAG, "layer image $name is not on this device; exported without it")
            }
        }
    }

    private fun sourceExif(uri: Uri): ExifInterface? =
        runCatching { context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) } }.getOrNull()

    private fun checkLimits(
        format: ExportFormat,
        width: Int,
        height: Int,
    ) {
        if (format == ExportFormat.Webp && maxOf(width, height) > WEBP_MAX_EDGE) {
            fail("WebP is limited to $WEBP_MAX_EDGE px per side: choose a smaller size")
        }
    }

    /** A failed export: [message] is shown to the person. */
    private fun fail(message: String): Nothing = throw IOException(message)

    companion object {
        private const val TAG = "LineOverExport"
        const val WEBP_MAX_EDGE = 16383
        const val RENDER_SHARE = 0.7f
        const val FINISH_SHARE = 0.8f
        const val ENCODE_SHARE = 0.95f
    }
}
