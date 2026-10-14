package com.letta.mobile.data.plugin

import com.letta.mobile.data.schema.JsonSchemaCheck
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The reference plugin's manifest `plugins/chart/letta-plugin.json` (letta-mobile-cdgpr.2): it
 * passes the manifest schema and [PluginManifestRules], asks for exactly what it uses, packs into a
 * valid `process` package, and the props and input in its golden session hold to its own schemas.
 */
class ChartPluginManifestTest {
    private val dir = File("../../plugins/chart")

    private fun manifest(): PluginManifest =
        assertIs<PluginManifestResult.Parsed>(PluginManifestParser.parse(dir.resolve("letta-plugin.json").readText())).manifest

    @Test
    fun theManifestParsesAndAsksForCanvasAssetsAndPagesOnly() {
        val manifest = manifest()
        assertEquals("letta.chart", manifest.id)
        assertEquals("1.0.0", manifest.version)
        assertEquals(PluginRuntime.Process("node", listOf("server.mjs")), manifest.runtime)
        assertEquals(listOf(PluginCapability.CANVAS_PLACE, PluginCapability.ASSETS_WRITE, PluginCapability.UI_PAGES), manifest.capabilities)
        assertTrue(manifest.secrets.isEmpty() && manifest.net.connect.isEmpty() && manifest.settings.isEmpty())
        assertEquals(PluginPageCsp(), manifest.pages.getValue("chart").csp)
        assertEquals(listOf(PluginActionVisibility.AGENT, PluginActionVisibility.VIEW), manifest.actions.getValue("plot").visibility)
        assertEquals(listOf(PluginActionVisibility.VIEW), manifest.actions.getValue("toggle").visibility)
        assertEquals("chart_plot", manifest.toolName("plot"))
    }

    @Test
    fun thePluginFilesAssembleIntoAProcessPackage() {
        val entries = PACKAGED.associateWith { dir.resolve(it).readBytes() }
        val read = assertIs<PluginPackageResult.Read>(PluginPackages.assemble(PluginPackageRef("0".repeat(64)), entries))
        assertEquals(PluginPayload.ProcessFiles(listOf("png.mjs", "server.mjs")), read.pkg.layout.payload)
        assertEquals(mapOf("chart" to "pages/chart.html"), read.pkg.layout.pages)
    }

    @Test
    fun theGoldenSessionHoldsToTheChartSchemas() {
        val manifest = manifest()
        val props = JsonSchemaCheck(manifest.elements.getValue("chart").props)
        val plotInput = JsonSchemaCheck(manifest.actions.getValue("plot").input)
        val toggleInput = JsonSchemaCheck(manifest.actions.getValue("toggle").input)
        val params = sessionParams()
        val emitted = params.flatMap { listOfNotNull(it["place"], it["update"]) }.flatMap { it.jsonArray }.map { it.jsonObject.getValue("props") }
        assertEquals(2, emitted.size)
        emitted.forEach { assertEquals(emptyList(), props.check(it), "props $it") }
        val invocations = params.filter { "action" in it }.associate { it.getValue("action").jsonPrimitive.content to it.getValue("input") }
        assertEquals(emptyList(), plotInput.check(invocations.getValue("plot")))
        assertEquals(emptyList(), toggleInput.check(invocations.getValue("toggle")))
        emitted.forEach { assertEquals(emptyList(), toggleInput.check(it), "a page sends its props with toggle") }
    }

    private fun sessionParams(): List<JsonObject> =
        checkNotNull(javaClass.getResource("/canvas/plugin/v1/wire/session-chart.jsonl")).readText().lines().filter(String::isNotBlank)
            .mapNotNull { Json.parseToJsonElement(it).jsonObject.getValue("message").jsonObject["params"]?.jsonObject }

    private companion object {
        val PACKAGED = listOf("letta-plugin.json", "server.mjs", "png.mjs", "pages/chart.html")
    }
}
