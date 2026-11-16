package dev.mark.lineover.core.model

import kotlinx.serialization.json.Json

/** JSON codec for [EditGraph], the format stored inside .loproj project files. */
object EditGraphJson {
    private val json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
            classDiscriminator = "op"
        }

    fun encode(graph: EditGraph): String = json.encodeToString(EditGraph.serializer(), graph)

    fun decode(text: String): EditGraph = json.decodeFromString(EditGraph.serializer(), text)
}
