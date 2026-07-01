package com.letta.mobile.data.controller.capability

import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * End-to-end factory-default vs extended deployment smoke test.
 *
 * This test proves the factory-vs-extended split through the full controller + capability
 * + extras stack:
 *
 * - FACTORY endpoint: advertises ONLY baseline => RemoteCapabilities has baseline true, all
 *   extras false, and ExternalToolRegistry advertises NO extra tools (factory-compatible).
 *
 * - EXTENDED endpoint: advertises extras => RemoteCapabilities has those extras enabled,
 *   and ExternalToolRegistry advertises the corresponding extra tools.
 *
 * This demonstrates the two deployment stories:
 *
 * 1. Factory user: points Meridian at a stock 'letta app-server' (baseline v2 only).
 *    Capability negotiation returns baseline-only, and no extra tools are registered.
 *    Full chat/turns/approvals work via baseline protocol.
 *
 * 2. Extended user: installs the adjusted controller/server that advertises extras.
 *    Capability negotiation lights up extras, and ExternalToolRegistry advertises
 *    those extra tools to the App Server.
 *
 * NO real network or App Server process required — this uses in-memory fakes to prove
 * the negotiation + registry gating logic end-to-end.
 */
class FactoryVsExtendedDeploymentTest {
    @Test
    fun factoryDefaultEndpoint_baselineOnly_noExtraTools() = runTest {
        // FACTORY STORY: user points Meridian at a stock 'letta app-server'
        // The stock server advertises NOTHING beyond baseline App Server v2 protocol.
        val factoryAdvertiser = FactoryDefaultAdvertiser()
        val negotiator = CapabilityNegotiator(factoryAdvertiser)

        // Negotiate capabilities with the factory endpoint
        val capabilities = negotiator.negotiate()

        // PROOF 1: All extras are disabled (baseline-only)
        assertFalse(capabilities.imageHydration, "Factory endpoint should not advertise image_hydration")
        assertFalse(capabilities.subagentChips, "Factory endpoint should not advertise subagent_chips")
        assertFalse(capabilities.goals, "Factory endpoint should not advertise goals")
        assertFalse(capabilities.slashCommands, "Factory endpoint should not advertise slash_commands")
        assertFalse(capabilities.schedules, "Factory endpoint should not advertise schedules")
        assertFalse(capabilities.reflection, "Factory endpoint should not advertise reflection")
        assertFalse(capabilities.slimAgents, "Factory endpoint should not advertise slim_agents")
        assertFalse(capabilities.scopedPush, "Factory endpoint should not advertise scoped_push")

        assertEquals(
            RemoteCapabilities.FACTORY_DEFAULT,
            capabilities,
            "Factory endpoint should return factory-default capabilities"
        )

        // PROOF 2: ExternalToolRegistry advertises NO extra tools
        val toolRegistry = ExternalToolRegistry.standard(capabilities)
        val advertisedTools = toolRegistry.listAdvertisedTools()

        assertTrue(
            advertisedTools.isEmpty(),
            "Factory endpoint should advertise no extra tools (baseline-only protocol)"
        )

        // END RESULT: Meridian works against stock 'letta app-server' with baseline protocol.
        // Chat, turns, and approvals all function via the baseline App Server v2 protocol.
        // No extras, no shim required.
    }

    @Test
    fun extendedEndpoint_allExtras_allExtraToolsAdvertised() = runTest {
        // EXTENDED STORY: user installs the adjusted controller/server with all extras.
        // The extended server advertises ALL extra capabilities beyond baseline.
        val extendedAdvertiser = ExtendedCapabilityAdvertiser()
        val negotiator = CapabilityNegotiator(extendedAdvertiser)

        // Negotiate capabilities with the extended endpoint
        val capabilities = negotiator.negotiate()

        // PROOF 1: All extras are enabled
        assertTrue(capabilities.imageHydration, "Extended endpoint should advertise image_hydration")
        assertTrue(capabilities.subagentChips, "Extended endpoint should advertise subagent_chips")
        assertTrue(capabilities.goals, "Extended endpoint should advertise goals")
        assertTrue(capabilities.slashCommands, "Extended endpoint should advertise slash_commands")
        assertTrue(capabilities.schedules, "Extended endpoint should advertise schedules")
        assertTrue(capabilities.reflection, "Extended endpoint should advertise reflection")
        assertTrue(capabilities.slimAgents, "Extended endpoint should advertise slim_agents")
        assertTrue(capabilities.scopedPush, "Extended endpoint should advertise scoped_push")

        assertEquals(8, capabilities.enabledCapabilities().size, "All 8 extras should be enabled")

        // PROOF 2: ExternalToolRegistry advertises all 7 extra tools
        // (scoped_push is a capability but not a tool)
        val toolRegistry = ExternalToolRegistry.standard(capabilities)
        val advertisedTools = toolRegistry.listAdvertisedTools()

        assertEquals(
            7,
            advertisedTools.size,
            "Extended endpoint should advertise all 7 extra tools"
        )

        val toolNames = advertisedTools.map { it.name }.toSet()
        assertTrue("image_hydration" in toolNames, "Should advertise image_hydration tool")
        assertTrue("subagent_chips" in toolNames, "Should advertise subagent_chips tool")
        assertTrue("goals" in toolNames, "Should advertise goals tool")
        assertTrue("slash_commands" in toolNames, "Should advertise slash_commands tool")
        assertTrue("schedules" in toolNames, "Should advertise schedules tool")
        assertTrue("reflection" in toolNames, "Should advertise reflection tool")
        assertTrue("slim_agents" in toolNames, "Should advertise slim_agents tool")

        // END RESULT: Meridian works with extended controller/server with all extras lit.
        // Capability negotiation enables all extras, and ExternalToolRegistry advertises
        // the corresponding tools to the App Server.
    }

    @Test
    fun extendedEndpoint_partialExtras_correspondingToolsAdvertised() = runTest {
        // EXTENDED STORY VARIANT: user installs an extended server with a SUBSET of extras.
        // This proves the gating logic: only the advertised extras are enabled, and only
        // the corresponding tools are advertised.
        val partialAdvertiser = InMemoryCapabilityAdvertiser(
            setOf("image_hydration", "goals", "schedules")
        )
        val negotiator = CapabilityNegotiator(partialAdvertiser)

        // Negotiate capabilities with the partial-extras endpoint
        val capabilities = negotiator.negotiate()

        // PROOF 1: Only advertised extras are enabled
        assertTrue(capabilities.imageHydration, "Should enable advertised image_hydration")
        assertTrue(capabilities.goals, "Should enable advertised goals")
        assertTrue(capabilities.schedules, "Should enable advertised schedules")

        assertFalse(capabilities.subagentChips, "Should not enable unadvertised subagent_chips")
        assertFalse(capabilities.slashCommands, "Should not enable unadvertised slash_commands")
        assertFalse(capabilities.reflection, "Should not enable unadvertised reflection")
        assertFalse(capabilities.slimAgents, "Should not enable unadvertised slim_agents")
        assertFalse(capabilities.scopedPush, "Should not enable unadvertised scoped_push")

        assertEquals(3, capabilities.enabledCapabilities().size, "Only 3 extras should be enabled")

        // PROOF 2: ExternalToolRegistry advertises only the 3 corresponding tools
        val toolRegistry = ExternalToolRegistry.standard(capabilities)
        val advertisedTools = toolRegistry.listAdvertisedTools()

        assertEquals(
            3,
            advertisedTools.size,
            "Should advertise exactly 3 tools for the 3 enabled capabilities"
        )

        val toolNames = advertisedTools.map { it.name }.toSet()
        assertTrue("image_hydration" in toolNames, "Should advertise image_hydration tool")
        assertTrue("goals" in toolNames, "Should advertise goals tool")
        assertTrue("schedules" in toolNames, "Should advertise schedules tool")

        assertFalse("subagent_chips" in toolNames, "Should not advertise subagent_chips tool")
        assertFalse("slash_commands" in toolNames, "Should not advertise slash_commands tool")
        assertFalse("reflection" in toolNames, "Should not advertise reflection tool")
        assertFalse("slim_agents" in toolNames, "Should not advertise slim_agents tool")

        // END RESULT: Partial-extras endpoint works as expected — only the advertised
        // extras and tools are enabled, proving the gating logic is correct.
    }

    @Test
    fun emptyAdvertisement_treatedAsFactoryDefault() = runTest {
        // EDGE CASE: endpoint returns an empty advertisement (no extras advertised).
        // This is equivalent to factory-default and should behave the same way.
        val emptyAdvertiser = InMemoryCapabilityAdvertiser(emptySet())
        val negotiator = CapabilityNegotiator(emptyAdvertiser)

        // Negotiate capabilities with the empty-advertisement endpoint
        val capabilities = negotiator.negotiate()

        // PROOF 1: Treated as factory-default (all extras disabled)
        assertEquals(
            RemoteCapabilities.FACTORY_DEFAULT,
            capabilities,
            "Empty advertisement should be treated as factory-default"
        )

        // PROOF 2: No extra tools advertised
        val toolRegistry = ExternalToolRegistry.standard(capabilities)
        val advertisedTools = toolRegistry.listAdvertisedTools()

        assertTrue(
            advertisedTools.isEmpty(),
            "Empty advertisement should advertise no extra tools"
        )

        // END RESULT: Empty advertisement is safe — defaults to baseline-only.
    }

    @Test
    fun unknownCapabilities_ignoredGracefully() = runTest {
        // FORWARD COMPATIBILITY: endpoint advertises unknown capabilities.
        // The negotiator should ignore unknown capabilities and enable only the known ones.
        val futureAdvertiser = InMemoryCapabilityAdvertiser(
            setOf(
                "image_hydration",
                "goals",
                "unknown_future_feature_1",
                "unknown_future_feature_2"
            )
        )
        val negotiator = CapabilityNegotiator(futureAdvertiser)

        // Negotiate capabilities with the future-capabilities endpoint
        val capabilities = negotiator.negotiate()

        // PROOF 1: Known capabilities are enabled
        assertTrue(capabilities.imageHydration, "Should enable known image_hydration")
        assertTrue(capabilities.goals, "Should enable known goals")

        // PROOF 2: Unknown capabilities are ignored (not represented in RemoteCapabilities)
        assertEquals(2, capabilities.enabledCapabilities().size, "Only 2 known extras should be enabled")

        // PROOF 3: Only the 2 known tools are advertised
        val toolRegistry = ExternalToolRegistry.standard(capabilities)
        val advertisedTools = toolRegistry.listAdvertisedTools()

        assertEquals(
            2,
            advertisedTools.size,
            "Should advertise exactly 2 tools for the 2 known capabilities"
        )

        val toolNames = advertisedTools.map { it.name }.toSet()
        assertTrue("image_hydration" in toolNames, "Should advertise image_hydration tool")
        assertTrue("goals" in toolNames, "Should advertise goals tool")

        // END RESULT: Unknown capabilities are gracefully ignored, ensuring forward
        // compatibility with future server versions.
    }
}
