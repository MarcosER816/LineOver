package dev.mark.lineover.core.io.gallery

import android.content.ContentResolver
import android.content.ContentUris
import android.database.ContentObserver
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException

/** An album (MediaStore bucket) of the device's photos. */
data class DeviceAlbum(
    val id: Long,
    val name: String,
    val count: Int,
    /** MediaStore id of the newest photo, shown as the cover. */
    val coverPhotoId: Long,
)

/** One photo of the device's library. */
data class DevicePhoto(
    val id: Long,
    val name: String,
    val modified: Long,
)

/**
 * The device's photo library (MediaStore). Needs READ_MEDIA_IMAGES, or
 * READ_MEDIA_VISUAL_USER_SELECTED for the photos the user chose (Android 14+ partial
 * access); without either the lists are empty. Photos are only ever read.
 */
interface DeviceGallery {
    /** Albums with their photo count, newest cover first; updates when the library changes. */
    fun observeAlbums(): Flow<List<DeviceAlbum>>

    fun observePhotos(albumId: Long): Flow<List<DevicePhoto>>

    /** A small thumbnail (longest side about [sizePx]) from MediaStore's cache, or null. */
    suspend fun thumbnail(
        photoId: Long,
        sizePx: Int,
    ): Bitmap?

    fun uriOf(photoId: Long): Uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photoId)
}

/** One MediaStore row, as needed to build albums. */
data class MediaRow(
    val id: Long,
    val bucketId: Long,
    val bucketName: String?,
    val displayName: String?,
    val modified: Long,
)

/** Album grouping, free of Android so it can be unit-tested. */
object GalleryGrouping {
    private const val UNNAMED = "Other"

    /** Rows sorted newest first in, albums out: ordered by their newest photo. */
    fun albums(rows: List<MediaRow>): List<DeviceAlbum> =
        rows
            .groupBy { it.bucketId }
            .map { (bucketId, photos) ->
                val newest = photos.maxBy { it.modified }
                DeviceAlbum(bucketId, newest.bucketName?.takeIf { it.isNotBlank() } ?: UNNAMED, photos.size, newest.id) to newest.modified
            }.sortedWith(compareByDescending<Pair<DeviceAlbum, Long>> { it.second }.thenBy { it.first.name })
            .map { it.first }

    fun photos(
        rows: List<MediaRow>,
        albumId: Long,
    ): List<DevicePhoto> =
        rows
            .filter { it.bucketId == albumId }
            .sortedByDescending { it.modified }
            .map { DevicePhoto(it.id, it.displayName.orEmpty(), it.modified) }
}

class MediaStoreGallery(
    private val resolver: ContentResolver,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : DeviceGallery {
    override fun observeAlbums(): Flow<List<DeviceAlbum>> = observeRows().mapRows { GalleryGrouping.albums(it) }

    override fun observePhotos(albumId: Long): Flow<List<DevicePhoto>> = observeRows().mapRows { GalleryGrouping.photos(it, albumId) }

    override suspend fun thumbnail(
        photoId: Long,
        sizePx: Int,
    ): Bitmap? =
        withContext(io) {
            try {
                resolver.loadThumbnail(uriOf(photoId), Size(sizePx, sizePx), null)
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
        }

    private fun <T> Flow<List<MediaRow>>.mapRows(transform: (List<MediaRow>) -> T): Flow<T> = map(transform).flowOn(io)

    /** Queries again whenever MediaStore reports a change. */
    private fun observeRows(): Flow<List<MediaRow>> =
        callbackFlow {
            val observer =
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        trySend(query())
                    }
                }
            resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
            send(query())
            awaitClose { resolver.unregisterContentObserver(observer) }
        }.flowOn(io)

    private fun query(): List<MediaRow> {
        val projection =
            arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.BUCKET_ID,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_MODIFIED,
            )
        val rows = mutableListOf<MediaRow>()
        try {
            resolver
                .query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    null,
                    null,
                    "${MediaStore.Images.Media.DATE_MODIFIED} DESC",
                )?.use { c ->
                    val id = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val bucket = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
                    val bucketName = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                    val name = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val modified = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                    while (c.moveToNext()) {
                        rows +=
                            MediaRow(
                                id = c.getLong(id),
                                bucketId = c.getLong(bucket),
                                bucketName = c.getString(bucketName),
                                displayName = c.getString(name),
                                modified = c.getLong(modified) * MILLIS_PER_SECOND,
                            )
                    }
                }
        } catch (_: SecurityException) {
            // No media permission: an empty library.
        }
        return rows
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
    }
}
