package com.letta.mobile.data.pins.shared

import com.letta.mobile.data.model.AgentId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Names, limits and codes of the pinned-agents v1 contract, shared by host and client. The host keeps
 * one ordered list of pinned agents for every paired device and pushes each accepted change to all of
 * them; a host without [CAPABILITY] has no shared pins.
 */
object AgentPinsWire {
    /** Advertised by a host that serves every v1 method and pushes [FRAME_TYPE]. */
    const val CAPABILITY: String = "pinned_agents_v1"

    /** Reads the shared list. */
    const val GET: String = "pins.get"

    /** Replaces the shared list when the caller's expected revision is current. */
    const val SET: String = "pins.set"

    /** Every v1 method. */
    val METHODS: Set<String> = setOf(GET, SET)

    /** Frame type of the push the host sends every connection after each accepted change. */
    const val FRAME_TYPE: String = "pins_updated"

    /** Most agents one list may hold. */
    const val MAX_PINS: Int = 200

    /** Longest agent id accepted. */
    const val MAX_ID_CHARS: Int = 256

    /** The refusal of a `pins.set` whose expected revision is no longer current. */
    const val REVISION_CONFLICT: String = "revision_conflict"

    /** The refusal of a malformed request. */
    const val INVALID_REQUEST: String = "invalid_request"

    /** Param and result field holding the ordered agent ids. */
    const val AGENT_IDS: String = "agent_ids"

    /** Param of `pins.set`: the revision the caller's list was based on. */
    const val EXPECTED_REVISION: String = "expected_revision"
}

/**
 * The shared list at one [revision]: 0 until the first accepted write, then one more for every
 * accepted write. [agentIds] is in pin order, without duplicates.
 */
@Serializable
data class AgentPinsSnapshot(
    @SerialName("revision") val revision: Long = 0,
    @SerialName(AgentPinsWire.AGENT_IDS) val agentIds: List<AgentId> = emptyList(),
)
