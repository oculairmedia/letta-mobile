package com.letta.mobile.data.model

/**
 * Normalizes Matrix-namespaced agent IDs (`letta_agent-...`) to bare IDs (`agent-...`)
 * for storage and candidate lookups in the local backend, and formats bare IDs to
 * Matrix-namespaced IDs when outbound transport requires it.
 */
object AgentIdNamespace {
    private const val PREFIX = "letta_"

    /**
     * Strips leading `"letta_"` if present (e.g. `"letta_agent-123"` -> `"agent-123"`),
     * leaving already-bare IDs untouched.
     */
    fun normalizeToBareId(id: String): String {
        return if (id.startsWith(PREFIX)) {
            id.substring(PREFIX.length)
        } else {
            id
        }
    }

    /**
     * Adds `"letta_"` if not present (e.g. `"agent-123"` -> `"letta_agent-123"`).
     */
    fun toMatrixId(id: String): String {
        return if (id.startsWith(PREFIX)) {
            id
        } else {
            "$PREFIX$id"
        }
    }
}
