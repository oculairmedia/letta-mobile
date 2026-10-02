package com.letta.mobile.data.canvas.plugin

import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.canvas.CanvasOpProjector
import com.letta.mobile.data.canvas.CanvasSceneCheck
import com.letta.mobile.data.canvas.CanvasSceneValidator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The serialized v1 plugin element fixtures under `commonTest/resources/canvas/plugin/v1/`
 * (letta-mobile-s416w.1): stored entries that decode and keep the envelope, and an op sequence
 * that projects to the stored entry whatever order its update and move arrive in; and the example
 * manifest's kind refusing props at the pointers `props-problems.json` lists (letta-mobile-s416w.2).
 */
class CanvasPluginFixturesTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("$DIR/$name")) { "missing fixture $name" }.readText()

    private fun entry(name: String): JsonObject = json.parseToJsonElement(fixture(name)).jsonObject

    @Test
    fun theStoredElementFixturesDecodeAndKeepTheEnvelope() {
        listOf("element-widget.json", "element-snapshot-link.json").forEach { name ->
            val entry = entry(name)
            assertEquals(emptyList(), CanvasPluginElementSchema.checkEntry(entry), name)
            val element = assertNotNull(CanvasPluginElements.decode(entry), name)
            assertEquals("letta.example", element.pluginId)
        }
    }

    @Test
    fun thePlaceUpdateAndMoveFixtureProjectsToTheStoredEntryInEveryOrder() {
        val fixture = json.parseToJsonElement(fixture("ops-place-and-update.json")).jsonObject
        val ops = fixture.getValue("ops").jsonArray.map { json.decodeFromJsonElement(CanvasOp.serializer(), it) }
        val expected = entry(fixture.getValue("expected").jsonPrimitive.content)
        fixture.getValue("orders").jsonArray.forEach { order ->
            val ordered = order.jsonArray.map { ops[it.jsonPrimitive.int] }
            val scene = json.parseToJsonElement(CanvasOpProjector.project(CanvasOpProjector.emptySceneJson(), ordered)).jsonObject
            val projected = (scene.getValue(CanvasPluginElements.KEY) as JsonArray).single().jsonObject
            assertEquals(expected, projected, "order $order")
        }
    }

    @Test
    fun theExampleManifestNamesTheKindTheElementFixtureUses() {
        val manifest = json.parseToJsonElement(fixture("manifest-example.json")).jsonObject
        val kinds = manifest.getValue("elements").jsonObject.keys
        val element = assertNotNull(CanvasPluginElements.decode(entry("element-widget.json")))
        assertEquals(element.pluginId, manifest.getValue("id").jsonPrimitive.content)
        assertTrue(element.kind in kinds, "$kinds")
    }

    @Test
    fun theExampleManifestsWidgetRefusesPropsAtTheFixturesPointers() {
        val manifest = json.parseToJsonElement(fixture("manifest-example.json")).jsonObject
        val kinds = InMemoryPluginKindCatalog(
            InMemoryPluginKindCatalog.specsOf(manifest.getValue("id").jsonPrimitive.content, manifest.getValue("elements").jsonObject),
        )
        val fixture = json.parseToJsonElement(fixture("props-problems.json")).jsonObject
        val type = fixture.getValue("type").jsonPrimitive.content
        val v = fixture.getValue("v").jsonPrimitive.int
        fixture.getValue("cases").jsonArray.map { it.jsonObject }.forEach { case ->
            val name = case.getValue("name").jsonPrimitive.content
            val op = CanvasPluginElementFixtures.place(1).copy(elementType = type, v = v, props = case.getValue("props").jsonObject)
            val paths = when (val check = CanvasSceneValidator.pluginElement(op, kinds)) {
                is CanvasSceneCheck.Valid -> emptyList()
                is CanvasSceneCheck.Invalid -> check.problems.map { it.path }
            }
            assertEquals(case.getValue("expected").jsonArray.map { it.jsonPrimitive.content }, paths, name)
        }
    }

    private companion object {
        const val DIR = "/canvas/plugin/v1"
    }
}
