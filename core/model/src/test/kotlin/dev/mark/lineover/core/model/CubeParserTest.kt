package dev.mark.lineover.core.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class CubeParserTest {
    private val testdata: File by lazy {
        var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        File(requireNotNull(dir), "testdata")
    }

    private fun text(path: String) = File(testdata, path).readText()

    @Test
    fun readsTheFixtureLuts() {
        val warm = CubeParser.parse(text("golden/luts/warm_fade_17.cube"))
        assertEquals(17, warm.size)
        assertEquals("LineOver test: warm fade", warm.title)
        assertEquals(17 * 17 * 17 * 3, warm.table.size)
        // The first entry is the black corner, red changes fastest.
        assertEquals(0.06f, warm.table[0], 1e-6f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f), warm.domain, 0f)
        val teal = CubeParser.parse(text("golden/luts/teal_amber_33.cube"))
        assertEquals(33, teal.size)
        assertArrayEquals(floatArrayOf(1.25f, 1.25f, 1.25f), teal.domainMax, 1e-6f)
    }

    @Test
    fun writingAndReadingAgainKeepsTheTable() {
        val warm = CubeParser.parse(text("golden/luts/warm_fade_17.cube"))
        assertEquals(text("golden/luts/warm_fade_17.cube"), CubeParser.write(warm))
    }

    @Test
    fun readsTheLargestSize() {
        val n = 65
        val table = FloatArray(n * n * n * 3) { i -> (i % (n * 3)) / (n * 3f) }
        val lut = CubeParser.parse(CubeParser.write(CubeLut(n, table, title = "big")))
        assertEquals(n, lut.size)
        assertEquals(table.size, lut.table.size)
        assertEquals(table[12345], lut.table[12345], 1e-6f)
    }

    @Test
    fun acceptsCommentsBlankLinesTabsAndInputRange() {
        val cube =
            "# made by hand\n\nTITLE \"x\"\nLUT_3D_INPUT_RANGE 0 2\nLUT_3D_SIZE 2\n" +
                (0 until 8).joinToString("") { i -> "${i and 1}\t${(i shr 1) and 1} ${(i shr 2) and 1}\n" } + "# end\n"
        val lut = CubeParser.parse(cube)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 2f, 2f, 2f), lut.domain, 0f)
        assertArrayEquals(floatArrayOf(1f, 1f, 1f), lut.table.copyOfRange(21, 24), 0f)
    }

    /** Every broken file in testdata/luts/malformed is refused with its own message. */
    @Test
    fun refusesMalformedFiles() {
        val expected =
            mapOf(
                "data_before_size" to "line 1: table data before LUT_3D_SIZE",
                "domain_inverted" to "DOMAIN_MAX must be above DOMAIN_MIN",
                "empty" to "no LUT_3D_SIZE",
                "no_size" to "line 2: table data before LUT_3D_SIZE",
                "not_a_number" to "line 5: table has a value that is not a number",
                "not_finite" to "line 5: table has a value that is not finite",
                "one_d" to "line 1: 1D LUTs are not supported",
                "size_not_integer" to "line 1: LUT_3D_SIZE is not a whole number",
                "size_too_big" to "line 1: LUT_3D_SIZE 129 is outside 2..65",
                "size_too_small" to "line 1: LUT_3D_SIZE 1 is outside 2..65",
                "size_twice" to "line 2: LUT_3D_SIZE twice",
                "too_few_rows" to "7 table rows, LUT_3D_SIZE 2 needs 8",
                "too_many_rows" to "9 table rows, LUT_3D_SIZE 2 needs 8",
                "two_values" to "line 5: table needs 3 numbers",
                "unknown_keyword" to "line 2: unknown keyword GAMMA",
            )
        val files =
            File(testdata, "luts/malformed")
                .listFiles()
                .orEmpty()
                .map { it.nameWithoutExtension }
                .sorted()
        assertEquals(expected.keys.sorted(), files)
        for ((name, message) in expected) {
            try {
                CubeParser.parse(text("luts/malformed/$name.cube"))
                fail("$name was accepted")
            } catch (e: CubeFormatException) {
                assertEquals(name, message, e.message)
            }
        }
    }

    @Test
    fun aKeywordAfterTheTableIsRefused() {
        val cube = "LUT_3D_SIZE 2\n" + "0 0 0\n".repeat(8) + "TITLE \"late\"\n"
        try {
            CubeParser.parse(cube)
            fail("accepted")
        } catch (e: CubeFormatException) {
            assertTrue(e.message.orEmpty().contains("after the table"))
        }
    }
}
