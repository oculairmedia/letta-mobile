package com.letta.mobile.runtime

import com.letta.mobile.data.model.AgentId
import kotlin.jvm.JvmInline
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Nonblank identity correlating a tool invocation, its result and any approval request. */
@JvmInline
@Serializable
value class ToolCallId(
    /** Nonblank invocation key shared by tool-call, result and approval records. */
    val value: String
) {
    init {
        require(value.isNotBlank()) { "ToolCallId cannot be blank." }
    }

    /** Returns the invocation key used to correlate tool records. */
    override fun toString(): String = value
}

/** Nonblank model-facing tool name. A name identifies a tool definition, not one invocation. */
@JvmInline
@Serializable
value class ToolName(
    /** Nonblank tool-definition name; construction does not verify registration or permissions. */
    val value: String
) {
    init {
        require(value.isNotBlank()) { "ToolName cannot be blank." }
    }

    /** Returns the model-facing tool name unchanged. */
    override fun toString(): String = value
}

/** Nonblank key for a pending approval, separate from the tool-call identity. */
@JvmInline
@Serializable
value class ToolApprovalId(
    /** Nonblank approval key; construction does not check whether an approval is pending. */
    val value: String
) {
    init {
        require(value.isNotBlank()) { "ToolApprovalId cannot be blank." }
    }

    /** Returns the approval key used by requests and decisions. */
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
