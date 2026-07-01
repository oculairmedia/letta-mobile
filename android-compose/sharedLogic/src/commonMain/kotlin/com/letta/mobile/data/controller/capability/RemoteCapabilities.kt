package com.letta.mobile.data.controller.capability

/**
 * Capabilities advertised by a remote App Server endpoint.
 *
 * Baseline capabilities (runtime_start, input, stream_delta, sync, abort) are ALWAYS
 * available on any stock 'letta app-server' and are not represented as flags here.
 *
 * This class tracks EXTRAS — value-add features that a stock factory App Server does NOT
 * expose, but an extended (Meridian) controller may advertise.
 *
 * USAGE:
 * - Factory endpoint => baseline only (all extras false)
 * - Extended endpoint => extras lit per advertisement
 * - Unknown/absent advertisement => baseline-only safe default
 *
 * The client defaults to FACTORY-COMPATIBLE and progressively enhances only when
 * an extended controller advertises extras.
 */
data class RemoteCapabilities(
    /**
     * Image hydration: server can return hydrated image data in messages.
     */
    val imageHydration: Boolean = false,

    /**
     * Subagent chips/introspection: server emits subagent state updates and chips.
     */
    val subagentChips: Boolean = false,

    /**
     * Goals: server supports goal tracking and management.
     */
    val goals: Boolean = false,

    /**
     * Slash commands: server supports slash command execution.
     */
    val slashCommands: Boolean = false,

    /**
     * Schedules: server supports scheduled task management.
     */
    val schedules: Boolean = false,

    /**
     * Reflection: server supports reflection/introspection APIs.
     */
    val reflection: Boolean = false,

    /**
     * Slim agents projection: server supports slim agent projection for multi-agent scenarios.
     */
    val slimAgents: Boolean = false,

    /**
     * Conversation-scoped push: server supports conversation-scoped push notifications.
     */
    val scopedPush: Boolean = false,
) {
    companion object {
        /**
         * Factory-default capabilities: baseline only, all extras disabled.
         */
        val FACTORY_DEFAULT = RemoteCapabilities()

        /**
         * Parse capabilities from an advertised descriptor.
         *
         * The descriptor is a set of capability strings advertised by the endpoint.
         * Each string corresponds to an extra feature.
         *
         * Unknown capability strings are ignored (forward compatibility).
         *
         * @param advertised Set of capability strings advertised by the endpoint
         * @return RemoteCapabilities with extras enabled per advertisement
         */
        fun fromAdvertised(advertised: Set<String>): RemoteCapabilities {
            return RemoteCapabilities(
                imageHydration = "image_hydration" in advertised,
                subagentChips = "subagent_chips" in advertised,
                goals = "goals" in advertised,
                slashCommands = "slash_commands" in advertised,
                schedules = "schedules" in advertised,
                reflection = "reflection" in advertised,
                slimAgents = "slim_agents" in advertised,
                scopedPush = "scoped_push" in advertised,
            )
        }

        /**
         * Merge multiple capability sets (union).
         *
         * If any source has a capability enabled, the result has it enabled.
         *
         * @param capabilities List of capability sets to merge
         * @return Merged capabilities (union)
         */
        fun merge(vararg capabilities: RemoteCapabilities): RemoteCapabilities {
            if (capabilities.isEmpty()) return FACTORY_DEFAULT

            return RemoteCapabilities(
                imageHydration = capabilities.any { it.imageHydration },
                subagentChips = capabilities.any { it.subagentChips },
                goals = capabilities.any { it.goals },
                slashCommands = capabilities.any { it.slashCommands },
                schedules = capabilities.any { it.schedules },
                reflection = capabilities.any { it.reflection },
                slimAgents = capabilities.any { it.slimAgents },
                scopedPush = capabilities.any { it.scopedPush },
            )
        }
    }

    /**
     * Check if a specific capability is enabled.
     *
     * UI/transport call sites can use this to guard each extra feature.
     * Absent => the feature is simply not offered (graceful baseline fallback).
     *
     * @param capability The capability to check
     * @return true if the capability is enabled, false otherwise
     */
    fun has(capability: Capability): Boolean {
        return when (capability) {
            Capability.ImageHydration -> imageHydration
            Capability.SubagentChips -> subagentChips
            Capability.Goals -> goals
            Capability.SlashCommands -> slashCommands
            Capability.Schedules -> schedules
            Capability.Reflection -> reflection
            Capability.SlimAgents -> slimAgents
            Capability.ScopedPush -> scopedPush
        }
    }

    /**
     * Returns the set of all enabled capabilities.
     */
    fun enabledCapabilities(): Set<Capability> {
        return buildSet {
            if (imageHydration) add(Capability.ImageHydration)
            if (subagentChips) add(Capability.SubagentChips)
            if (goals) add(Capability.Goals)
            if (slashCommands) add(Capability.SlashCommands)
            if (schedules) add(Capability.Schedules)
            if (reflection) add(Capability.Reflection)
            if (slimAgents) add(Capability.SlimAgents)
            if (scopedPush) add(Capability.ScopedPush)
        }
    }
}

/**
 * Enum of extra capabilities beyond the baseline App Server v2 protocol.
 */
enum class Capability {
    ImageHydration,
    SubagentChips,
    Goals,
    SlashCommands,
    Schedules,
    Reflection,
    SlimAgents,
    ScopedPush,
}
