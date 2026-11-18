package dev.mark.lineover.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

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

    /** One op as its JSON object (the "op" key holds its serial name). */
    fun opObject(op: OpParams): JsonObject = json.encodeToJsonElement(OpParams.serializer(), op).jsonObject

    /** The project document (versions with their histories, snapshots): document.json. */
    fun encodeDocument(document: ProjectDocument): String = json.encodeToString(ProjectDocument.serializer(), document)

    fun decodeDocument(text: String): ProjectDocument = json.decodeFromString(ProjectDocument.serializer(), text)

    /** The shared settings, for readers of other parts of a project file (manifest.json). */
    val format: Json get() = json
}
