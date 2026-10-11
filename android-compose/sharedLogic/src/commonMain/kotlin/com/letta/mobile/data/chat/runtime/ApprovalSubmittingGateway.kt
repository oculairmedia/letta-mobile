package com.letta.mobile.data.chat.runtime

/**
 * Capability a [ChatGateway] may implement to answer / dismiss a parked runtime
 * approval (e.g. AskUserQuestion) over its own transport.
 *
 * letta-mobile-vilsn: desktop over Iroh uses [com.letta.mobile.data.repository.iroh.IrohAdminRpcChatGateway],
 * which had no approval-submit path — so desktop approvals were a silent no-op.
 * This interface lets any gateway expose one; callers detect it via
 * `gateway as? ApprovalSubmittingGateway`.
 *
 * [reason] may carry an AskUserQuestion answer encoded via
 * `com.letta.mobile.data.model.AskUserQuestion.encodeAnswerReason`; the gateway
 * decodes it to the structured `updated_input` close payload, otherwise it is a
 * plain allow/deny message.
 */
interface ApprovalSubmittingGateway {
    suspend fun submitApproval(
        agentId: String,
        conversationId: String,
        approvalRequestId: String,
        toolCallId: String?,
        approve: Boolean,
        reason: String?,
    )

    /**
     * letta-mobile-bzvro.40: approves [toolCallId] and persists the server-offered rules
     * [suggestionIds] ("always allow"). A gateway that cannot carry them refuses, rather than approve
     * without the rule the person chose.
     */
    suspend fun submitAlwaysAllow(
        agentId: String,
        conversationId: String,
        approvalRequestId: String,
        toolCallId: String,
        suggestionIds: List<String>,
    ): Unit = throw UnsupportedOperationException("always-allow rules are not supported by this connection")
}

/**
 * letta-mobile-bzvro.11/.12/.40: a gateway that can say what each parked `can_use_tool` request
 * offered (always-allow rules, blocked path, diffs), by tool call id: one that runs the App Server
 * turn engine itself, or an Iroh gateway fed by its host's `pending_approvals` relay.
 */
interface PendingApprovalSource {
    val pendingApprovalDetails: kotlinx.coroutines.flow.StateFlow<Map<String, com.letta.mobile.data.runtime.PendingApprovalDetails>>
}

/**
 * letta-mobile-bzvro.13/.40: a gateway that can read and change a conversation's permission mode. One
 * that cannot reports no [permissionModes], and the composer shows no mode chip for it.
 */
interface PermissionModeGateway {
    /** The registry this gateway's conversations are governed by, or null when it has no mode support. */
    val permissionModes: com.letta.mobile.data.runtime.PermissionModeRegistry?

    /** Asks for [mode] on the conversation's runtime; true once the server confirmed it. */
    suspend fun setPermissionMode(
        runtime: com.letta.mobile.data.transport.appserver.AppServerRuntimeScope,
        mode: com.letta.mobile.data.transport.appserver.AppServerPermissionMode,
    ): Boolean

    /** Brings the chip and the server into agreement when a conversation is opened (a relaying host). */
    suspend fun syncPermissionMode(runtime: com.letta.mobile.data.transport.appserver.AppServerRuntimeScope) = Unit
}
