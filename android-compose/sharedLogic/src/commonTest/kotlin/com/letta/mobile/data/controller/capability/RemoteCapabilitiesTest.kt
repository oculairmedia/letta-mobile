package com.letta.mobile.data.controller.capability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteCapabilitiesTest {
    @Test
    fun factoryDefaultHasAllExtrasFalse() {
        val capabilities = RemoteCapabilities.FACTORY_DEFAULT

        assertFalse(capabilities.imageHydration)
        assertFalse(capabilities.subagentChips)
        assertFalse(capabilities.goals)
        assertFalse(capabilities.slashCommands)
        assertFalse(capabilities.schedules)
        assertFalse(capabilities.reflection)
        assertFalse(capabilities.slimAgents)
        assertFalse(capabilities.scopedPush)

        // All extras should be disabled
        assertTrue(capabilities.enabledCapabilities().isEmpty())
    }

    @Test
    fun fromAdvertisedParsesEmptySetAsFactoryDefault() {
        val capabilities = RemoteCapabilities.fromAdvertised(emptySet())

        assertFalse(capabilities.imageHydration)
        assertFalse(capabilities.subagentChips)
        assertFalse(capabilities.goals)
        assertFalse(capabilities.slashCommands)
        assertFalse(capabilities.schedules)
        assertFalse(capabilities.reflection)
        assertFalse(capabilities.slimAgents)
        assertFalse(capabilities.scopedPush)
    }

    @Test
    fun fromAdvertisedParsesImageHydration() {
        val capabilities = RemoteCapabilities.fromAdvertised(setOf("image_hydration"))

        assertTrue(capabilities.imageHydration)
        assertFalse(capabilities.subagentChips)
        assertFalse(capabilities.goals)
        assertFalse(capabilities.slashCommands)
        assertFalse(capabilities.schedules)
        assertFalse(capabilities.reflection)
        assertFalse(capabilities.slimAgents)
        assertFalse(capabilities.scopedPush)

        assertEquals(setOf(Capability.ImageHydration), capabilities.enabledCapabilities())
    }

    @Test
    fun fromAdvertisedParsesMultipleCapabilities() {
        val capabilities = RemoteCapabilities.fromAdvertised(
            setOf("image_hydration", "goals", "slash_commands", "schedules"),
        )

        assertTrue(capabilities.imageHydration)
        assertFalse(capabilities.subagentChips)
        assertTrue(capabilities.goals)
        assertTrue(capabilities.slashCommands)
        assertTrue(capabilities.schedules)
        assertFalse(capabilities.reflection)
        assertFalse(capabilities.slimAgents)
        assertFalse(capabilities.scopedPush)

        assertEquals(
            setOf(
                Capability.ImageHydration,
                Capability.Goals,
                Capability.SlashCommands,
                Capability.Schedules,
            ),
            capabilities.enabledCapabilities(),
        )
    }

    @Test
    fun fromAdvertisedParsesAllKnownExtras() {
        val capabilities = RemoteCapabilities.fromAdvertised(
            setOf(
                "image_hydration",
                "subagent_chips",
                "goals",
                "slash_commands",
                "schedules",
                "reflection",
                "slim_agents",
                "scoped_push",
            ),
        )

        assertTrue(capabilities.imageHydration)
        assertTrue(capabilities.subagentChips)
        assertTrue(capabilities.goals)
        assertTrue(capabilities.slashCommands)
        assertTrue(capabilities.schedules)
        assertTrue(capabilities.reflection)
        assertTrue(capabilities.slimAgents)
        assertTrue(capabilities.scopedPush)

        assertEquals(8, capabilities.enabledCapabilities().size)
        assertEquals(
            setOf(
                Capability.ImageHydration,
                Capability.SubagentChips,
                Capability.Goals,
                Capability.SlashCommands,
                Capability.Schedules,
                Capability.Reflection,
                Capability.SlimAgents,
                Capability.ScopedPush,
            ),
            capabilities.enabledCapabilities(),
        )
    }

    @Test
    fun fromAdvertisedIgnoresUnknownCapabilities() {
        val capabilities = RemoteCapabilities.fromAdvertised(
            setOf("image_hydration", "unknown_capability", "future_feature", "goals"),
        )

        assertTrue(capabilities.imageHydration)
        assertTrue(capabilities.goals)
        assertFalse(capabilities.subagentChips)
        assertFalse(capabilities.slashCommands)

        // Unknown capabilities are ignored (forward compatibility)
        assertEquals(
            setOf(Capability.ImageHydration, Capability.Goals),
            capabilities.enabledCapabilities(),
        )
    }

    @Test
    fun hasReturnsTrueForEnabledCapability() {
        val capabilities = RemoteCapabilities(
            imageHydration = true,
            goals = true,
        )

        assertTrue(capabilities.has(Capability.ImageHydration))
        assertTrue(capabilities.has(Capability.Goals))
        assertFalse(capabilities.has(Capability.SubagentChips))
        assertFalse(capabilities.has(Capability.SlashCommands))
    }

    @Test
    fun hasReturnsFalseForDisabledCapability() {
        val capabilities = RemoteCapabilities.FACTORY_DEFAULT

        assertFalse(capabilities.has(Capability.ImageHydration))
        assertFalse(capabilities.has(Capability.SubagentChips))
        assertFalse(capabilities.has(Capability.Goals))
        assertFalse(capabilities.has(Capability.SlashCommands))
        assertFalse(capabilities.has(Capability.Schedules))
        assertFalse(capabilities.has(Capability.Reflection))
        assertFalse(capabilities.has(Capability.SlimAgents))
        assertFalse(capabilities.has(Capability.ScopedPush))
    }

    @Test
    fun mergeEmptyReturnsFactoryDefault() {
        val merged = RemoteCapabilities.merge()

        assertEquals(RemoteCapabilities.FACTORY_DEFAULT, merged)
    }

    @Test
    fun mergeSingleCapabilityReturnsIt() {
        val capabilities = RemoteCapabilities(imageHydration = true, goals = true)

        val merged = RemoteCapabilities.merge(capabilities)

        assertEquals(capabilities, merged)
    }

    @Test
    fun mergeTwoCapabilitiesUnionsThem() {
        val caps1 = RemoteCapabilities(imageHydration = true, goals = true)
        val caps2 = RemoteCapabilities(subagentChips = true, slashCommands = true)

        val merged = RemoteCapabilities.merge(caps1, caps2)

        assertTrue(merged.imageHydration)
        assertTrue(merged.goals)
        assertTrue(merged.subagentChips)
        assertTrue(merged.slashCommands)
        assertFalse(merged.schedules)
        assertFalse(merged.reflection)
    }

    @Test
    fun mergeMultipleCapabilitiesUnionsThem() {
        val caps1 = RemoteCapabilities(imageHydration = true)
        val caps2 = RemoteCapabilities(goals = true)
        val caps3 = RemoteCapabilities(slashCommands = true)
        val caps4 = RemoteCapabilities.FACTORY_DEFAULT

        val merged = RemoteCapabilities.merge(caps1, caps2, caps3, caps4)

        assertTrue(merged.imageHydration)
        assertTrue(merged.goals)
        assertTrue(merged.slashCommands)
        assertFalse(merged.subagentChips)
        assertFalse(merged.schedules)
    }

    @Test
    fun mergeWithOverlappingCapabilitiesUnionsThem() {
        val caps1 = RemoteCapabilities(imageHydration = true, goals = true)
        val caps2 = RemoteCapabilities(imageHydration = true, slashCommands = true)

        val merged = RemoteCapabilities.merge(caps1, caps2)

        assertTrue(merged.imageHydration)
        assertTrue(merged.goals)
        assertTrue(merged.slashCommands)
    }

    @Test
    fun enabledCapabilitiesReturnsCorrectSet() {
        val capabilities = RemoteCapabilities(
            imageHydration = true,
            subagentChips = false,
            goals = true,
            slashCommands = false,
            schedules = true,
            reflection = false,
            slimAgents = false,
            scopedPush = true,
        )

        assertEquals(
            setOf(
                Capability.ImageHydration,
                Capability.Goals,
                Capability.Schedules,
                Capability.ScopedPush,
            ),
            capabilities.enabledCapabilities(),
        )
    }
}
