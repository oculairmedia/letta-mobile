package com.letta.mobile.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
enum class BackendKind {
    RemoteLetta,
    LocalLettaCode,
    LocalKoog,
    CompatibleRuntime,
}

@Serializable
data class BackendDescriptor(
    val backendId: BackendId,
    val runtimeId: RuntimeId,
    val kind: BackendKind,
    val label: String,
    val capabilities: BackendCapabilities,
)

/**
 * Capabilities advertised by a backend runtime.
 *
 * **Tool capability split (letta-mobile-nq1le):** The original `supportsTools` flag conflated
 * three distinct concerns: whether tool-call/tool-return events project into the timeline,
 * whether tools actually execute, and whether approval round-trips are meaningful. Local runtimes
 * project AND execute tools, but auto-approve all turns (no approval UI round-trip). The split
 * fields make this expressible:
 * - `supportsToolEvents`: tool_call/tool_return events project into the timeline; tool cards render.
 * - `supportsToolExecution`: tools actually execute on this backend.
 * - `supportsApprovals`: approval round-trips (approve/reject UI) are meaningful; false means
 *   auto-approved (no approval affordance).
 *
 * The legacy `supportsTools` field is kept for back-compat and derived as
 * `supportsToolEvents || supportsToolExecution` — if either is true, some tool capability exists.
 */
@Serializable
data class BackendCapabilities(
    val supportsStreaming: Boolean,
    val supportsMemFs: Boolean,
    val supportsToolEvents: Boolean,
    val supportsToolExecution: Boolean,
    val supportsApprovals: Boolean,
    val supportsAgentFileImport: Boolean,
    val supportsAgentFileExport: Boolean,
) {
    /**
     * Legacy compat flag: true if the backend supports tool events OR tool execution.
     * Kept for consumers that haven't migrated to the split fields yet.
     */
    val supportsTools: Boolean
        get() = supportsToolEvents || supportsToolExecution
}

interface LettaBackend {
    val descriptor: BackendDescriptor

    fun runTurn(command: TurnCommand): Flow<RuntimeEventEnvelope>

    fun events(afterOffset: RuntimeEventOffset): Flow<RuntimeEventEnvelope>
}

interface RuntimeEventOutbox {
    suspend fun append(draft: RuntimeEventDraft): RuntimeEventEnvelope

    fun events(afterOffset: RuntimeEventOffset): Flow<RuntimeEventEnvelope>
}
