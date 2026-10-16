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
 * **Tool capabilities (letta-mobile-nq1le):** three distinct concerns. Local runtimes project AND
 * execute tools, but auto-approve all turns (no approval UI round-trip):
 * - `supportsToolEvents`: tool_call/tool_return events project into the timeline; tool cards render.
 * - `supportsToolExecution`: tools actually execute on this backend.
 * - `supportsApprovals`: approval round-trips (approve/reject UI) are meaningful; false means
 *   auto-approved (no approval affordance).
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
)

interface LettaBackend {
    val descriptor: BackendDescriptor

    fun runTurn(command: TurnCommand): Flow<RuntimeEventEnvelope>

    fun events(afterOffset: RuntimeEventOffset): Flow<RuntimeEventEnvelope>
}

interface RuntimeEventOutbox {
    suspend fun append(draft: RuntimeEventDraft): RuntimeEventEnvelope = appendAll(listOf(draft)).single()

    suspend fun appendAll(drafts: List<RuntimeEventDraft>): List<RuntimeEventEnvelope>

    fun events(afterOffset: RuntimeEventOffset): Flow<RuntimeEventEnvelope>
}
