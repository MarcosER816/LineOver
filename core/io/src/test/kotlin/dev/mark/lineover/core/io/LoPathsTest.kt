package dev.mark.lineover.core.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoPathsTest {
    @Test
    fun projectFileNameAppendsExtension() {
        assertEquals("holiday.loproj", LoPaths.projectFileName("holiday"))
    }

    @Test
    fun recognisesProjectFiles() {
        assertTrue(LoPaths.isProjectFile("a.loproj"))
        assertTrue(LoPaths.isProjectFile("A.LOPROJ"))
        assertFalse(LoPaths.isProjectFile("a.jpg"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankNameIsRejected() {
        LoPaths.projectFileName(" ")
    }
}
