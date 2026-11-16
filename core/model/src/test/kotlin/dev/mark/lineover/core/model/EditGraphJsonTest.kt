package dev.mark.lineover.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditGraphJsonTest {
    @Test
    fun roundTripKeepsGraph() {
        val graph =
            EditGraph(
                layers =
                    listOf(
                        Layer(id = "background", ops = listOf(Op.Identity)),
                        Layer(id = "empty"),
                    ),
            )

        val decoded = EditGraphJson.decode(EditGraphJson.encode(graph))

        assertEquals(graph, decoded)
    }

    @Test
    fun opsAreStoredUnderTheirStableName() {
        val text = EditGraphJson.encode(EditGraph(layers = listOf(Layer("l0", listOf(Op.Identity)))))

        assertTrue(text, text.contains("\"op\":\"identity\""))
        assertTrue(text, text.contains("\"schemaVersion\":1"))
    }

    @Test
    fun unknownFieldsFromNewerVersionsAreIgnored() {
        val text = """{"schemaVersion":1,"futureField":true,"layers":[{"id":"l0","ops":[{"op":"identity"}]}]}"""

        assertEquals(EditGraph(layers = listOf(Layer("l0", listOf(Op.Identity)))), EditGraphJson.decode(text))
    }
}
