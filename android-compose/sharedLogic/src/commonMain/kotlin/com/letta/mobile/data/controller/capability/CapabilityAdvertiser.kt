package com.letta.mobile.data.controller.capability

/**
 * Pluggable interface for advertising capabilities from an endpoint.
 *
 * This abstraction allows the capability negotiation logic to be decoupled from
 * the actual mechanism of retrieving the advertised capabilities (e.g., a handshake
 * frame, an HTTP endpoint, a static configuration, etc.).
 *
 * Implementations can be:
 * - Real: query the App Server for advertised capabilities (future handshake)
 * - Fake: return a fixed set of capabilities for testing
 * - Static: return factory-default or extended capabilities based on config
 */
interface CapabilityAdvertiser {
    /**
     * Retrieve the set of capability strings advertised by the endpoint.
     *
     * Returns an empty set if no capabilities are advertised, which signals
     * baseline-only mode (factory-compatible).
     *
     * @return Set of capability strings (e.g., "image_hydration", "goals")
     */
    suspend fun advertise(): Set<String>
}

/**
 * In-memory capability advertiser for testing and static configuration.
 *
 * @param capabilities Set of capability strings to advertise
 */
class InMemoryCapabilityAdvertiser(
    private val capabilities: Set<String> = emptySet(),
) : CapabilityAdvertiser {
    override suspend fun advertise(): Set<String> = capabilities
}

/**
 * Factory-default advertiser: returns empty set (baseline only).
 */
class FactoryDefaultAdvertiser : CapabilityAdvertiser {
    override suspend fun advertise(): Set<String> = emptySet()
}

/**
 * Extended (Meridian) advertiser: returns all known extras.
 *
 * @param extras Set of extra capabilities to advertise. Defaults to all known extras.
 */
class ExtendedCapabilityAdvertiser(
    private val extras: Set<String> = setOf(
        "image_hydration",
        "subagent_chips",
        "goals",
        "slash_commands",
        "schedules",
        "reflection",
        "slim_agents",
        "scoped_push",
    ),
) : CapabilityAdvertiser {
    override suspend fun advertise(): Set<String> = extras
}
