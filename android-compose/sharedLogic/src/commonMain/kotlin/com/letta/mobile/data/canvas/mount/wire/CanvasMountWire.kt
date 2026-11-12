package com.letta.mobile.data.canvas.mount.wire

/**
 * Wire names of the canvas mount admin RPC routes (`docs/reference/contracts/canvas-mount-v1.md`),
 * shared by the host routes and a later client. Only names live here: no behaviour, no client.
 */
object CanvasMountWire {
    /** Host capability advertised exactly when all four routes are registered. */
    const val CAPABILITY: String = "canvas.mount.v1"

    /** Reads a conversation's binding, falling back to its agent's default. */
    const val RESOLVE: String = "canvas.mount.resolve"

    /** Binds a conversation only when it has no binding; answers the durable winner. */
    const val BIND: String = "canvas.mount.binding.bind"

    /** Replaces a conversation's binding (last writer wins). */
    const val REPLACE: String = "canvas.mount.binding.replace"

    /** Sets an agent's default (last writer wins). */
    const val SET_DEFAULT: String = "canvas.mount.default.set"

    /**
     * Push frame `type` sent to the viewers of a conversation whose binding changed:
     * `{"type": "canvas_mount_updated", "agent_id", "conversation_id", "canvas_id", "durability"}`.
     */
    const val EVENT_TYPE: String = "canvas_mount_updated"

    /** Every route, registered together or not at all. */
    val METHODS: Set<String> = setOf(RESOLVE, BIND, REPLACE, SET_DEFAULT)

    /** Peer capability that admits [RESOLVE]. */
    const val READ_CAPABILITY: String = "canvas.mount.read"

    /** Peer capability that admits [BIND], [REPLACE] and [SET_DEFAULT]. */
    const val WRITE_CAPABILITY: String = "canvas.mount.write"

    /** The agent that owns the addressed record (a target, never the caller). */
    const val AGENT_ID: String = "agent_id"

    /** The conversation whose binding is addressed. */
    const val CONVERSATION_ID: String = "conversation_id"

    /** The semantic canvas id stored or answered. */
    const val CANVAS_ID: String = "canvas_id"

    /** Result field naming the outcome; a client treats an unknown value as [UNAVAILABLE]. */
    const val STATUS: String = "status"

    /** Result field of [FOUND]: [SOURCE_BINDING] or [SOURCE_DEFAULT]. */
    const val SOURCE: String = "source"

    /** Result field of [BOUND]: whether the caller's own proposal is the winner. */
    const val WON: String = "won"

    /** Result field of every canvas-bearing outcome: [DURABLE] or [ENTRY_UNCONFIRMED]. */
    const val DURABILITY: String = "durability"

    /** Result field of [UNAVAILABLE]: one of the fixed `REASON_` values. */
    const val REASON: String = "reason"

    /** A resolve found a canvas the caller may see. */
    const val FOUND: String = "found"

    /** Authorized for the record, which does not exist. */
    const val NONE: String = "none"

    /** A bind finished with a durable winner the caller may see. */
    const val BOUND: String = "bound"

    /** A binding now holds the caller's canvas. */
    const val REPLACED: String = "replaced"

    /** An agent default now holds the caller's canvas. */
    const val DEFAULT_SET: String = "default_set"

    /** Failed before any change; the current mount stays as it is. */
    const val UNAVAILABLE: String = "unavailable"

    /** A mutation may or may not have committed; resolve again before acting on it. */
    const val OUTCOME_UNKNOWN: String = "outcome_unknown"

    /** The canvas came from the conversation's own binding. */
    const val SOURCE_BINDING: String = "binding"

    /** The canvas came from the agent's default. */
    const val SOURCE_DEFAULT: String = "default"

    /** The host confirms the record's contents and its directory entry. */
    const val DURABLE: String = "durable"

    /** Contents are forced and atomically replaced, but the directory entry is unconfirmed (Windows hosts). */
    const val ENTRY_UNCONFIRMED: String = "entry_unconfirmed"

    /** The mount storage failed. */
    const val REASON_STORAGE: String = "storage"

    /** The host's grant or conversation records could not be consulted. */
    const val REASON_AUTHORITY: String = "authority"

    /** The call passed its deadline before anything could commit. */
    const val REASON_TIMEOUT_BEFORE_COMMIT: String = "timeout_before_commit"

    /** The serving connection has no verified identity. */
    const val REASON_IDENTITY: String = "identity"

    /** The one refusal text for every denial, whichever check failed and whether or not a record exists. */
    const val DENIED: String = "canvas.mount.denied"

    /**
     * Prefix of the refusal text for invalid params: `canvas.mount.invalid_input:<field>:<code>`, where
     * the field and code come from fixed lists in the contract, never from the request.
     */
    const val INVALID_INPUT_PREFIX: String = "canvas.mount.invalid_input"
}
