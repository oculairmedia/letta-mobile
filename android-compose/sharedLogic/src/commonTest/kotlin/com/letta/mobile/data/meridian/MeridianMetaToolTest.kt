package com.letta.mobile.data.meridian

import com.letta.mobile.data.canvas.CanvasToolContract
import com.letta.mobile.data.controller.extras.ExternalToolCaller
import com.letta.mobile.data.controller.extras.ExternalToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The `meridian` meta-tool arm (letta-mobile-jna0o.8): one advertised tool in place of every
 * canvas, agent and plugin tool, answering through the router with the App Server-stamped caller.
 */
class MeridianMetaToolTest {
    private fun metaHost() = MeridianTestHost(offer = MeridianToolOffer.of(AgentToolsModes.all(AgentToolsMode.META)))

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun call(command: String, input: JsonObject? = null) = buildJsonObject {
        put(MeridianMetaTool.COMMAND, command)
        input?.let { put(MeridianMetaTool.INPUT, it) }
    }

    @Test
    fun metaModeAdvertisesOnlyTheMetaTool() {
        val sent = metaHost().registry.advertisedToolsCommandGroups()!!.single().tools
        assertEquals(listOf("meridian"), sent.map { it.name })
        assertEquals(MeridianMetaTool.SCHEMA, sent.single().parameters)
    }

    @Test
    fun nativeModeIsByteIdenticalToTheNativeOffer() {
        val native = MeridianTestHost().registry.advertisedToolsCommandGroups(scopeId = "agent-A")
        val viaOffer = MeridianTestHost(offer = MeridianToolOffer.of(AgentToolsModes.all(AgentToolsMode.NATIVE)))
            .registry.advertisedToolsCommandGroups(scopeId = "agent-A")
        assertEquals(native, viaOffer)
        assertTrue(native!!.single().tools.none { it.name == "meridian" })
    }

    @Test
    fun aMetaCallRunsTheSameToolWithTheStampedCallerAndAnswersItsResult() = runTest {
        val meta = metaHost()
        val native = MeridianTestHost()
        val compose = json("""{"items":[{"kind":"NOTE","markdown":"Hello"}]}""")

        val viaMeta = meta.registry.invoke("meridian", call("canvas compose", compose), MeridianTestHost.CALLER)
        val direct = native.registry.invoke(CanvasToolContract.COMPOSE, compose, MeridianTestHost.CALLER)

        assertEquals(direct, viaMeta)
        assertEquals(native.calls.single(), meta.calls.single())
        assertEquals(MeridianTestHost.CALLER, meta.calls.single().caller)
    }

    @Test
    fun helpListsTheCommandsAndAFailureIsAStructuredError() = runTest {
        val registry = metaHost().registry
        val help = assertIs<ExternalToolResult.Success>(registry.invoke("meridian", call("--help"), MeridianTestHost.CALLER))
        assertTrue(help.content.contains("  canvas "), help.content)

        val unknown = assertIs<ExternalToolResult.Error>(registry.invoke("meridian", call("canvas draw"), MeridianTestHost.CALLER))
        assertEquals(
            "{\"error\":\"unknown_command\",\"message\":\"unknown command: canvas draw\"}\nRun: meridian canvas --help",
            unknown.error,
        )
        val noCommand = assertIs<ExternalToolResult.Error>(registry.invoke("meridian", JsonObject(emptyMap()), MeridianTestHost.CALLER))
        assertTrue(noCommand.error.startsWith("{\"error\":\"usage\""), noCommand.error)
        val badInput = buildJsonObject {
            put(MeridianMetaTool.COMMAND, "canvas list")
            put(MeridianMetaTool.INPUT, JsonPrimitive("not an object"))
        }
        val refused = assertIs<ExternalToolResult.Error>(registry.invoke("meridian", badInput, MeridianTestHost.CALLER))
        assertTrue("\"pointer\":\"/input\"" in refused.error, refused.error)
    }

    @Test
    fun aCallWithoutAnAgentIsDeniedNotRunAsSomeoneElse() = runTest {
        val meta = metaHost()
        val result = meta.registry.invoke("meridian", call("canvas scene"), ExternalToolCaller(agentId = null))
        val error = assertIs<ExternalToolResult.Error>(result)
        assertTrue(error.error.startsWith("{\"error\":\"denied\""), error.error)
    }

    @Test
    fun theMetaToolCannotReachItselfAndToolsStayDirectlyInvocable() = runTest {
        val meta = metaHost()
        assertTrue(meta.router().commands().none { it.toolName == "meridian" })
        val nested = assertIs<ExternalToolResult.Error>(meta.registry.invoke("meridian", call("tool meridian"), MeridianTestHost.CALLER))
        assertTrue(nested.error.startsWith("{\"error\":\"unknown_command\""), nested.error)
        // A turn that started before the switch may still call a native name: it is answered.
        assertIs<ExternalToolResult.Success>(meta.registry.invoke(CanvasToolContract.LIST, JsonObject(emptyMap()), MeridianTestHost.CALLER))
    }

    @Test
    fun theAllowlistCarriesTheMetaTool() {
        val registry = metaHost().registry
        assertTrue("meridian" in registry.offeredTools().map { it.name })
        assertTrue(registry.invocableTools().none { it.name == "meridian" })
    }
}
