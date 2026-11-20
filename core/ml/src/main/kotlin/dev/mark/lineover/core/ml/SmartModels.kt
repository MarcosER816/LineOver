package dev.mark.lineover.core.ml

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The tools that need a model (Stage 16; Stage 17 adds the masks that share them, and depth). */
enum class SmartTool(
    val label: String,
) {
    Subject("Select subject, Subject / Background mask"),
    Sky("Select sky, Sky / People mask"),
    Object("Object select"),
    Depth("Depth range mask"),
}

/** What the first-use sheet and Settings say about a tool's model. */
data class ModelInfo(
    val tool: SmartTool,
    val name: String,
    /** Null when Google Play services downloads it (it reports the size once it starts). */
    val sizeBytes: Long?,
    val license: String,
    val licenseUrl: String,
    /** Where it comes from, in words. */
    val source: String,
)

/** How the last model run went: where it ran and how long it took. */
data class ModelRun(
    val tool: SmartTool,
    val accelerator: Accelerator?,
    val millis: Long,
    val trials: List<AcceleratorTrial> = emptyList(),
)

/** The models behind Select subject / sky and Object select, for the editor and Settings. */
interface SmartModels {
    val states: StateFlow<Map<SmartTool, ModelState>>
    val lastRun: StateFlow<ModelRun?>

    fun info(tool: SmartTool): ModelInfo

    /** Downloads (or installs) [tool]'s model unless it is there; the resulting state. */
    suspend fun prepare(
        tool: SmartTool,
        allowMetered: Boolean = false,
    ): ModelState

    /**
     * Runs [tool] on [image] ([prompts] for Object select); null when its model is not ready or
     * finds nothing. Depth gives the depth map (1 = near).
     */
    suspend fun segment(
        tool: SmartTool,
        image: ModelImage,
        prompts: List<SegmentPrompt> = emptyList(),
    ): ModelMask?

    /** Stage 17's People mask: the sky model's "person" class (its model is Sky's). */
    suspend fun people(image: ModelImage): ModelMask? = null

    suspend fun delete(tool: SmartTool)
}

/**
 * [SmartModels] on the device: ModelStore files for MagicTouch V2 and the sky model, ML Kit's
 * Play services module for the subject. Runtimes are opened on first use and kept; every run is
 * on one background thread (the runtimes are not thread-safe), never the main thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceSmartModels(
    private val context: Context,
    private val store: ModelStore,
    private val network: NetworkStatus,
    private val wifiOnly: () -> Boolean,
    private val picker: AcceleratorPicker = AcceleratorPicker(PrefsAcceleratorMemory(context)),
) : SmartModels {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val worker = Dispatchers.IO.limitedParallelism(1)

    // ML Kit needs its MlKitContext (set up by its provider at app start): without it, or
    // without Play services, Select subject is unavailable instead of crashing the app.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private val subject: SubjectSegmenter? by lazy {
        try {
            SubjectSegmenter(context)
        } catch (e: RuntimeException) {
            null
        }
    }
    private val subjectStates = MutableStateFlow<ModelState>(ModelState.Missing)
    private var sky: Picked<SkySegmenter>? = null
    private var objects: Picked<ObjectSegmenter>? = null
    private var depth: Picked<DepthEstimator>? = null
    private val mutableLastRun = MutableStateFlow<ModelRun?>(null)
    override val lastRun: StateFlow<ModelRun?> = mutableLastRun.asStateFlow()

    override val states: StateFlow<Map<SmartTool, ModelState>> =
        combine(store.states, subjectState()) { files, subjectState ->
            mapOf(
                SmartTool.Subject to subjectState,
                SmartTool.Sky to (files[ModelCatalog.SKY.id] ?: ModelState.Missing),
                SmartTool.Object to (files[ModelCatalog.MAGIC_TOUCH.id] ?: ModelState.Missing),
                SmartTool.Depth to (files[ModelCatalog.DEPTH.id] ?: ModelState.Missing),
            )
        }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private fun subjectState(): StateFlow<ModelState> {
        scope.launch {
            val segmenter = subject
            if (segmenter == null) {
                subjectStates.value = ModelState.Failed(ModelError.Unavailable)
            } else {
                launch { segmenter.refresh() }
                segmenter.state.collect { subjectStates.value = it }
            }
        }
        return subjectStates.asStateFlow()
    }

    private fun spec(tool: SmartTool): ModelSpec? =
        when (tool) {
            SmartTool.Sky -> ModelCatalog.SKY
            SmartTool.Object -> ModelCatalog.MAGIC_TOUCH
            SmartTool.Depth -> ModelCatalog.DEPTH
            SmartTool.Subject -> null
        }

    override fun info(tool: SmartTool): ModelInfo {
        val spec = spec(tool) ?: return SUBJECT_INFO
        return ModelInfo(
            tool,
            spec.displayName,
            spec.sizeBytes,
            spec.license,
            spec.licenseUrl,
            spec.downloadUrl.substringAfter("//").substringBefore('/'),
        )
    }

    override suspend fun prepare(
        tool: SmartTool,
        allowMetered: Boolean,
    ): ModelState {
        val spec = spec(tool) ?: return subject?.install { refusal(allowMetered) } ?: ModelState.Failed(ModelError.Unavailable)
        return store.download(spec, allowMetered)
    }

    private fun refusal(allowMetered: Boolean): ModelError? =
        when {
            !network.isOnline() -> ModelError.Offline
            wifiOnly() && !allowMetered && !network.isUnmetered() -> ModelError.NeedsWifi
            else -> null
        }

    override suspend fun segment(
        tool: SmartTool,
        image: ModelImage,
        prompts: List<SegmentPrompt>,
    ): ModelMask? =
        withContext(worker) {
            val start = System.nanoTime()
            var accelerator: Accelerator? = null
            var trials = emptyList<AcceleratorTrial>()
            val mask =
                when (tool) {
                    SmartTool.Subject -> {
                        subject?.takeIf { it.refresh() == ModelState.Ready }?.segment(image)
                    }

                    SmartTool.Sky -> {
                        openSky()?.let { picked ->
                            accelerator = picked.accelerator
                            trials = picked.trials
                            picked.runner.segment(image)
                        }
                    }

                    SmartTool.Object -> {
                        openObjects()?.let { picked ->
                            accelerator = picked.accelerator
                            trials = picked.trials
                            picked.runner.setImage(image)
                            picked.runner.segment(prompts)
                        }
                    }

                    SmartTool.Depth -> {
                        openDepth()?.let { picked ->
                            accelerator = picked.accelerator
                            trials = picked.trials
                            picked.runner.estimate(image)
                        }
                    }
                }
            mutableLastRun.value = ModelRun(tool, accelerator, (System.nanoTime() - start) / NANOS_PER_MS, trials)
            mask
        }

    private fun openSky(): Picked<SkySegmenter>? {
        if (!store.isReady(ModelCatalog.SKY)) return null
        return sky ?: SkySegmenter.open(context, store.file(ModelCatalog.SKY), picker).also { sky = it }
    }

    override suspend fun people(image: ModelImage): ModelMask? =
        withContext(worker) {
            val start = System.nanoTime()
            val picked = openSky()
            val mask = picked?.runner?.segment(image, SkySegmenter.PERSON_CLASS)
            mutableLastRun.value =
                ModelRun(SmartTool.Sky, picked?.accelerator, (System.nanoTime() - start) / NANOS_PER_MS, picked?.trials.orEmpty())
            mask
        }

    private fun openDepth(): Picked<DepthEstimator>? {
        if (!store.isReady(ModelCatalog.DEPTH)) return null
        return depth ?: DepthEstimator.open(context, store.file(ModelCatalog.DEPTH), picker).also { depth = it }
    }

    private fun openObjects(): Picked<ObjectSegmenter>? {
        if (!store.isReady(ModelCatalog.MAGIC_TOUCH)) return null
        return objects ?: ObjectSegmenter.open(context, store.file(ModelCatalog.MAGIC_TOUCH), picker).also { objects = it }
    }

    override suspend fun delete(tool: SmartTool) {
        withContext(worker) {
            when (tool) {
                SmartTool.Sky -> sky?.runner?.close().also { sky = null }
                SmartTool.Object -> objects?.runner?.close().also { objects = null }
                SmartTool.Depth -> depth?.runner?.close().also { depth = null }
                SmartTool.Subject -> Unit
            }
        }
        val spec = spec(tool)
        if (spec != null) store.delete(spec) else subject?.release()
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        val SUBJECT_INFO =
            ModelInfo(
                SmartTool.Subject,
                "ML Kit Subject Segmentation",
                sizeBytes = null,
                license = "ML Kit Terms of Service",
                licenseUrl = "https://developers.google.com/ml-kit/terms",
                source = "Google Play services",
            )
    }
}
