package dev.mark.lineover.feature.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import com.github.takahirom.roborazzi.captureRoboImage
import dev.mark.lineover.core.designsystem.LineOverTheme
import dev.mark.lineover.core.designsystem.LoColors
import dev.mark.lineover.core.io.gallery.DeviceAlbum
import dev.mark.lineover.core.io.gallery.DevicePhoto
import dev.mark.lineover.core.model.Project
import dev.mark.lineover.core.ui.image.LocalThumbnailLoader
import dev.mark.lineover.core.ui.image.ThumbnailLoader
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Home, empty and with projects (thumbnails are generated gradients). */
abstract class HomeScreenshots(
    private val widthClass: String,
) {
    private val gradients =
        listOf(
            LoColors.TrackTemperatureCool to LoColors.TrackTemperatureWarm,
            LoColors.TrackTintGreen to LoColors.TrackTintMagenta,
            LoColors.Accent to LoColors.Success,
            LoColors.Warning to LoColors.Danger,
        )

    private val fakeThumbnails =
        ThumbnailLoader { path ->
            val (from, to) = gradients[path.substringAfter('-').substringAfter(':').toInt() % gradients.size]
            val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            val paint = Paint().apply { shader = LinearGradient(0f, 0f, 256f, 256f, from.toArgb(), to.toArgb(), Shader.TileMode.CLAMP) }
            Canvas(bitmap).drawRect(0f, 0f, 256f, 256f, paint)
            bitmap.asImageBitmap()
        }

    private val projects =
        listOf("Lisbon tram", "Harbour at dusk", "Portrait – Ana", "IMG_2041", "Fog over the valley", "Market colours", "DSC_0193 (RAW)")
            .mapIndexed { i, name ->
                // 14 Nov 2026 12:00 UTC minus one day per project.
                val time = 1_794_830_400_000L - i * 86_400_000L
                Project(i + 1L, name, "content://photo/$i", if (i == 6) null else "thumb-$i", time, time)
            }

    private fun snap(
        name: String,
        state: HomeState,
    ) {
        captureRoboImage("home/$widthClass/$name.png") {
            CompositionLocalProvider(LocalThumbnailLoader provides fakeThumbnails) {
                LineOverTheme { HomeScreen(state, onIntent = {}, onImport = {}, onOpenFile = {}, onOpenSettings = {}) }
            }
        }
    }

    @Test
    fun empty() = snap("home_empty", HomeState(loaded = true))

    @Test
    fun filled() = snap("home_filled", HomeState(tab = HomeTab.Projects, projects = projects, recent = projects, loaded = true))

    @Test
    fun albumsWithoutAccess() = snap("home_albums_no_access", HomeState(tab = HomeTab.Albums, loaded = true))

    @Test
    fun albumsPartialAccess() =
        snap(
            "home_albums_partial",
            HomeState(
                tab = HomeTab.Albums,
                loaded = true,
                mediaAccess = MediaAccess.Partial,
                albums =
                    listOf(
                        DeviceAlbum(1, "Camera", 214, coverPhotoId = 0),
                        DeviceAlbum(2, "Screenshots", 37, coverPhotoId = 1),
                        DeviceAlbum(3, "WhatsApp Images", 1, coverPhotoId = 2),
                    ),
            ),
        )

    @Test
    fun album() {
        captureRoboImage("home/$widthClass/album.png") {
            CompositionLocalProvider(LocalThumbnailLoader provides fakeThumbnails) {
                LineOverTheme {
                    AlbumScreen(
                        AlbumState("Camera", photos = List(14) { DevicePhoto(it.toLong(), "IMG_$it.jpg", 0) }, loaded = true),
                        onIntent = {},
                        onBack = {},
                    )
                }
            }
        }
    }

    @Test
    fun undoAfterDelete() =
        snap("home_undo", HomeState(tab = HomeTab.Recent, recent = projects.drop(1), loaded = true, pendingDelete = projects.first()))
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class CompactHomeScreenshotTest : HomeScreenshots("compact")

/** Tablet / foldable landscape. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1024dp-h768dp-hdpi")
class ExpandedHomeScreenshotTest : HomeScreenshots("expanded")
