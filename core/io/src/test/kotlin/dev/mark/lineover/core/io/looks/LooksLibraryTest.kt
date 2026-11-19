package dev.mark.lineover.core.io.looks

import dev.mark.lineover.core.model.OpParams
import dev.mark.lineover.core.model.SettingsGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LooksLibraryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val cube =
        (
            "LUT_3D_SIZE 2\n" +
                (0 until 8).joinToString(
                    "",
                ) { i -> "${i and 1} ${(i shr 1) and 1} ${(i shr 2) and 1}\n" }
        ).toByteArray()

    @Test
    fun importedLutsSurviveARestartAndImportTwiceIsOneEntry() {
        val root = folder.newFolder()
        val library = LooksLibrary(root)
        val entry = library.importCube("Mine.cube", cube).getOrThrow()
        assertEquals("Mine", entry.name)
        assertEquals(64, entry.id.length)
        assertEquals(entry, library.importCube("again.cube", cube).getOrThrow())
        val reopened = LooksLibrary(root)
        assertEquals(listOf(entry), reopened.state.value.luts)
        assertEquals(2, reopened.loadLut(entry.id)?.size)
        reopened.deleteLut(entry.id)
        assertNull(LooksLibrary(root).loadLut(entry.id))
    }

    @Test
    fun aBrokenCubeIsRefusedWithItsReason() {
        val result = LooksLibrary(folder.newFolder()).importCube("bad.cube", "LUT_3D_SIZE 99\n".toByteArray())
        assertEquals("line 1: LUT_3D_SIZE 99 is outside 2..65", result.exceptionOrNull()?.message)
    }

    @Test
    fun presetsFavouritesAndCopiedSettingsArePersisted() {
        val root = folder.newFolder()
        val library = LooksLibrary(root)
        val preset = library.savePreset("  Evening  ", listOf(OpParams.Exposure(0.5f), OpParams.Vibrance(10f)))
        assertEquals("Evening", preset.name)
        library.setFavorite(preset.id, true)
        library.setFavorite("builtin.clean", true)
        library.copySettings(setOf(SettingsGroup.Light), listOf(OpParams.Exposure(1f)))
        val reopened = LooksLibrary(root)
        assertEquals(listOf(preset), reopened.userPresets.value)
        assertEquals(listOf(preset.id, "builtin.clean"), reopened.state.value.favoritePresets)
        val copied = requireNotNull(reopened.state.value.copied)
        assertEquals(listOf(SettingsGroup.Light), copied.groups)
        assertEquals(listOf(OpParams.Exposure(1f)), copied.ops)
        reopened.deletePreset(preset.id)
        assertTrue(LooksLibrary(root).userPresets.value.isEmpty())
        assertEquals(listOf("builtin.clean"), LooksLibrary(root).state.value.favoritePresets)
    }
}
