package dev.mark.lineover.core.ml

import android.content.Context
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Select subject (Stage 16): ML Kit Subject Segmentation. Its model is a Google Play services
 * module, downloaded by Play services on first use (never in the APK, rule D3) and verified by Play
 * services itself (a signed module: there is no file for LineOver to hash). [release] lets Play
 * services remove it (Settings → AI models).
 */
class SubjectSegmenter(
    private val context: Context,
) : AutoCloseable {
    private val segmenter = SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build())
    private val installer = ModuleInstall.getClient(context)
    private val mutableState = MutableStateFlow<ModelState>(ModelState.Missing)

    /** The module's state as the download sheet and Settings show it. */
    val state: StateFlow<ModelState> = mutableState.asStateFlow()

    /**
     * Asks Play services whether the module is installed (updates [state]). Play services fails
     * with its own ApiException or a RuntimeException when outdated: either way the tool is
     * unavailable here, which the state says.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    suspend fun refresh(): ModelState {
        val next =
            try {
                if (installer.areModulesAvailable(segmenter).await().areModulesAvailable()) ModelState.Ready else ModelState.Missing
            } catch (e: Exception) {
                // no or an old Play services: the tool can't run here
                ModelState.Failed(ModelError.Unavailable)
            }
        mutableState.value = next
        return next
    }

    /** Has Play services download and install the module, reporting its progress. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // any Play services failure is a failed download
    suspend fun install(refusal: () -> ModelError?): ModelState {
        if (refresh() == ModelState.Ready) return ModelState.Ready
        refusal()?.let { return ModelState.Failed(it).also { failed -> mutableState.value = failed } }
        val listener =
            InstallStatusListener { update ->
                val progress = update.progressInfo
                when (update.installState) {
                    ModuleInstallStatusUpdate.InstallState.STATE_DOWNLOADING -> {
                        if (progress !=
                            null
                        ) {
                            mutableState.value = ModelState.Downloading(progress.bytesDownloaded, progress.totalBytesToDownload)
                        }
                    }

                    ModuleInstallStatusUpdate.InstallState.STATE_INSTALLING -> {
                        mutableState.value = ModelState.Verifying
                    }

                    else -> {
                        Unit
                    }
                }
            }
        val request =
            ModuleInstallRequest
                .newBuilder()
                .addApi(segmenter)
                .setListener(listener)
                .build()
        val next =
            try {
                installer.installModules(request).await()
                waitUntilAvailable()
            } catch (e: Exception) {
                ModelState.Failed(ModelError.Network)
            } finally {
                runCatching { installer.unregisterListener(listener) }
            }
        mutableState.value = next
        return next
    }

    private suspend fun waitUntilAvailable(): ModelState {
        repeat(INSTALL_POLLS) {
            if (installer.areModulesAvailable(segmenter).await().areModulesAvailable()) return ModelState.Ready
            kotlinx.coroutines.delay(INSTALL_POLL_MS)
        }
        return ModelState.Failed(ModelError.Network)
    }

    /** The foreground confidence (0..1) of every pixel of [image]. */
    suspend fun segment(image: ModelImage): ModelMask? {
        val bitmap = image.toBitmap()
        return try {
            val result = segmenter.process(InputImage.fromBitmap(bitmap, 0)).await()
            val mask = result.foregroundConfidenceMask ?: return null
            val values = FloatArray(image.width * image.height)
            mask.rewind()
            mask.get(values, 0, minOf(values.size, mask.remaining()))
            ModelMask(image.width, image.height, values)
        } finally {
            bitmap.recycle()
        }
    }

    /** Lets Play services delete the module when no app needs it. */
    suspend fun release() {
        runCatching { installer.releaseModules(segmenter).await() }
        mutableState.value = ModelState.Missing
    }

    override fun close() = segmenter.close()

    private companion object {
        const val INSTALL_POLLS = 240
        const val INSTALL_POLL_MS = 500L
    }
}

/** A Play services Task as a suspending call. */
internal suspend fun <T> Task<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { continuation.resume(it) }
        addOnFailureListener { continuation.resumeWithException(it) }
        addOnCanceledListener { continuation.cancel() }
    }
