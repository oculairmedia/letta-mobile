package com.letta.mobile.data.canvas.compose

import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The schema the model is given and the DTOs a request decodes into say the same thing, and a
 * request is held to that schema with a JSON-pointer path for every problem (letta-mobile-bglj6.6).
 */
class CanvasComposeSchemaTest {
    private val schema = CanvasComposeSchema.input
    private val example = ComposeJson.parse(CanvasComposeGuide.EXAMPLE_REQUEST)

    private fun problems(request: JsonElement) = CanvasComposeSchema.check(request).map { it.path to it.code }

    private fun branch(kind: ComposeKind): JsonObject = schema.property("items")["items"]!!.jsonObject["anyOf"]!!.jsonArray
        .map { it.jsonObject }
        .single { it.property("kind")["enum"]!!.jsonArray.single().jsonPrimitive.content == kind.name }

    private fun JsonObject.property(name: String): JsonObject = getValue("properties").jsonObject.getValue(name).jsonObject

    private fun JsonObject.required(): Set<String> = this["required"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty()

    private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.content.toInt()

    /** Every object schema anywhere in [node], with where it is. */
    private fun objectSchemas(node: JsonElement, path: String = "#"): List<Pair<String, JsonObject>> = when (node) {
        is JsonObject -> {
            val here = if (node["type"]?.jsonPrimitive?.content == "object") listOf(path to node) else emptyList()
            here + node.flatMap { (key, value) -> objectSchemas(value, "$path/$key") }
        }
        is JsonArray -> node.flatMapIndexed { i, value -> objectSchemas(value, "$path/$i") }
        is JsonPrimitive -> emptyList()
    }

    @Test
    fun everyObjectInTheSchemaIsClosed() {
        val objects = objectSchemas(schema)
        assertTrue(objects.size >= 9, "expected the request, five kinds, checklist entries and card fields: ${objects.map { it.first }}")
        objects.forEach { (path, node) ->
            assertEquals(JsonPrimitive(false), node["additionalProperties"], "$path is not closed")
        }
    }

    @Test
    fun aFieldTheContractDoesNotNameIsRefusedWhereverItIs() {
        ComposeJson.objectPointers(example).forEach { pointer ->
            val request = ComposeJson.with(example, "$pointer/surprise", JsonPrimitive(1))
            assertEquals(listOf("$pointer/surprise" to "UNKNOWN_FIELD"), problems(request), "added at '$pointer'")
        }
    }

    @Test
    fun theSchemaAndTheDtosNameTheSameFields() {
        fun assertAgrees(node: JsonObject, descriptor: SerialDescriptor, extra: Set<String> = emptySet()) {
            val dtoFields = descriptor.elementNames.toSet()
            val dtoRequired = (0 until descriptor.elementsCount).filterNot(descriptor::isElementOptional).map(descriptor::getElementName).toSet()
            assertEquals(dtoFields + extra, node.getValue("properties").jsonObject.keys, descriptor.serialName)
            assertEquals(dtoRequired + extra, node.required(), descriptor.serialName)
        }
        assertAgrees(schema, ComposeRequest.serializer().descriptor)
        // The discriminator is the schema's `kind` field and the serializer's class name.
        assertAgrees(branch(ComposeKind.NOTE), ComposeItem.Note.serializer().descriptor, setOf("kind"))
        assertAgrees(branch(ComposeKind.CHECKLIST), ComposeItem.Checklist.serializer().descriptor, setOf("kind"))
        assertAgrees(branch(ComposeKind.CARD), ComposeItem.Card.serializer().descriptor, setOf("kind"))
        assertAgrees(branch(ComposeKind.TEXT), ComposeItem.Text.serializer().descriptor, setOf("kind"))
        assertAgrees(branch(ComposeKind.GROUP), ComposeItem.Group.serializer().descriptor, setOf("kind"))
        assertAgrees(branch(ComposeKind.CHECKLIST).property("items")["items"]!!.jsonObject, ComposeChecklistItem.serializer().descriptor)
        assertAgrees(branch(ComposeKind.CARD).property("fields")["items"]!!.jsonObject, ComposeCardField.serializer().descriptor)
        assertEquals(
            ComposeKind.entries.map { it.name }.toSet(),
            setOf(ComposeItem.Note.serializer(), ComposeItem.Checklist.serializer(), ComposeItem.Card.serializer(),
                ComposeItem.Text.serializer(), ComposeItem.Group.serializer()).map { it.descriptor.serialName }.toSet(),
        )
        assertEquals(listOf("heading", "body"), branch(ComposeKind.TEXT).property("size")["enum"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun theSchemaCapsAreTheContractCaps() {
        assertEquals(CanvasComposeContract.MAX_ITEMS, schema.property("items").int("maxItems"))
        assertEquals(CanvasComposeContract.MAX_CHECKLIST_ITEMS, branch(ComposeKind.CHECKLIST).property("items").int("maxItems"))
        assertEquals(CanvasComposeContract.MAX_MARKDOWN_CHARS, branch(ComposeKind.NOTE).property("markdown").int("maxLength"))
        assertEquals(CanvasComposeContract.MAX_CARD_FIELDS, branch(ComposeKind.CARD).property("fields").int("maxItems"))
        assertEquals(CanvasComposeContract.MAX_CARD_BODY_CHARS, branch(ComposeKind.CARD).property("markdown").int("maxLength"))
        assertEquals(CanvasComposeContract.MAX_TEXT_CHARS, branch(ComposeKind.TEXT).property("text").int("maxLength"))
        assertEquals(CanvasComposeContract.KEY_PATTERN, branch(ComposeKind.NOTE).property("key").getValue("pattern").jsonPrimitive.content)
        assertEquals(CanvasComposeContract.ARTIFACT_ID_PATTERN, schema.property("artifact_id").getValue("pattern").jsonPrimitive.content)
        assertEquals(CanvasComposeContract.COLOR_PATTERN, branch(ComposeKind.CARD).property("color").getValue("pattern").jsonPrimitive.content)
        // A group holds every kind but GROUP: one level of nesting.
        val children = branch(ComposeKind.GROUP).property("children")["items"]!!.jsonObject["anyOf"]!!.jsonArray
        assertEquals(
            listOf("NOTE", "CHECKLIST", "CARD", "TEXT"),
            children.map { it.jsonObject.property("kind")["enum"]!!.jsonArray.single().jsonPrimitive.content },
        )
    }

    @Test
    fun theExampleBreaksNoRule() {
        assertEquals(emptyList(), CanvasComposeSchema.check(example))
    }

    @Test
    fun anUnknownKindIsReportedAtItsKindWhereverTheItemIs() {
        ComposeJson.itemPointers(example).forEach { pointer ->
            val request = ComposeJson.with(example, "$pointer/kind", JsonPrimitive("STICKY"))
            assertEquals(listOf("$pointer/kind" to "UNKNOWN_KIND"), problems(request), "at '$pointer'")
        }
    }

    @Test
    fun aGroupInsideAGroupIsTooDeep() {
        val nested = buildJsonObject {
            put("kind", "GROUP")
            put("children", JsonArray(listOf(buildJsonObject { put("kind", "NOTE"); put("markdown", "x") })))
        }
        val request = ComposeJson.with(example, "/items/3/children/0", nested)
        assertEquals(listOf("/items/3/children/0/kind" to "NESTING_TOO_DEEP"), problems(request))
    }

    @Test
    fun aCapExceededIsReportedWhereItIs() {
        val entry = buildJsonObject { put("text", "x") }
        assertEquals(
            listOf("/items/1/items" to "TOO_MANY_ITEMS"),
            problems(ComposeJson.with(example, "/items/1/items", JsonArray(List(CanvasComposeContract.MAX_CHECKLIST_ITEMS + 1) { entry }))),
        )
        assertEquals(
            listOf("/items/2/markdown" to "TOO_LONG"),
            problems(ComposeJson.with(example, "/items/2/markdown", JsonPrimitive("x".repeat(CanvasComposeContract.MAX_MARKDOWN_CHARS + 1)))),
        )
        val field = buildJsonObject { put("label", "l"); put("value", "v") }
        assertEquals(
            listOf("/items/3/children/0/fields" to "TOO_MANY_ITEMS"),
            problems(ComposeJson.with(example, "/items/3/children/0/fields", JsonArray(List(CanvasComposeContract.MAX_CARD_FIELDS + 1) { field }))),
        )
        val items = (example as JsonObject).getValue("items").jsonArray
        assertEquals(
            listOf("/items" to "TOO_MANY_ITEMS"),
            problems(ComposeJson.with(example, "/items", JsonArray(List(CanvasComposeContract.MAX_ITEMS + 1) { items[2] }))),
        )
        // At the cap is fine.
        assertEquals(
            emptyList(),
            problems(ComposeJson.with(example, "/items/2/markdown", JsonPrimitive("x".repeat(CanvasComposeContract.MAX_MARKDOWN_CHARS)))),
        )
    }

    @Test
    fun badValuesAreNamedByWhatIsWrong() {
        assertEquals(listOf("/items/1/color" to "BAD_COLOR"), problems(ComposeJson.with(example, "/items/1/color", JsonPrimitive("blue"))))
        assertEquals(listOf("/items/1/key" to "BAD_KEY"), problems(ComposeJson.with(example, "/items/1/key", JsonPrimitive("Shopping List"))))
        assertEquals(listOf("/artifact_id" to "BAD_KEY"), problems(ComposeJson.with(example, "/artifact_id", JsonPrimitive("Weekend Plan"))))
        assertEquals(
            listOf("/items/1/items/1/checked" to "WRONG_TYPE"),
            problems(ComposeJson.with(example, "/items/1/items/1/checked", JsonPrimitive("yes"))),
        )
        assertEquals(listOf("/items/0/size" to "BAD_VALUE"), problems(ComposeJson.with(example, "/items/0/size", JsonPrimitive("huge"))))
        assertEquals(listOf("/items/2/markdown" to "BAD_VALUE"), problems(ComposeJson.with(example, "/items/2/markdown", JsonPrimitive(""))))
        assertEquals(listOf("/items" to "BAD_VALUE"), problems(ComposeJson.with(example, "/items", JsonArray(emptyList()))))
        assertEquals(listOf("/title" to "WRONG_TYPE"), problems(ComposeJson.with(example, "/title", JsonNull)))
    }

    @Test
    fun aMissingFieldIsReportedAtWhereItShouldBe() {
        val note = JsonObject(ComposeJson.at(example, "/items/2").jsonObject - "markdown")
        assertEquals(listOf("/items/2/markdown" to "MISSING_FIELD"), problems(ComposeJson.with(example, "/items/2", note)))
        val kindless = JsonObject(ComposeJson.at(example, "/items/2").jsonObject - "kind")
        assertEquals(listOf("/items/2/kind" to "MISSING_FIELD"), problems(ComposeJson.with(example, "/items/2", kindless)))
        assertEquals(listOf("/items" to "MISSING_FIELD"), problems(JsonObject(example.jsonObject - "items")))
    }

    @Test
    fun everyProblemInARequestIsReportedAtOnce() {
        var request = ComposeJson.with(example, "/items/1/kind", JsonPrimitive("STICKY"))
        request = ComposeJson.with(request, "/items/2/color", JsonPrimitive("blue"))
        request = ComposeJson.with(request, "/items/3/children/1/markdown", JsonPrimitive(3))
        assertEquals(
            listOf("/items/1/kind" to "UNKNOWN_KIND", "/items/2/color" to "BAD_COLOR", "/items/3/children/1/markdown" to "WRONG_TYPE"),
            problems(request),
        )
    }

    @Test
    fun aFieldNameIsEscapedInItsPointer() {
        val request = ComposeJson.with(example, "/items/0", JsonObject(ComposeJson.at(example, "/items/0").jsonObject + ("a/b~c" to JsonPrimitive(1))))
        assertEquals(listOf("/items/0/a~1b~0c" to "UNKNOWN_FIELD"), problems(request))
    }
}
