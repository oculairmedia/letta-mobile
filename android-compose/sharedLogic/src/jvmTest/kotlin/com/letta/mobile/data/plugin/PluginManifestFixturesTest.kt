package com.letta.mobile.data.plugin

import com.letta.mobile.data.canvas.plugin.CanvasPluginElements
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The manifest fixtures under `commonTest/resources/canvas/plugin/v1/` (letta-mobile-s416w.24): the
 * jvm, process and service examples parse, and every case of `manifest-problems.json` is refused
 * with exactly its listed problems, each at its pointer.
 */
class PluginManifestFixturesTest {
    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("$DIR/$name")) { "missing fixture $name" }.readText()

    @Test
    fun theThreeRuntimeExamplesParse() {
        val runtimes = listOf("manifest-example.json", "manifest-example-process.json", "manifest-example-service.json").map { name ->
            val parsed = assertIs<PluginManifestResult.Parsed>(PluginManifestParser.parse(fixture(name)), name)
            assertEquals("letta.example", parsed.manifest.id, name)
            PluginRuntime.kindOf(parsed.manifest.runtime)
        }
        assertEquals(listOf("jvm", "process", "service"), runtimes)
    }

    @Test
    fun theExampleManifestRoundTripsThroughItsEncoding() {
        val manifest = assertIs<PluginManifestResult.Parsed>(PluginManifestParser.parse(fixture("manifest-example.json"))).manifest
        val again = assertIs<PluginManifestResult.Parsed>(PluginManifestParser.parse(PluginManifestParser.encode(manifest))).manifest
        assertEquals(manifest, again)
    }

    @Test
    fun theExampleKindsAreTheOnesTheElementFixtureUses() {
        val manifest = assertIs<PluginManifestResult.Parsed>(PluginManifestParser.parse(fixture("manifest-example.json"))).manifest
        val element = CanvasPluginElements.decode(Json.parseToJsonElement(fixture("element-widget.json")).jsonObject)
        val catalog = PluginKinds.catalogOf(PluginRegistryFixtures.registryOf(manifest))
        assertTrue(catalog.schemaFor(manifest.elementType(element!!.kind), 2) != null)
    }

    @Test
    fun everyProblemCaseIsRefusedAtItsPointers() {
        val problems = Json.parseToJsonElement(fixture("manifest-problems.json")).jsonObject
        val base = Json.parseToJsonElement(fixture(problems.getValue("base").jsonPrimitive.content))
        val cases = problems.getValue("cases").jsonArray.map { it.jsonObject }
        assertTrue(cases.size >= 40, "${cases.size} cases")
        cases.forEach { case ->
            val name = case.getValue("name").jsonPrimitive.content
            var document = base
            case["remove"]?.jsonArray?.forEach { document = JsonPatch.remove(document, it.jsonPrimitive.content) }
            (case["set"] as? JsonObject)?.forEach { (path, value) -> document = JsonPatch.set(document, path, value) }
            val refused = assertIs<PluginManifestResult.Refused>(PluginManifestParser.parse(document), name)
            assertEquals(case.getValue("expected").jsonArray.map { it.jsonPrimitive.content }, refused.problems.map { "${it.path} ${it.code}" }, name)
        }
    }

    private companion object {
        const val DIR = "/canvas/plugin/v1"
    }
}
