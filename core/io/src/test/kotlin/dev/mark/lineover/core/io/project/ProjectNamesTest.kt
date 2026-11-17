package dev.mark.lineover.core.io.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectNamesTest {
    @Test
    fun displayNameLosesItsExtension() {
        assertEquals("IMG_2041", ProjectNames.fromDisplayName("IMG_2041.jpg"))
        assertEquals("holiday.final", ProjectNames.fromDisplayName("holiday.final.dng"))
        assertEquals("noext", ProjectNames.fromDisplayName("noext"))
        assertEquals("Untitled", ProjectNames.fromDisplayName(null))
        assertEquals("Untitled", ProjectNames.fromDisplayName("  .jpg"))
    }

    @Test
    fun cleanTrimsCollapsesAndLimits() {
        assertEquals("Sea side", ProjectNames.clean("  Sea   side \n"))
        assertEquals(ProjectNames.MAX_LENGTH, ProjectNames.clean("x".repeat(200)).length)
        assertEquals("Untitled", ProjectNames.clean("   "))
    }

    @Test
    fun copiesAreNumbered() {
        assertEquals("Beach copy", ProjectNames.copyOf("Beach"))
        assertEquals("Beach copy 2", ProjectNames.copyOf("Beach copy"))
        assertEquals("Beach copy 3", ProjectNames.copyOf("Beach copy 2"))
    }

    @Test
    fun projectFilesAreRecognised() {
        assertTrue(ProjectNames.isProjectFile("trip.LOPROJ"))
        assertFalse(ProjectNames.isProjectFile("trip.jpg"))
        assertFalse(ProjectNames.isProjectFile(null))
    }
}
