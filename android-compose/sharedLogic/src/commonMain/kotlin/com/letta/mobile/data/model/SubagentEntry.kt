package com.letta.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * letta-mobile-73o2h.1: wire shape of a single registered subagent as
 * carried over the shim's active-subagent registry (sister to the cron
 * protocol). Mirrors the entry shape documented in
 * `admin-shim/docs/MOBILE_WS_PROTOCOL.md` §13.2 verbatim so the persisted
 * registry shape and the wire shape stay in sync.
 *
 * Correlation seam (§13.1):
 *  - [toolCallId] is the parent Agent tool_call's `tool_call_id` — the
 *    canonical correlation key mobile uses everywhere.
 *  - [taskId] / [subagentAgentId] / [parentRunId] are derived from the
 *    background dispatch's return body; they are absent for synchronous
 *    dispatches that haven't returned identity yet.
 *  - [parentAgentId] / [parentConversationId] (letta-mobile-m6oa1.2, §13.2)
 *    are the INTERIM shim-emitted parent provenance so mobile can group
 *    ephemeral subagents by authoritative parent identity WITHOUT inferring
 *    from the display name. Both are optional + nullable: older shims omit
 *    them, and the shim emits null when the parent identity is not in scope
 *    at the ingesting call site. Origination moves to the Kotlin App Server
 *    later in epic m6oa1.
 *
 * Status vocabulary (§13.2): `running` | `completed` | `failed` |
 * `cancelled`. `cancelled` (letta-mobile-drv4a) is the NON-CLEAN terminal
 * outcome — a subagent that was killed / evicted / orphaned / TaskStop'd, or
 * whose backing process died without a completion frame. The shim is the
 * source of truth; mobile renders whatever string arrives — [SubagentStatus]
 * exposes the documented vocabulary as constants without forcing a closed
 * enum (forward-compat with future shim additions).
 *
 * Timestamps are ISO-8601 strings on the wire; mobile does not parse them
 * eagerly at this layer.
 */
/**
 * letta-mobile-i2f23: wire shape of the shim's per-subagent
 * todo_progress snapshot — { completed: Int, total: Int }. Nullable
 * with default on [SubagentEntry] so older shims parse without error.
 */
@Serializable
data class SubagentTodoProgressWire(
    val completed: Int = 0,
    val total: Int = 0,
)

@Serializable
data class SubagentEntry(
    @SerialName("toolCallId") val toolCallId: String,
    val description: String = "",
    @SerialName("subagentType") val subagentType: String = "",
    val status: String,
    @SerialName("taskId") val taskId: String? = null,
    @SerialName("subagentAgentId") val subagentAgentId: String? = null,
    @SerialName("subagentConversationId") val subagentConversationId: String? = null,
    @SerialName("parentRunId") val parentRunId: String? = null,
    // letta-mobile-m6oa1.2 (§13.2): interim shim-emitted parent provenance.
    // Nullable defaults so older wire (which omits these) parses cleanly.
    @SerialName("parentAgentId") val parentAgentId: String? = null,
    @SerialName("parentConversationId") val parentConversationId: String? = null,
    @SerialName("startedAt") val startedAt: String? = null,
    @SerialName("todo_progress") val todoProgress: SubagentTodoProgressWire? = null,
)

object SubagentStatus {
    const val RUNNING = "running"
    const val COMPLETED = "completed"
    const val FAILED = "failed"

    /**
     * letta-mobile-drv4a: non-clean terminal outcome — the subagent was
     * killed / evicted (idle reaper) / orphaned / TaskStop'd, or its backing
     * process/session/run died without writing a completion frame. The shim's
     * reaper finalizes such entries `cancelled` so the chip stops being stuck
     * `running` forever. Rendered as a terminal (failed-styled) chip that
     * lingers then dismisses (29h9u).
     */
    const val CANCELLED = "cancelled"
}

/**
 * letta-mobile-73o2h.1: one TodoWrite entry from a subagent's latest
 * snapshot (§13.3). `status` mirrors the TodoWrite tool vocabulary:
 * `pending | in_progress | completed`.
 */
@Serializable
data class SubagentTodo(
    val content: String = "",
    val status: String = "",
    val activeForm: String = "",
)
