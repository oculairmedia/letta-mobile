package com.letta.mobile.data.controller.capability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class CapabilityNegotiatorTest {
    @Test
    fun factoryAdvertiserReturnsBaselineOnly() = runTest {
        val negotiator = CapabilityNegotiator(FactoryDefaultAdvertiser())

        val capabilities = negotiator.negotiate()

        // All extras should be false (baseline only)
        assertFalse(capabilities.imageHydration)
        assertFalse(capabilities.subagentChips)
        assertFalse(capabilities.goals)
        assertFalse(capabilities.slashCommands)
        assertFalse(capabilities.schedules)
        assertFalse(capabilities.reflection)
        assertFalse(capabilities.slimAgents)
        assertFalse(capabilities.scopedPush)

        assertEquals(RemoteCapabilities.FACTORY_DEFAULT, capabilities)
    }

    @Test
    fun emptyAdvertisementReturnsBaselineOnly() = runTest {
        val negotiator = CapabilityNegotiator(InMemoryCapabilityAdvertiser(emptySet()))

        val capabilities = negotiator.negotiate()

        // Empty advertisement => baseline-only safe default
        assertEquals(RemoteCapabilities.FACTORY_DEFAULT, capabilities)
    }

    @Test
    fun extendedAdvertiserReturnsAllExtras() = runTest {
        val negotiator = CapabilityNegotiator(ExtendedCapabilityAdvertiser())

        val capabilities = negotiator.negotiate()

        // All extras should be true
        assertTrue(capabilities.imageHydration)
        assertTrue(capabilities.subagentChips)
        assertTrue(capabilities.goals)
        assertTrue(capabilities.slashCommands)
        assertTrue(capabilities.schedules)
        assertTrue(capabilities.reflection)
        assertTrue(capabilities.slimAgents)
        assertTrue(capabilities.scopedPush)

        assertEquals(8, capabilities.enabledCapabilities().size)
    }

    @Test
    fun partialExtrasAdvertiserReturnsAdvertisedExtrasOnly() = runTest {
        val negotiator = CapabilityNegotiator(
            InMemoryCapabilityAdvertiser(
                setOf("image_hydration", "goals", "slash_commands"),
            ),
        )

        val capabilities = negotiator.negotiate()

        // Only advertised extras should be true
        assertTrue(capabilities.imageHydration)
        assertTrue(capabilities.goals)
        assertTrue(capabilities.slashCommands)

        // Unadvertised extras should be false
        assertFalse(capabilities.subagentChips)
        assertFalse(capabilities.schedules)
        assertFalse(capabilities.reflection)
        assertFalse(capabilities.slimAgents)
        assertFalse(capabilities.scopedPush)
    }

    @Test
    fun unknownCapabilitiesAreIgnored() = runTest {
        val negotiator = CapabilityNegotiator(
            InMemoryCapabilityAdvertiser(
                setOf("image_hydration", "unknown_future_feature", "goals"),
            ),
        )

        val capabilities = negotiator.negotiate()

        // Known capabilities should be parsed
        assertTrue(capabilities.imageHydration)
        assertTrue(capabilities.goals)

        // Other extras should be false
        assertFalse(capabilities.subagentChips)
        assertFalse(capabilities.slashCommands)

        // Unknown capabilities are silently ignored (forward compatibility)
        assertEquals(2, capabilities.enabledCapabilities().size)
    }

    @Test
    fun factoryDefaultFactoryMethodReturnsBaselineOnly() = runTest {
        val negotiator = CapabilityNegotiator.factoryDefault()

        val capabilities = negotiator.negotiate()

        assertEquals(RemoteCapabilities.FACTORY_DEFAULT, capabilities)
    }

    @Test
    fun extendedFactoryMethodReturnsAllExtras() = runTest {
        val negotiator = CapabilityNegotiator.extended()

        val capabilities = negotiator.negotiate()

        assertTrue(capabilities.imageHydration)
        assertTrue(capabilities.subagentChips)
        assertTrue(capabilities.goals)
        assertTrue(capabilities.slashCommands)
        assertTrue(capabilities.schedules)
        assertTrue(capabilities.reflection)
        assertTrue(capabilities.slimAgents)
        assertTrue(capabilities.scopedPush)
    }

    @Test
    fun extendedFactoryMethodWithSpecificExtrasReturnsThoseExtras() = runTest {
        val negotiator = CapabilityNegotiator.extended(
            setOf("image_hydration", "subagent_chips", "goals"),
        )

        val capabilities = negotiator.negotiate()

        assertTrue(capabilities.imageHydration)
        assertTrue(capabilities.subagentChips)
        assertTrue(capabilities.goals)

        assertFalse(capabilities.slashCommands)
        assertFalse(capabilities.schedules)
        assertFalse(capabilities.reflection)
        assertFalse(capabilities.slimAgents)
        assertFalse(capabilities.scopedPush)
    }

    @Test
    fun customFactoryMethodWorksWithCustomAdvertiser() = runTest {
        val customAdvertiser = InMemoryCapabilityAdvertiser(
            setOf("schedules", "reflection"),
        )
        val negotiator = CapabilityNegotiator.custom(customAdvertiser)

        val capabilities = negotiator.negotiate()

        assertTrue(capabilities.schedules)
        assertTrue(capabilities.reflection)

        assertFalse(capabilities.imageHydration)
        assertFalse(capabilities.subagentChips)
        assertFalse(capabilities.goals)
        assertFalse(capabilities.slashCommands)
        assertFalse(capabilities.slimAgents)
        assertFalse(capabilities.scopedPush)
    }

    @Test
    fun gatingHelperReturnsTrueForEnabledCapability() = runTest {
        val negotiator = CapabilityNegotiator.extended(
            setOf("image_hydration", "goals"),
        )

        val capabilities = negotiator.negotiate()

        // Gating helper should return true for enabled capabilities
        assertTrue(capabilities.has(Capability.ImageHydration))
        assertTrue(capabilities.has(Capability.Goals))
    }

    @Test
    fun gatingHelperReturnsFalseForDisabledCapability() = runTest {
        val negotiator = CapabilityNegotiator.extended(
            setOf("image_hydration", "goals"),
        )

        val capabilities = negotiator.negotiate()

        // Gating helper should return false for disabled capabilities
        assertFalse(capabilities.has(Capability.SubagentChips))
        assertFalse(capabilities.has(Capability.SlashCommands))
        assertFalse(capabilities.has(Capability.Schedules))
    }

    @Test
    fun gatingHelperReturnsFalseForAllCapabilitiesInFactoryDefault() = runTest {
        val negotiator = CapabilityNegotiator.factoryDefault()

        val capabilities = negotiator.negotiate()

        // All extras should be false in factory default
        assertFalse(capabilities.has(Capability.ImageHydration))
        assertFalse(capabilities.has(Capability.SubagentChips))
        assertFalse(capabilities.has(Capability.Goals))
        assertFalse(capabilities.has(Capability.SlashCommands))
        assertFalse(capabilities.has(Capability.Schedules))
        assertFalse(capabilities.has(Capability.Reflection))
        assertFalse(capabilities.has(Capability.SlimAgents))
        assertFalse(capabilities.has(Capability.ScopedPush))
    }
}
