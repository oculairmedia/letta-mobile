package com.letta.mobile.data.canvas

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** [CanvasSceneSchema] written out for a model: the tool descriptions and the scene read's hint. */
internal object CanvasSceneSchemaText {
    private val json = Json { prettyPrint = false }

    fun full(schema: CanvasSceneSchema): String = buildString {
        append("Scene format (the only one the apps can draw): ")
        append("{\"bgColor\":\"#rrggbbaa\",\"elements\":[...]}. ")
        append("Colors are #rrggbbaa (alpha last). Coordinates are world pixels, written as \"x,y\" strings. ")
        append("Every element needs a unique string id and a type, one of ${schema.allowedTypes.joinToString("|")}. ")
        schema.elementTypes.forEach { append(typeLine(schema, it)).append(' ') }
        append("There are no other element types: a line or arrow is a Shape, and a note or document is not an element ")
        append("(use canvas.apply_ops set_document). ")
        append("Example scene: ").append(encode(schema.sceneExample))
    }

    fun hint(schema: CanvasSceneSchema): String = buildString {
        append("Elements: ")
        append(schema.elementTypes.joinToString("; ") { "${it.type} {${it.required.joinToString(", ")}}" })
        append(". shapeType ${schema.shapeTypes.joinToString("|")}; points \"x,y\"; colors #rrggbbaa. ")
        append("Full format and examples in the canvas.replace_scene description.")
    }

    /**
     * The refusal of a write with [problems]: each element and what is wrong with it, the types
     * there are, and an example of each type the writer seems to have meant.
     */
    fun problems(problems: List<CanvasElementProblem>): String = buildString {
        append("Nothing was published: the apps cannot draw ")
        append(if (problems.size == 1) "this write." else "${problems.size} parts of this write.")
        problems.forEach { append("\n- ").append(subject(it)).append(' ').append(it.reason) }
        append("\nAllowed element types: ${CanvasSceneSchema.allowedTypes.joinToString(", ")}. ")
        append("Lines and arrows are Shapes (shapeType LINE|ARROW); notes/documents are canvas.apply_ops set_document, not elements.")
        problems.map { it.suggested }.distinct().forEach { append("\n${it.type} example: ${encode(it.example)}") }
        append("\nScene: {\"bgColor\":\"#rrggbbaa\",\"elements\":[...]}. See canvas.replace_scene's description for every field.")
    }

    private fun subject(problem: CanvasElementProblem): String =
        problem.elementId?.let { "element '$it'" } ?: "the scene (or an element without an id)"

    private fun typeLine(schema: CanvasSceneSchema, spec: CanvasElementSpec): String =
        "${spec.type}: requires ${spec.required.joinToString(", ") { field(schema, it) }}; " +
            "optional ${(spec.defaults.keys - "points" + spec.optional).joinToString(", ") { field(schema, it) }}; " +
            "${spec.note}. Example: ${encode(spec.example)}."

    private fun field(schema: CanvasSceneSchema, name: String): String {
        val allowed = schema.fields[name]?.allowed.orEmpty()
        return if (allowed.isEmpty()) name else "$name (${allowed.joinToString("|")})"
    }

    private fun encode(value: JsonObject): String = json.encodeToString(JsonObject.serializer(), value)
}
