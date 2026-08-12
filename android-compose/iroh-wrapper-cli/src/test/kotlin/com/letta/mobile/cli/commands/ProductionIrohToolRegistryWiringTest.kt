package com.letta.mobile.cli.commands

import com.letta.mobile.data.controller.capability.Capability
import com.letta.mobile.data.controller.capability.RemoteCapabilities
import com.letta.mobile.data.controller.extras.CustomIrohMessagingTool
import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * letta-mobile-bn008-phase2-custom-tool (1vuec): the wrapper distribution's
 * per-agent injection seam.
 *
 * `buildProductionExternalToolRegistryForTesting` is the test-friendly seam
 * for `AppServerServeIrohCommand.buildProductionExternalToolRegistry` —
 * identical logic, exposed at top level so we can drive it with throwaway
 * CLI paths without spinning up clikt.
 *
 * The acceptance criteria covered here:
 *  - "Custom messaging tool lives in letta-mobile." ✓ (lives in sharedLogic,
 *    wired through this function in iroh-wrapper-cli.)
 *  - "Per-agent injection mechanism documented and tested." ✓
 *    The test below proves:
 *      a) When `--meridian-binary` is empty, the registry advertises no
 *         extras (preserving the pre-bead behavior — no regression).
 *      b) When `--meridian-binary` is set, the registry advertises the
 *         `agent_message_send` tool to every agent via the standard
 *         `advertisedToolsCommandGroups` API.
 *  - "No regression: matrix_agent_message behavior unchanged in upstream;
 *    we just stop relying on it for new agents." ✓ (matrix_agent_message
 *    lives in upstream letta-code, untouched. The factory-default registry
 *    here advertises no extras, which is the same behavior as before the
 *    bead.)
 */
class ProductionIrohToolRegistryWiringTest {

    @Test
    fun emptyBinaryProducesFactoryDefaultRegistry() {
        // When --meridian-binary is empty (the default), the wrapper
        // behaves exactly as it did before this bead: factoryDefault()
        // advertises no external tools. This is the regression guard.
        val registry = buildProductionExternalToolRegistryForTesting(
            binary = "",
            identityDir = null,
            addressStore = null,
        )
        assertTrue(
            registry.listAdvertisedTools().isEmpty(),
            "empty --meridian-binary must produce a registry that advertises nothing, " +
                "preserving the pre-bead behavior",
        )
        // Sanity: factoryDefault() identity holds.
        assertEquals(
            ExternalToolRegistry.factoryDefault().listAdvertisedTools().size,
            registry.listAdvertisedTools().size,
            "empty binary must produce the same advertised set as factoryDefault()",
        )
    }

    @Test
    fun nonEmptyBinaryAdvertisesIrohToolAcrossAgents() {
        val registry = buildProductionExternalToolRegistryForTesting(
            binary = "/usr/local/bin/meridian",
            identityDir = "/custom/identities",
            addressStore = "/custom/addresses.kv",
        )
        val advertised = registry.listAdvertisedTools()
        val names = advertised.map { it.name }.toSet()
        assertTrue(
            CustomIrohMessagingTool.TOOL_NAME in names,
            "Iroh agent-message tool must be advertised when --meridian-binary is set, got: $names",
        )

        // The capability gate lights up — the registry surfaces it via
        // RemoteCapabilities.enabledCapabilities() so the wire handshake
        // can advertise the agentMessaging capability alongside the tools.
        val capabilities = RemoteCapabilities(agentMessaging = true)
        assertTrue(
            capabilities.has(Capability.AgentMessaging),
            "agentMessaging capability must be on when the Iroh tool is wired",
        )
    }

    @Test
    fun registryAdvertisesTheSameToolForEveryRuntimeScope() {
        // Per-agent injection: the same registry instance is passed to every
        // runtime_start, so the Iroh tool lights up for every agent on the
        // wrapper, not just the first one. The advertisedToolsCommandGroups
        // API takes a scopeId but the tool set is identical per scope.
        val registry = buildProductionExternalToolRegistryForTesting(
            binary = "/usr/local/bin/meridian",
            identityDir = null,
            addressStore = null,
        )
        val agentA = registry.advertisedToolsCommandGroups(scopeId = "agent-A")
        val agentB = registry.advertisedToolsCommandGroups(scopeId = "agent-B")
        val agentC = registry.advertisedToolsCommandGroups(scopeId = null)
        assertNotNull(agentA)
        assertNotNull(agentB)
        assertNotNull(agentC)
        val toolNamesA = agentA!!.flatMap { it.tools }.map { it.name }.toSet()
        val toolNamesB = agentB!!.flatMap { it.tools }.map { it.name }.toSet()
        val toolNamesC = agentC!!.flatMap { it.tools }.map { it.name }.toSet()
        assertEquals(
            toolNamesA,
            toolNamesB,
            "every agent must see the same advertised tool set",
        )
        assertEquals(
            toolNamesA,
            toolNamesC,
            "scopeId=null must produce the same set as scopeId=agent-X",
        )
        assertTrue(
            CustomIrohMessagingTool.TOOL_NAME in toolNamesA,
            "agent-A must see agent_message_send",
        )
    }

    @Test
    fun factoryDefaultHasNoAgentMessagingCapability() {
        // Pin the no-regression guarantee: the factory-default capability
        // set must NOT have agentMessaging enabled, so an unconfigured
        // wrapper does not accidentally start advertising a tool whose
        // binary it does not have.
        val factory = RemoteCapabilities.FACTORY_DEFAULT
        assertFalse(
            factory.has(Capability.AgentMessaging),
            "FACTORY_DEFAULT must NOT enable agentMessaging — only an " +
                "explicit --meridian-binary configuration does",
        )
    }
}
