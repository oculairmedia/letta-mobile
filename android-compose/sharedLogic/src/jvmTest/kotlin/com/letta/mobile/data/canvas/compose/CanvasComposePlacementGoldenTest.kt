package com.letta.mobile.data.canvas.compose

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `commonTest/resources/canvas/compose/v1/placement-golden.json` (letta-mobile-bglj6.9): every case
 * placed by [CanvasComposePlacement.place] gives exactly the recorded slots, labels and bounds.
 * Read from the JVM classpath, like the other compose fixtures; the same layouts are asserted in
 * common code by CanvasComposePlacementTest, so every target runs them.
 */
class CanvasComposePlacementGoldenTest {
    private val golden: JsonObject =
        ComposeJson.parse(checkNotNull(javaClass.getResource("$DIR/placement-golden.json")).readText()) as JsonObject

    @Test
    fun everyGoldenCasePlacesAsRecorded() {
        val cases = golden.getValue("cases").jsonArray
        assertTrue(cases.isNotEmpty())
        cases.forEach { element ->
            val case = element.jsonObject
            val name = case.getValue("name").jsonPrimitive.content
            val items = case.getValue("items").jsonArray.map { item(it.jsonObject) }
            val content = case["content_bounds"]?.takeIf { it != JsonNull }?.let { bounds(it.jsonObject) }
            val placement = CanvasComposePlacement.place(items, content)
            assertEquals(slots(case.getValue("slots").jsonObject), placement.slots, name)
            assertEquals(slots(case.getValue("labels").jsonObject), placement.labels, name)
            assertEquals(bounds(case.getValue("bounds").jsonObject), placement.bounds, name)
        }
    }

    @Test
    fun theCommonGroupCaseIsTheGoldenOne() {
        val case = golden.getValue("cases").jsonArray.map { it.jsonObject }
            .single { it.getValue("name").jsonPrimitive.content.startsWith("the request fixture") }
        assertEquals(CanvasComposePlacementTest.GROUP_CASE, case.getValue("items").jsonArray.map { item(it.jsonObject) })
        assertEquals(CanvasComposePlacementTest.GROUP_CASE_SLOTS, slots(case.getValue("slots").jsonObject))
    }

    private fun item(json: JsonObject): SizedItem {
        val key = json.getValue("key").jsonPrimitive.content
        val children = json["children"] as? JsonArray ?: return leaf(json)
        return SizedItem.Group(key, json["label"]?.jsonPrimitive?.content, children.map { leaf(it.jsonObject) })
    }

    private fun leaf(json: JsonObject) = SizedItem.Leaf(
        json.getValue("key").jsonPrimitive.content,
        json.getValue("width").jsonPrimitive.float,
        json.getValue("height").jsonPrimitive.float,
    )

    private fun slots(json: JsonObject): Map<String, Slot> = json.mapValues { (_, value) ->
        val b = bounds(value.jsonObject)
        Slot(b.x, b.y, b.width, b.height)
    }

    private fun bounds(json: JsonObject) = ComposeBounds(
        json.getValue("x").jsonPrimitive.float,
        json.getValue("y").jsonPrimitive.float,
        json.getValue("width").jsonPrimitive.float,
        json.getValue("height").jsonPrimitive.float,
    )

    private companion object {
        const val DIR = "/canvas/compose/v1"
    }
}
