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

@Serializable
data class BackendCapabilities(
    val supportsStreaming: Boolean,
    val supportsMemFs: Boolean,
    val supportsTools: Boolean,
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
    suspend fun append(draft: RuntimeEventDraft): RuntimeEventEnvelope

    fun events(afterOffset: RuntimeEventOffset): Flow<RuntimeEventEnvelope>
}
