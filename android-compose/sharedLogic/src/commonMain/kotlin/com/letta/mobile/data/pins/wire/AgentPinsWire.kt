package com.letta.mobile.data.pins.wire

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

    /** The refusal when the host cannot read its stored list; nothing is changed. */
    const val STATE_UNREADABLE: String = "state_unreadable"

    /** Param and result field holding the ordered agent ids. */
    const val AGENT_IDS: String = "agent_ids"

    /** Param of `pins.set`: the revision the caller's list was based on. */
    const val EXPECTED_REVISION: String = "expected_revision"
}

/** A pins failure as the client sees it; any code it does not know reads as [UNKNOWN]. */
enum class AgentPinsError {
    /** Another device changed the list first. */
    REVISION_CONFLICT,

    /** The request was malformed. */
    INVALID_REQUEST,

    /** This device may not read or change the list (not authorized, or bounded to some agents). */
    FORBIDDEN,

    /** The host cannot read its stored list. */
    STATE_UNREADABLE,

    /** The host does not serve the method. */
    UNSUPPORTED,

    /** No answer: the link failed or the reply was unreadable. */
    UNREACHABLE,

    /** A code this client does not know. */
    UNKNOWN,
    ;

    companion object {
        /** The error for the host's `error` string; null reads as [UNREACHABLE]. */
        fun fromWire(code: String?): AgentPinsError = when {
            code == null -> UNREACHABLE
            code == AgentPinsWire.REVISION_CONFLICT -> REVISION_CONFLICT
            code == AgentPinsWire.INVALID_REQUEST -> INVALID_REQUEST
            code == AgentPinsWire.STATE_UNREADABLE -> STATE_UNREADABLE
            code.startsWith("forbidden") -> FORBIDDEN
            code.startsWith("Unknown method") -> UNSUPPORTED
            else -> UNKNOWN
        }
    }
}
