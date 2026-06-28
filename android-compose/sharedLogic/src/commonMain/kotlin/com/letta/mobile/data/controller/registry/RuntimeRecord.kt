package com.letta.mobile.data.controller.registry

import com.letta.mobile.data.controller.CanonicalRuntime
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.runtime.ConversationId
import kotlin.time.Instant

/**
 * Persistent record of a runtime session.
 *
 * This record tracks the lifecycle of a runtime (agent + conversation pair) across
 * App Server restarts and controller recovery. The controller stores these records
 * in a persistent registry (DB or file) and uses them to rebuild runtime state
 * after process restart.
 *
 * KEY INVARIANT: The controller DB is the source of truth for runtime lifecycle.
 * Never rely on in-memory runtime state alone (App Server can restart; controller
 * recovers from its own DB).
 *
 * @property id Unique stable ID for this runtime record (e.g., UUID)
 * @property agentId The agent ID for this runtime
 * @property conversationId The conversation ID for this runtime
 * @property cwd Optional working directory for the runtime
 * @property mode Optional permission mode used when the runtime was started
 * @property displayName Optional display name (e.g., "Chat with Agent X")
 * @property role Optional role descriptor (e.g., "assistant", "researcher")
 * @property lastStartedAt Timestamp of the last successful runtime_start
 * @property canonicalRuntime The canonical runtime metadata from the last runtime_start (nullable until first start)
 */
data class RuntimeRecord(
    val id: String,
    val agentId: AgentId,
    val conversationId: ConversationId,
    val cwd: String? = null,
    val mode: AppServerPermissionMode? = null,
    val displayName: String? = null,
    val role: String? = null,
    val lastStartedAt: Instant? = null,
    val canonicalRuntime: CanonicalRuntime? = null,
)
