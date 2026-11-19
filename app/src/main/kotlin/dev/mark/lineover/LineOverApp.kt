package dev.mark.lineover

import android.app.Application
import dev.mark.lineover.core.io.gallery.DeviceGallery
import dev.mark.lineover.core.io.gallery.MediaStoreGallery
import dev.mark.lineover.core.io.lens.LensProfileStore
import dev.mark.lineover.core.io.looks.LooksLibrary
import dev.mark.lineover.core.io.prefs.AppPreferences
import dev.mark.lineover.core.io.prefs.SharedAppPreferences
import dev.mark.lineover.core.io.project.ProjectLibraries
import dev.mark.lineover.core.io.project.ProjectLibrary
import dev.mark.lineover.feature.editor.canvas.EditorCanvasFactory
import dev.mark.lineover.feature.editor.canvas.EngineCanvasFactory
import dev.mark.lineover.feature.export.ExportEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Application. onCreate does nothing on purpose (fast cold start): everything in the
 * container is created on first use.
 */
class LineOverApp :
    Application(),
    ExportEnvironment.Provider {
    val container: AppContainer by lazy { AppContainer(this) }

    /** The export service (Stage 12) reaches the projects and LUTs through this. */
    override val exportEnvironment: ExportEnvironment by lazy {
        object : ExportEnvironment {
            override val projects get() = container.projects
            override val looks get() = container.looks
            override val versionName: String = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        }
    }
}

/** Hand-written dependency container (no DI framework in the plan). */
class AppContainer(
    app: Application,
) {
    /** Work that must outlive a screen, e.g. removing a deleted project's files. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val projects: ProjectLibrary by lazy { ProjectLibraries.create(app) }

    /** The device's photo library (Home → Albums). */
    val gallery: DeviceGallery by lazy { MediaStoreGallery(app.contentResolver) }

    /** Editor and canvas preferences (last category, favourites, coach marks, surround). */
    val preferences: AppPreferences by lazy { SharedAppPreferences(app) }

    /** The Lensfun lens profile database, included in the app. */
    val lensProfiles: LensProfileStore by lazy {
        // Shipped in the app (core/io assets, CC BY-SA 3.0) since 0.11.2: no download needed.
        LensProfileStore(File(app.filesDir, "lensfun"), bundled = { name -> app.assets.open("lensfun/$name") })
    }

    /** Presets, imported 3D LUTs and copied settings (Stage 11). */
    val looks: LooksLibrary by lazy { LooksLibrary(File(app.filesDir, "looks")) }

    /** Opens the image engine for the editor's canvas (one session per open project). */
    val canvases: EditorCanvasFactory by lazy { EngineCanvasFactory(app) }
}
