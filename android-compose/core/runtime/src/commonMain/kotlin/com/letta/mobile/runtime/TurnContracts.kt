package com.letta.mobile.runtime

import com.letta.mobile.data.model.AgentId
import kotlin.jvm.JvmInline
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Nonblank identity correlating a tool invocation, its result and any approval request. */
@JvmInline
@Serializable
value class ToolCallId(/** Validated primitive representation of this value. */ val value: String) {
    init {
        require(value.isNotBlank()) { "ToolCallId cannot be blank." }
    }

    /** Returns the underlying value without a wrapper label. */
    override fun toString(): String = value
}

/** Nonblank model-facing tool name. A name identifies a tool definition, not one invocation. */
@JvmInline
@Serializable
value class ToolName(/** Validated primitive representation of this value. */ val value: String) {
    init {
        require(value.isNotBlank()) { "ToolName cannot be blank." }
    }

    /** Returns the underlying value without a wrapper label. */
    override fun toString(): String = value
}

/** Nonblank key for a pending approval, separate from the tool-call identity. */
@JvmInline
@Serializable
value class ToolApprovalId(/** Validated primitive representation of this value. */ val value: String) {
    init {
        require(value.isNotBlank()) { "ToolApprovalId cannot be blank." }
    }

    /** Returns the underlying value without a wrapper label. */
    override fun toString(): String = value
}

/**
 * Inputs for one turn scoped to a backend/runtime and agent/conversation.
 * [memFsRevision] and [toolPolicy] request context/policy; enforcement belongs to [TurnEngine].
 * Constructing this value does not start execution.
 */
@Serializable
data class TurnCommand(
    /** Backend scope that owns this record. */
    val backendId: BackendId,
    /** Runtime scope within the backend. */
    val runtimeId: RuntimeId,
    /** Agent whose turn will execute. */
    val agentId: AgentId,
    /** Conversation that will receive the turn. */
    val conversationId: ConversationId,
    /** User message or approval response to execute. */
    val input: TurnInput,
    /** Memory revision associated with the operation, when supplied. */
    val memFsRevision: MemFsRevision? = null,
    /** Requested tool execution and approval policy. */
    val toolPolicy: ToolPolicy = ToolPolicy(),
    /** Additional backend-specific turn context. */
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * An inline image carried on a local [TurnInput.UserMessage]. [base64] is the raw
 * base64 payload with NO `data:` prefix — the embedded letta.js runtime ingests
 * the Letta `ImageContent`/`Base64Image` union (`source.type == "base64"`,
 * `source.media_type`, `source.data`), the same shape the remote path uses.
 */
@Serializable
data class TurnImagePart(
    /** Encoded image bytes without a data-URL prefix. */
    val base64: String,
    /** Media type of the encoded image. */
    val mediaType: String,
)

/**
 * A user message or a response to an existing tool approval.
 * Preserve approval/call identity when answering; that response is not a new user message.
 */
@Serializable
sealed interface TurnInput {
    /** User-authored turn input with optional file references and inline multimodal parts. */
    @Serializable
    @SerialName("user_message")
    data class UserMessage(
        /** Client-assigned identity retained across delivery and retry. */
        val localMessageId: String,
        /** User-visible message text. */
        val text: String,
        /** Identities of existing agent files attached to this message. */
        val attachments: List<AgentFileId> = emptyList(),
        /**
         * Inline multimodal image parts (base64 + media type). When non-empty,
         * the local turn is sent to the embedded runtime as a content ARRAY
         * (Letta `LettaMessageContentUnion` shape) instead of a plain text
         * string, so a vision-capable provider receives the images.
         * Empty means no inline images were supplied by this field.
         */
        val imageParts: List<TurnImagePart> = emptyList(),
        /**
         * Raw App Server / websocket-compatible multimodal content array. Iroh
         * uses this to preserve text/image parts byte-for-byte with the WS path.
         */
        val contentPartsJson: String? = null,
    ) : TurnInput

    /** Answer to a previously requested tool approval, preserving its correlation IDs. */
    @Serializable
    @SerialName("tool_approval_response")
    data class ToolApprovalResponse(
        /** Correlated approval decision supplied by the person. */
        val decision: ToolApprovalDecision,
    ) : TurnInput
}

/**
 * Requested allow/deny sets and approval mode. Engines interpret and enforce the policy;
 * this value does not itself authorize execution or resolve overlapping sets.
 */
@Serializable
data class ToolPolicy(
    /** Requested approval prompting policy. */
    val approvalMode: ToolApprovalMode = ToolApprovalMode.RequireForSensitiveTools,
    /** Tools explicitly requested as permitted. */
    val allowedTools: Set<ToolName> = emptySet(),
    /** Tools explicitly requested as denied. */
    val deniedTools: Set<ToolName> = emptySet(),
)

/** Requested frequency of approval prompts; the engine applies supported policy. */
@Serializable
enum class ToolApprovalMode {
    /** Request execution without ordinary approval prompts. */
    NeverRequire,
    /** Request prompts for tools classified as sensitive. */
    RequireForSensitiveTools,
    /** Request approval for every tool invocation. */
    RequireForEveryCall,
}

/** Progress or terminal outcome of one tool invocation. */
@Serializable
enum class ToolExecutionStatus {
    /** Invocation is awaiting execution. */
    Pending,
    /** Invocation is executing. */
    Running,
    /** Invocation completed successfully. */
    Succeeded,
    /** Invocation terminated with an error. */
    Failed,
    /** Invocation was cancelled. */
    Cancelled,
}

/**
 * Human decision request correlated by approval and call IDs.
 * Arguments and diffs are display previews; submit the correlated IDs when answering.
 */
@Serializable
data class ToolApprovalRequest(
    /** Identity of the approval request being answered or displayed. */
    val approvalId: ToolApprovalId,
    /** Tool invocation associated with this record. */
    val callId: ToolCallId,
    /** Model-facing name of the invoked tool. */
    val toolName: ToolName,
    /** Question shown to the person deciding the approval. */
    val prompt: String,
    /** Optional tool arguments formatted for review. */
    val argumentsPreview: String? = null,
    /** Reusable "always allow" rules the server offered for this call. */
    val suggestions: List<PermissionSuggestion> = emptyList(),
    /** The path that tripped the permission check, when the server named one. */
    val blockedPath: String? = null,
    /** The change the call would make (Edit/Write), when previewable. */
    val diffs: List<ApprovalDiffPreview> = emptyList(),
)

/** One `permission_suggestions` entry: choosing [id] approves the call and persists the rule [text]. */
@Serializable
data class PermissionSuggestion(
    /** Server token to submit when selecting this rule. */
    val id: String,
    /** Human-readable reusable permission rule. */
    val text: String,
)

/**
 * One `diffs` entry of a `can_use_tool` control request, normalised to unified-diff text so the
 * shared diff renderer can draw it. [unifiedDiff] is null for a file the server could not preview
 * (then [note] says why, when it did).
 */
@Serializable
data class ApprovalDiffPreview(
    /** File affected by the proposed change, when known. */
    val path: String? = null,
    /** Preview in unified-diff format, or null when unavailable. */
    val unifiedDiff: String? = null,
    /** Explanation of an unavailable or incomplete preview. */
    val note: String? = null,
) {
    /** Nothing to show: no file, no diff and no note. */
    fun isEmpty(): Boolean = path == null && unifiedDiff == null && note == null
}

/**
 * Decision for an approval/call pair with a requested reuse scope.
 * The backend determines supported scopes; selecting Forever alone does not persist a rule.
 */
@Serializable
data class ToolApprovalDecision(
    /** Identity of the approval request being answered or displayed. */
    val approvalId: ToolApprovalId,
    /** Tool invocation associated with this record. */
    val callId: ToolCallId,
    /** Approved, denied or timed-out outcome. */
    val decision: ToolApprovalDecisionValue,
    /** Requested lifetime for reusing the decision. */
    val scope: ToolApprovalScope,
    /** Optional human answer carried with the approval decision. */
    val response: String? = null,
)

/** Human approval outcome, including expiry without an answer. */
@Serializable
enum class ToolApprovalDecisionValue {
    /** The person allowed the invocation. */
    Approved,
    /** The person rejected the invocation. */
    Denied,
    /** No decision arrived before expiry. */
    TimedOut,
}

/** Requested reuse lifetime for an approval; persistence is backend-dependent. */
@Serializable
enum class ToolApprovalScope {
    /** Apply only to this invocation. */
    Once,
    /** Request reuse for the current session. */
    Session,
    /** Request a persistent rule where the backend supports it. */
    Forever,
}

/**
 * Execution boundary producing unsequenced drafts for a requested turn.
 * The returned Flow carries lifecycle/content/tool events without assigning outbox offsets.
 * Implementations own execution and terminal/cancellation semantics; obtaining a flow alone
 * is not evidence that a remote turn has started.
 */
fun interface TurnEngine {
    /**
     * Produce events for [command]. The caller owns collection and its cancellation.
     */
    fun runTurn(command: TurnCommand): Flow<RuntimeEventDraft>
}
