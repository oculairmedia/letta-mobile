package com.letta.mobile.runtime

import com.letta.mobile.data.model.AgentId
import kotlin.jvm.JvmInline
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class ToolCallId(val value: String) {
    init {
        require(value.isNotBlank()) { "ToolCallId cannot be blank." }
    }

    override fun toString(): String = value
}

@JvmInline
@Serializable
value class ToolName(val value: String) {
    init {
        require(value.isNotBlank()) { "ToolName cannot be blank." }
    }

    override fun toString(): String = value
}

@JvmInline
@Serializable
value class ToolApprovalId(val value: String) {
    init {
        require(value.isNotBlank()) { "ToolApprovalId cannot be blank." }
    }

    override fun toString(): String = value
}

@Serializable
data class TurnCommand(
    val backendId: BackendId,
    val runtimeId: RuntimeId,
    val agentId: AgentId,
    val conversationId: ConversationId,
    val input: TurnInput,
    val memFsRevision: MemFsRevision? = null,
    val toolPolicy: ToolPolicy = ToolPolicy(),
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
sealed interface TurnInput {
    @Serializable
    @SerialName("user_message")
    data class UserMessage(
        val localMessageId: String,
        val text: String,
        val attachments: List<AgentFileId> = emptyList(),
    ) : TurnInput

    @Serializable
    @SerialName("tool_approval_response")
    data class ToolApprovalResponse(
        val decision: ToolApprovalDecision,
    ) : TurnInput
}

@Serializable
data class ToolPolicy(
    val approvalMode: ToolApprovalMode = ToolApprovalMode.RequireForSensitiveTools,
    val allowedTools: Set<ToolName> = emptySet(),
    val deniedTools: Set<ToolName> = emptySet(),
)

@Serializable
enum class ToolApprovalMode {
    NeverRequire,
    RequireForSensitiveTools,
    RequireForEveryCall,
}

@Serializable
data class ToolExecutionRequest(
    val callId: ToolCallId,
    val name: ToolName,
    val argumentsJson: String? = null,
    val approval: ToolApprovalRequest? = null,
)

@Serializable
data class ToolExecutionResult(
    val callId: ToolCallId,
    val status: ToolExecutionStatus,
    val body: String,
    val errorMessage: String? = null,
)

@Serializable
enum class ToolExecutionStatus {
    Pending,
    Running,
    Succeeded,
    Failed,
    Cancelled,
}

@Serializable
data class ToolApprovalRequest(
    val approvalId: ToolApprovalId,
    val callId: ToolCallId,
    val toolName: ToolName,
    val prompt: String,
    val argumentsPreview: String? = null,
)

@Serializable
data class ToolApprovalDecision(
    val approvalId: ToolApprovalId,
    val callId: ToolCallId,
    val decision: ToolApprovalDecisionValue,
    val scope: ToolApprovalScope,
    val response: String? = null,
)

@Serializable
enum class ToolApprovalDecisionValue {
    Approved,
    Denied,
    TimedOut,
}

@Serializable
enum class ToolApprovalScope {
    Once,
    Session,
    Forever,
}

fun interface TurnEngine {
    fun runTurn(command: TurnCommand): Flow<RuntimeEventDraft>
}
