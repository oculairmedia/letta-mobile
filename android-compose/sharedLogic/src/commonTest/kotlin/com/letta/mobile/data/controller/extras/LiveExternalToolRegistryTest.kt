package com.letta.mobile.data.controller.extras

import app.cash.turbine.test
import com.letta.mobile.data.controller.capability.Capability
import com.letta.mobile.data.controller.capability.RemoteCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-s416w.27: the registry's live [ToolSource]s, beside the fixed tools it is built with.
 */
class LiveExternalToolRegistryTest {
    private val fixed = NamedTool("canvas_open")

    private fun registry() = ExternalToolRegistry.hostTools(listOf(fixed))

    @Test
    fun anAddedSourceIsAdvertisedAfterTheFixedToolsAndRemovingItWithdrawsIt() {
        val registry = registry()

        assertTrue(registry.addSource(ToolSource.static("plugin.a", listOf(NamedTool("a_one"), NamedTool("a_two")))))
        assertEquals(listOf("canvas_open", "a_one", "a_two"), registry.listAdvertisedTools().map { it.name })
        assertEquals(
            listOf("canvas_open", "a_one", "a_two"),
            registry.advertisedToolsCommandGroups()?.single()?.tools?.map { it.name },
        )

        assertTrue(registry.removeSource("plugin.a"))
        assertEquals(listOf("canvas_open"), registry.listAdvertisedTools().map { it.name })
        assertEquals(listOf("canvas_open"), registry.advertisedToolsCommandGroups()?.single()?.tools?.map { it.name })
    }

    @Test
    fun aSourceIdIsAddedOnceAndOnlyAPresentSourceCanBeRemoved() {
        val registry = registry()

        assertTrue(registry.addSource(ToolSource.static("plugin.a", listOf(NamedTool("a_one")))))
        assertFalse(registry.addSource(ToolSource.static("plugin.a", listOf(NamedTool("other")))))
        assertEquals(listOf("canvas_open", "a_one"), registry.listAdvertisedTools().map { it.name })
        assertFalse(registry.removeSource("plugin.missing"))
    }

    @Test
    fun aSourceToolCannotShadowAFixedToolOrAnEarlierSource() = runTest {
        val registry = registry()
        registry.addSource(ToolSource.static("plugin.a", listOf(NamedTool("canvas_open", result = "plugin"), NamedTool("shared"))))
        registry.addSource(ToolSource.static("plugin.b", listOf(NamedTool("shared", result = "b"))))

        assertEquals(listOf("canvas_open", "shared"), registry.listAdvertisedTools().map { it.name })
        assertEquals(ExternalToolResult.Success("canvas_open"), registry.invoke("canvas_open", JsonObject(emptyMap())))
        assertEquals(ExternalToolResult.Success("shared"), registry.invoke("shared", JsonObject(emptyMap())))
    }

    @Test
    fun aSourceToolIsGatedByCapabilityLikeAFixedOne() {
        val registry = ExternalToolRegistry(tools = emptyList(), capabilities = RemoteCapabilities(goals = true))
        registry.addSource(
            ToolSource.static(
                "plugin.a",
                listOf(RemoteTool("gated_off", Capability.Reflection), RemoteTool("gated_on", Capability.Goals)),
            ),
        )

        assertEquals(listOf("gated_on"), registry.listAdvertisedTools().map { it.name })
    }

    @Test
    fun aPublishedListReplacesTheSourcesToolsInPlace() = runTest {
        val registry = registry()
        val source = MutableToolSource("plugin.a", listOf(NamedTool("a_one")))
        registry.addSource(source)

        source.publish(listOf(NamedTool("a_two")))

        assertEquals(listOf("canvas_open", "a_two"), registry.listAdvertisedTools().map { it.name })
        assertIs<ExternalToolResult.Success>(registry.invoke("a_two", JsonObject(emptyMap())))
    }

    @Test
    fun toolsChangedEmitsOnEveryAddAndRemoveAndOnRealPublishesButNotForTheCurrentSet() = runTest {
        val registry = registry()
        val source = MutableToolSource("plugin.a")

        registry.toolsChanged.test {
            expectNoEvents()
            // Membership always signals: a runtime started meanwhile may hold another set.
            registry.addSource(source)
            assertEquals(listOf("canvas_open"), awaitItem().map { it.name })
            source.publish(listOf(NamedTool("a_one")))
            assertEquals(listOf("canvas_open", "a_one"), awaitItem().map { it.name })
            source.publish(listOf(NamedTool("a_one")))
            expectNoEvents()
            registry.removeSource("plugin.a")
            assertEquals(listOf("canvas_open"), awaitItem().map { it.name })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aRegistryWithOnlyFixedToolsNeverSignalsAChange() = runTest {
        registry().toolsChanged.test {
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aCallForARemovedToolIsAnsweredAsNoLongerAvailable() = runTest {
        val registry = registry()
        registry.addSource(ToolSource.static("plugin.a", listOf(NamedTool("a_one"))))
        registry.advertisedToolsCommandGroups()
        registry.removeSource("plugin.a")

        val removed = registry.invoke("a_one", JsonObject(emptyMap()))
        val unknown = registry.invoke("never_offered", JsonObject(emptyMap()))

        assertEquals(ExternalToolResult.Error("tool 'a_one' is no longer available"), removed)
        assertEquals(ExternalToolResult.Error("Tool not found or not advertised: never_offered"), unknown)
    }

    @Test
    fun removingEveryToolOmitsTheExternalToolsField() {
        val registry = ExternalToolRegistry.factoryDefault()
        registry.addSource(ToolSource.static("plugin.a", listOf(NamedTool("a_one"))))
        registry.removeSource("plugin.a")

        assertNull(registry.advertisedToolsCommandGroups())
    }

    @Test
    fun concurrentAddsAndRemovesLoseNoUpdate() = runTest {
        val registry = registry()
        withContext(Dispatchers.Default) {
            (0 until SOURCES).map { index ->
                launch {
                    registry.addSource(ToolSource.static("plugin.$index", listOf(NamedTool("tool_$index"))))
                    registry.listAdvertisedTools()
                    if (index % 2 == 1) registry.removeSource("plugin.$index")
                }
            }.forEach { it.join() }
        }

        val expected = listOf("canvas_open") + (0 until SOURCES step 2).map { "tool_$it" }
        assertEquals(expected.toSet(), registry.listAdvertisedTools().map { it.name }.toSet())
        assertEquals(expected.size, registry.listAdvertisedTools().size)
    }

    private class NamedTool(override val name: String, private val result: String = name) : HostExternalTool {
        override val description = "test $name"
        override val inputSchema: JsonObject? = null
        override val capability = Capability.ImageHydration
        override suspend fun invoke(input: JsonObject, agentId: String?) = ExternalToolResult.Success(result)
    }

    private class RemoteTool(override val name: String, override val capability: Capability) : ExternalTool {
        override val description = "test $name"
        override val inputSchema: JsonObject? = null
        override suspend fun invoke(input: JsonObject, agentId: String?) = ExternalToolResult.Success(name)
    }

    private companion object {
        const val SOURCES = 200
    }
}
