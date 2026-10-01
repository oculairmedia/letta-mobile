package com.letta.mobile.data.canvas.compose

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** JSON helpers for the canvas.compose contract tests: canonical form, pointers and edits by pointer. */
internal object ComposeJson {
    fun parse(text: String): JsonElement = CanvasComposeContract.json.parseToJsonElement(text)

    /** Keys sorted at every level, compact: two encodings of one value print the same. */
    fun canonical(element: JsonElement): String = when (element) {
        is JsonObject -> element.entries.sortedBy { it.key }
            .joinToString(",", "{", "}") { (key, value) -> JsonPrimitive(key).toString() + ":" + canonical(value) }
        is JsonArray -> element.joinToString(",", "[", "]") { canonical(it) }
        is JsonPrimitive -> element.toString()
    }

    /** The pointer of every object in [element], the root (`""`) included. */
    fun objectPointers(element: JsonElement, path: String = ""): List<String> = when (element) {
        is JsonObject -> listOf(path) + element.flatMap { (key, value) -> objectPointers(value, "$path/$key") }
        is JsonArray -> element.flatMapIndexed { i, value -> objectPointers(value, "$path/$i") }
        is JsonPrimitive -> emptyList()
    }

    /** The pointer of every item object (anything with a `kind`). */
    fun itemPointers(element: JsonElement): List<String> =
        objectPointers(element).filter { (at(element, it) as JsonObject).containsKey("kind") }

    fun at(element: JsonElement, pointer: String): JsonElement =
        segments(pointer).fold(element) { node, segment ->
            when (node) {
                is JsonObject -> node.getValue(segment)
                is JsonArray -> node[segment.toInt()]
                is JsonPrimitive -> error("no $segment under a primitive")
            }
        }

    /** [element] with the value at [pointer] (which must exist or be a new object field) replaced by [value]. */
    fun with(element: JsonElement, pointer: String, value: JsonElement): JsonElement {
        val path = segments(pointer)
        if (path.isEmpty()) return value
        val head = path.first()
        val rest = "/" + path.drop(1).joinToString("/")
        val tail = if (path.size == 1) null else rest
        return when (element) {
            is JsonObject -> JsonObject(element + (head to (tail?.let { with(element.getValue(head), it, value) } ?: value)))
            is JsonArray -> JsonArray(
                element.mapIndexed { i, child -> if (i == head.toInt()) tail?.let { with(child, it, value) } ?: value else child },
            )
            is JsonPrimitive -> error("no $head under a primitive")
        }
    }

    private fun segments(pointer: String): List<String> = if (pointer.isEmpty()) emptyList() else pointer.removePrefix("/").split("/")
}

/** The refusal [decoding] must be. */
internal fun refusalOf(decoding: ComposeDecoding): ComposeRefusal = assertIs<ComposeDecoding.Refused>(decoding, "expected a refusal").refusal

internal fun requestOf(decoding: ComposeDecoding): ComposeRequest =
    assertIs<ComposeDecoding.Accepted>(decoding, "expected the request to be accepted, got $decoding").request

/** [refusal] names exactly one problem, [code] at [path]. */
internal fun assertSingleProblem(refusal: ComposeRefusal, path: String, code: ComposeProblemCode) {
    assertEquals(listOf(path to code.name), refusal.problems.map { it.path to it.code }, "problems: ${refusal.problems}")
}
