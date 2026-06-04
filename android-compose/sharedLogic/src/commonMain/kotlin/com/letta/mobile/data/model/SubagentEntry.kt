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
 *
 * Status vocabulary (§13.2): `running` | `completed` | `failed`. The shim
 * is the source of truth; mobile renders whatever string arrives —
 * [SubagentStatus] exposes the documented vocabulary as constants without
 * forcing a closed enum (forward-compat with future shim additions).
 *
 * Timestamps are ISO-8601 strings on the wire; mobile does not parse them
 * eagerly at this layer.
 */
@Serializable
data class SubagentEntry(
    @SerialName("toolCallId") val toolCallId: String,
    val description: String = "",
    @SerialName("subagentType") val subagentType: String = "",
    val status: String,
    @SerialName("taskId") val taskId: String? = null,
    @SerialName("subagentAgentId") val subagentAgentId: String? = null,
    @SerialName("parentRunId") val parentRunId: String? = null,
    @SerialName("startedAt") val startedAt: String? = null,
)

object SubagentStatus {
    const val RUNNING = "running"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
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
