package dev.mark.lineover.core.io.prefs

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AppPreferencesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun defaultsOnFirstStart() {
        val prefs = SharedAppPreferences(context)
        assertNull(prefs.lastCategory.value)
        assertFalse(prefs.coachMarksSeen.value)
        assertTrue(prefs.favorites.value.isEmpty())
        assertEquals(CanvasSurround.Dark, prefs.surround.value)
    }

    @Test
    fun valuesSurviveARestart() {
        SharedAppPreferences(context).apply {
            setLastCategory("color")
            setCoachMarksSeen(true)
            setFavorites(listOf("light.exposure", "retouch.spot_healing"))
            setSurround(CanvasSurround.MidGrey)
        }
        val restarted = SharedAppPreferences(context)
        assertEquals("color", restarted.lastCategory.value)
        assertTrue(restarted.coachMarksSeen.value)
        assertEquals(listOf("light.exposure", "retouch.spot_healing"), restarted.favorites.value)
        assertEquals(CanvasSurround.MidGrey, restarted.surround.value)
    }
}
