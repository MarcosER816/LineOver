package dev.mark.lineover.core.io.gallery

import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryGroupingTest {
    private val rows =
        listOf(
            MediaRow(id = 1, bucketId = 10, bucketName = "Camera", displayName = "a.jpg", modified = 500),
            MediaRow(id = 2, bucketId = 20, bucketName = "Screenshots", displayName = "b.png", modified = 900),
            MediaRow(id = 3, bucketId = 10, bucketName = "Camera", displayName = "c.jpg", modified = 700),
            MediaRow(id = 4, bucketId = 30, bucketName = null, displayName = "d.jpg", modified = 100),
        )

    @Test
    fun albumsAreOrderedByTheirNewestPhotoWithCountsAndCovers() {
        assertEquals(
            listOf(
                DeviceAlbum(20, "Screenshots", 1, coverPhotoId = 2),
                DeviceAlbum(10, "Camera", 2, coverPhotoId = 3),
                DeviceAlbum(30, "Other", 1, coverPhotoId = 4),
            ),
            GalleryGrouping.albums(rows),
        )
    }

    @Test
    fun photosOfAnAlbumAreNewestFirst() {
        assertEquals(listOf(3L, 1L), GalleryGrouping.photos(rows, albumId = 10).map { it.id })
        assertEquals(emptyList<DevicePhoto>(), GalleryGrouping.photos(rows, albumId = 99))
    }
}
