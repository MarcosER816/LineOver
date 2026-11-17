package dev.mark.lineover

import android.app.Application
import dev.mark.lineover.core.io.gallery.DeviceGallery
import dev.mark.lineover.core.io.gallery.MediaStoreGallery
import dev.mark.lineover.core.io.project.ProjectLibraries
import dev.mark.lineover.core.io.project.ProjectLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Application. onCreate does nothing on purpose (fast cold start): everything in the
 * container is created on first use.
 */
class LineOverApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
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
}
