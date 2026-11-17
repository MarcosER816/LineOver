package dev.mark.lineover.feature.home

import android.graphics.Bitmap
import dev.mark.lineover.core.io.gallery.DeviceAlbum
import dev.mark.lineover.core.io.gallery.DeviceGallery
import dev.mark.lineover.core.io.gallery.DevicePhoto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeDeviceGallery : DeviceGallery {
    val albums = MutableStateFlow<List<DeviceAlbum>>(emptyList())
    val photos = MutableStateFlow<Map<Long, List<DevicePhoto>>>(emptyMap())

    override fun observeAlbums() = albums

    override fun observePhotos(albumId: Long) = photos.map { it[albumId].orEmpty() }

    override suspend fun thumbnail(
        photoId: Long,
        sizePx: Int,
    ): Bitmap? = null
}
