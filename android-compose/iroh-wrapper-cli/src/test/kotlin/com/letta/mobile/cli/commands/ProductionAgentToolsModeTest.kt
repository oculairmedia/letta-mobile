package com.letta.mobile.cli.commands

import com.letta.mobile.data.canvas.CanvasRelayHost
import com.letta.mobile.data.canvas.CanvasToolContract
import com.letta.mobile.data.canvas.HostCanvasBackend
import com.letta.mobile.data.canvas.HostCanvasTools
import com.letta.mobile.data.canvas.InMemoryCanvasRelayStore
import com.letta.mobile.data.canvas.InMemoryHostCanvasDirectory
import com.letta.mobile.data.controller.extras.CustomIrohMessagingTool
import com.letta.mobile.data.controller.extras.ToolAudience
import com.letta.mobile.data.meridian.AgentToolsModePolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * letta-mobile-jna0o.9: `--agent-tools-mode` / `LETTA_AGENT_TOOLS_MODE` on the production host
 * registry (canvas + a2a), with per-agent overrides. native leaves the registry untouched.
 */
class ProductionAgentToolsModeTest {
    private fun production() = buildProductionExternalToolRegistryForTesting(
        binary = "/usr/local/bin/meridian",
        identityDir = null,
        addressStore = null,
        hostTools = InMemoryCanvasRelayStore().let { store ->
            HostCanvasTools.all(HostCanvasBackend(CanvasRelayHost(store, hostId = { "host" }), store, InMemoryHostCanvasDirectory()))
        },
    )

    private fun policy(mode: String?, overrides: String? = null) = AgentToolsModePolicy.parse(mode, overrides).getOrThrow()

    private fun names(registry: com.letta.mobile.data.controller.extras.ExternalToolRegistry, agentId: String?) =
        registry.advertisedToolsCommandGroups(audience = ToolAudience(agentId))?.flatMap { group -> group.tools.map { it.name } }

    @Test
    fun nativeIsTheDefaultAndLeavesTheRegistryUntouched() {
        val registry = production()
        assertSame(registry, policy(null).apply(registry))
        assertSame(registry, policy("native").apply(registry))
    }

    @Test
    fun metaAdvertisesOnlyTheMetaToolAndCliAdvertisesNothing() {
        assertEquals(listOf("meridian"), names(policy("meta").apply(production()), "agent-a"))
        assertNull(names(policy("cli").apply(production()), "agent-a"), "cli advertises no canvas, a2a or plugin tool")
    }

    @Test
    fun aPerAgentOverrideWinsOverTheHostDefault() {
        val registry = policy("native", "agent-meta=meta, agent-cli=cli").apply(production())
        val native = names(registry, "agent-a").orEmpty()
        assertTrue(CanvasToolContract.COMPOSE in native && CustomIrohMessagingTool.TOOL_NAME in native, "$native")
        assertEquals(listOf("meridian"), names(registry, "agent-meta"))
        assertNull(names(registry, "agent-cli"))
    }

    @Test
    fun anInvalidValueIsRefused() {
        assertTrue(AgentToolsModePolicy.parse("lazy", null).isFailure)
        assertTrue(AgentToolsModePolicy.parse("native", "agent-a").isFailure)
        assertTrue(AgentToolsModePolicy.parse("native", "agent-a=sometimes").isFailure)
    }
}
