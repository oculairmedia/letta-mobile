package com.letta.mobile.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Execution location of a backend. Descriptive identity, not an authorization grant.
 */
@Serializable
enum class BackendKind {
    /** Execution on a remote Letta backend. */
    RemoteLetta,
    /** Execution by the local letta-code runtime. */
    LocalLettaCode,
}

/**
 * Identity and advertised feature set of one backend/runtime pair.
 * A display label is not a stable cache key; scope stored events by [backendId] and [runtimeId].
 */
@Serializable
data class BackendDescriptor(
    /** Backend scope that owns this record. */
    val backendId: BackendId,
    /** Runtime scope within the backend. */
    val runtimeId: RuntimeId,
    /** Execution location advertised by this backend. */
    val kind: BackendKind,
    /** Human-readable backend name; not a stable identity. */
    val label: String,
    /** Operations advertised as supported by the backend. */
    val capabilities: BackendCapabilities,
)

/**
 * Backend-advertised features used to select supported UI/runtime operations.
 *
 * Tool event projection, execution and approval round-trips are independent flags. A backend
 * may expose tool cards without executing tools locally. These flags describe support; they do
 * not replace per-call authorization or the human answers required by user-input tools.
 */
@Serializable
data class BackendCapabilities(
    /** Whether incremental runtime events are available. */
    val supportsStreaming: Boolean,
    /** Whether virtual-memory file operations are available. */
    val supportsMemFs: Boolean,
    /** Whether tool call and return events can be projected. */
    val supportsToolEvents: Boolean,
    /** Whether this backend can execute tools. */
    val supportsToolExecution: Boolean,
    /** Whether tool approval request/response round-trips are supported. */
    val supportsApprovals: Boolean,
    /** Whether agent file import is supported. */
    val supportsAgentFileImport: Boolean,
    /** Whether agent file export is supported. */
    val supportsAgentFileExport: Boolean,
)

/**
 * Append/read boundary for runtime events. Implementations choose their storage lifetime;
 * this interface does not promise disk persistence, transport delivery or idempotent retries.
 * Consumers resume with outbox offsets, not upstream wire sequence numbers.
 */
interface RuntimeEventOutbox {
    /**
     * Append one draft, assigning envelope identity and offset through [appendAll].
     */
    suspend fun append(draft: RuntimeEventDraft): RuntimeEventEnvelope = appendAll(listOf(draft)).single()

    /**
     * Append drafts in caller order and return their assigned envelopes.
     * Transaction/failure semantics are implementation-specific; retrying a payload is not an
     * idempotency guarantee.
     */
    suspend fun appendAll(drafts: List<RuntimeEventDraft>): List<RuntimeEventEnvelope>

    /**
     * Observe records strictly after [afterOffset]. Retention and collection lifetime follow
     * the chosen implementation; the cursor is exclusive.
     */
    fun events(afterOffset: RuntimeEventOffset): Flow<RuntimeEventEnvelope>
}
