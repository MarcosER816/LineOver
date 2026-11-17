package dev.mark.lineover.core.ui.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * docs/TOOL_CATALOG.md and [ToolRegistry] must describe exactly the same tools, in the
 * same order, with the same name, category, slot, stage and status.
 */
class ToolCatalogTest {
    private val catalog: String by lazy {
        var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        File(requireNotNull(dir) { "project root not found" }, "docs/TOOL_CATALOG.md").readText()
    }

    private data class Row(
        val id: String,
        val name: String,
        val category: String,
        val slot: String,
        val stage: Int,
        val status: String,
    )

    /** Rows of the six-column tool tables (the design-system table has four columns and no slot). */
    private fun catalogRows(): List<Row> =
        catalog
            .lines()
            .filter { it.startsWith("| `") }
            .map { line ->
                line
                    .trim()
                    .trim('|')
                    .split('|')
                    .map { it.trim() }
            }.filter { it.size == 6 }
            .map { c -> Row(c[0].trim('`'), c[1], c[2], c[3].trim('`'), c[4].toInt(), c[5]) }

    private fun registryRows(): List<Row> =
        ToolRegistry.all.map { t ->
            Row(t.id, t.name, t.catalogCategory, t.slot, t.stage, if (t.done) "done" else "reserved")
        }

    @Test
    fun registryEqualsCatalog() {
        val catalogRows = catalogRows()
        val registryRows = registryRows()
        val missing = catalogRows - registryRows.toSet()
        val extra = registryRows - catalogRows.toSet()
        assertTrue("In the catalog but not in ToolRegistry (or different):\n${missing.joinToString("\n")}", missing.isEmpty())
        assertTrue("In ToolRegistry but not in the catalog (or different):\n${extra.joinToString("\n")}", extra.isEmpty())
        assertEquals("same order", catalogRows, registryRows)
    }

    @Test
    fun headlineCountsMatch() {
        val total = ToolRegistry.all.size
        val done = ToolRegistry.all.count { it.done }
        assertTrue("headline must say $total tools, $done done", "**$total tools, $done done.**" in catalog)
    }

    @Test
    fun idsAndSlotsAreUnique() {
        val ids = ToolRegistry.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        val slots = ToolRegistry.all.map { it.slot }
        assertEquals("every tool has its own slot", slots.size, slots.toSet().size)
    }

    @Test
    fun everyCategoryHasAStrip() {
        dev.mark.lineover.core.model.ToolCategory.entries.forEach { category ->
            assertTrue("$category has no tools", ToolRegistry.strip(category).isNotEmpty())
        }
    }
}
