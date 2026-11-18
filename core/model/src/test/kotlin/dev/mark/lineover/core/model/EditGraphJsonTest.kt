package dev.mark.lineover.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EditGraphJsonTest {
    private val graph =
        EditGraph(
            layers =
                listOf(
                    Layer(id = "background", ops = listOf(OpParams.Identity, OpParams.ExposureTest(stops = 1.5f))),
                    Layer(id = "empty"),
                ),
        )

    /** The fixture the native engine's graph_test also round-trips (byte for byte). */
    private val fixture: String by lazy {
        var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        File(requireNotNull(dir), "testdata/graph/edit_graph_v1.json").readText().trimEnd()
    }

    @Test
    fun roundTripKeepsGraph() {
        assertEquals(graph, EditGraphJson.decode(EditGraphJson.encode(graph)))
    }

    @Test
    fun encodingIsTheSharedFixtureExactly() {
        assertEquals(fixture, EditGraphJson.encode(graph))
        assertEquals(graph, EditGraphJson.decode(fixture))
    }

    @Test
    fun opsAreStoredUnderTheirStableName() {
        val text = EditGraphJson.encode(graph)
        assertTrue(text, text.contains("\"op\":\"identity\""))
        assertTrue(text, text.contains("{\"op\":\"exposure_test\",\"stops\":1.5}"))
        assertTrue(text, text.contains("\"schemaVersion\":1"))
    }

    @Test
    fun unknownFieldsFromNewerVersionsAreIgnored() {
        val text = """{"schemaVersion":1,"futureField":true,"layers":[{"id":"l0","ops":[{"op":"identity"}]}]}"""
        assertEquals(EditGraph(layers = listOf(Layer("l0", listOf(OpParams.Identity)))), EditGraphJson.decode(text))
    }

    @Test
    fun backgroundOpsAreReplacedOnTheBottomLayer() {
        val edited = EditGraph().withBackgroundOps(listOf(OpParams.ExposureTest(1f)))
        assertEquals(listOf(Layer(EditGraph.BACKGROUND, listOf(OpParams.ExposureTest(1f)))), edited.layers)
        assertEquals(edited.layers[0].copy(ops = emptyList()), edited.withBackgroundOps(emptyList()).layers[0])
        assertTrue(OpParams.ExposureTest(0f).isIdentity)
    }
}
