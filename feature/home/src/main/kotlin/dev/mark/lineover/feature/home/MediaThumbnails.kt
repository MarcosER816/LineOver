package dev.mark.lineover.feature.home

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import dev.mark.lineover.core.io.gallery.DeviceGallery
import dev.mark.lineover.core.ui.image.FileThumbnailLoader
import dev.mark.lineover.core.ui.image.LocalThumbnailLoader
import dev.mark.lineover.core.ui.image.ThumbnailLoader

private const val MEDIA_PREFIX = "media:"
private const val MEDIA_THUMB_PX = 384

/** Thumbnail key of a device photo, for ProjectThumbnail. */
fun mediaThumbnailKey(photoId: Long) = "$MEDIA_PREFIX$photoId"

/** Lets ProjectThumbnail show device photos (MediaStore's cached thumbnails) as well as project thumbnails. */
@Composable
internal fun MediaThumbnails(
    gallery: DeviceGallery,
    content: @Composable () -> Unit,
) {
    val loader =
        remember(gallery) {
            ThumbnailLoader { key ->
                if (key.startsWith(MEDIA_PREFIX)) {
                    key.removePrefix(MEDIA_PREFIX).toLongOrNull()?.let { gallery.thumbnail(it, MEDIA_THUMB_PX)?.asImageBitmap() }
                } else {
                    FileThumbnailLoader.load(key)
                }
            }
        }
    CompositionLocalProvider(LocalThumbnailLoader provides loader, content = content)
}

/** The current media permission state (READ_MEDIA_IMAGES / Android 14+ partial access). */
internal fun Context.mediaAccess(): MediaAccess {
    fun granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    return when {
        granted(android.Manifest.permission.READ_MEDIA_IMAGES) -> MediaAccess.Full
        granted(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> MediaAccess.Partial
        else -> MediaAccess.None
    }
}

internal val MEDIA_PERMISSIONS =
    arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
