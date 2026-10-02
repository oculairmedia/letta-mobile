package com.letta.mobile.data.plugin

import com.letta.mobile.data.canvas.CanvasSceneCheck
import com.letta.mobile.data.canvas.CanvasSceneValidator
import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures
import com.letta.mobile.data.controller.extras.ExternalToolCaller
import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.controller.extras.MutableToolSource
import com.letta.mobile.data.plugin.PluginRegistryFixtures.json
import com.letta.mobile.data.plugin.PluginRegistryFixtures.manifest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How a manifest reaches the rest of the host (plan sections 4 and 6.3): agent-visible actions as
 * tool definitions for a [MutableToolSource], and element kinds in the [com.letta.mobile.data.canvas.plugin.PluginKindCatalog].
 */
class PluginActionToolsTest {
    private val example = PluginRegistryFixtures.example
    private val invoker = PluginActionInvoker { definition, input, caller ->
        ExternalToolResult.Success("${definition.pluginId}/${definition.action} $input by ${caller.agentId}")
    }

    @Test
    fun onlyAgentVisibleActionsBecomeToolDefinitions() {
        val definitions = PluginActionTools.definitions(example)
        assertEquals(listOf("example_start", "example_cancel"), definitions.map { it.name })
        val start = definitions.first()
        assertEquals("Example: Start a job and place a widget", start.description)
        assertEquals(example.actions.getValue("start").input, start.inputSchema)
        assertEquals("start", start.action)
    }

    @Test
    fun onlyAdvertisedPluginsOfferTools() {
        val installed = PluginRegistryFixtures.registryOf(example)
        assertTrue(PluginActionTools.toolsFor(installed, invoker).isEmpty())
        val enabled = assertIs<PluginTransition.Applied>(PluginRegistryReducer.reduce(installed, PluginCommand.Enable(example.id))).state
        assertEquals(listOf("example_start", "example_cancel"), PluginActionTools.toolsFor(enabled, invoker).map { it.name })
    }

    @Test
    fun theToolSourcePublishesTheFullSetToTheLiveRegistry() = runTest {
        val registry = ExternalToolRegistry.hostTools(emptyList())
        val source = MutableToolSource(PluginActionTools.SOURCE_ID)
        registry.addSource(source)
        val installed = PluginRegistryFixtures.registryOf(example)
        val enabled = assertIs<PluginTransition.Applied>(PluginRegistryReducer.reduce(installed, PluginCommand.Enable(example.id))).state

        PluginActionTools.publish(source, enabled, invoker)
        val advertised = registry.advertisedToolsCommandGroups()?.single()?.tools.orEmpty()
        assertEquals(listOf("example_start", "example_cancel"), advertised.map { it.name })
        assertEquals(example.actions.getValue("start").input, advertised.first().parameters)

        val input = json("""{"label":"x"}""").jsonObject
        val result = registry.invoke("example_start", input, ExternalToolCaller("agent-1", "conv-1", "call-1"))
        assertEquals(ExternalToolResult.Success("letta.example/start $input by agent-1"), result)

        PluginActionTools.publish(source, installed, invoker)
        assertNull(registry.advertisedToolsCommandGroups())
    }

    @Test
    fun installedKindsFeedTheCatalogTheValidatorReads() {
        val other = manifest("/id" to json("\"acme.board\""), "/actions" to JsonObject(emptyMap()))
        val state = PluginRegistryFixtures.registryOf(example, other)
        val catalog = PluginKinds.catalogOf(state)
        assertNotNull(catalog.schemaFor("ext:letta.example/widget", 2))
        assertNotNull(catalog.schemaFor("ext:acme.board/widget", 2))
        assertNull(catalog.schemaFor("ext:letta.example/widget", 1))
        assertTrue(catalog.knows("acme.board"))

        val bad = CanvasPluginElementFixtures.place(1).copy(elementType = "ext:letta.example/widget", v = 2, props = json("""{"status":"paused"}""").jsonObject)
        val check = assertIs<CanvasSceneCheck.Invalid>(CanvasSceneValidator.pluginElement(bad, catalog))
        assertEquals(listOf("/props/status"), check.problems.map { it.path })
    }

    @Test
    fun clientsGetASummaryOfEveryKind() {
        val summaries = PluginKinds.summariesOf(PluginRegistryFixtures.registryOf(example))
        assertEquals(
            listOf(PluginKindSummary("ext:letta.example/widget", "letta.example", "Example", "widget", 2, 320f, 240f, "widget")),
            summaries,
        )
        assertEquals(2, PluginKinds.specsOf(example).single().schemaVersion)
    }
}
