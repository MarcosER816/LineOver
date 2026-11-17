package dev.mark.lineover.core.ui.image

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.mark.lineover.core.designsystem.LoColors
import dev.mark.lineover.core.designsystem.LoIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Loads thumbnail files. Swappable (tests provide generated images). */
fun interface ThumbnailLoader {
    suspend fun load(path: String): ImageBitmap?
}

/**
 * Decodes thumbnails on Dispatchers.IO (never on the main thread, rule R6). Thumbnails are
 * written at most 512 px on the longest side, so decoding them whole is cheap; a small
 * cache keeps scrolling smooth.
 */
object FileThumbnailLoader : ThumbnailLoader {
    private const val CACHE_ENTRIES = 64
    private val cache = LruCache<String, ImageBitmap>(CACHE_ENTRIES)

    override suspend fun load(path: String): ImageBitmap? =
        cache.get(path) ?: withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(path)?.asImageBitmap()?.also { cache.put(path, it) }
        }
}

val LocalThumbnailLoader = staticCompositionLocalOf<ThumbnailLoader> { FileThumbnailLoader }

/** A project thumbnail, cropped to fill; an image icon while loading or when there is none. */
@Composable
fun ProjectThumbnail(
    path: String?,
    modifier: Modifier = Modifier,
) {
    val loader = LocalThumbnailLoader.current
    val bitmap by produceState<ImageBitmap?>(null, path, loader) { value = path?.let { loader.load(it) } }
    Box(modifier.background(LoColors.Surface2), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        } else {
            Icon(painterResource(LoIcons.Image), contentDescription = null, tint = LoColors.TextDisabled, modifier = Modifier.size(32.dp))
        }
    }
}
