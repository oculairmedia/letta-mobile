package com.letta.mobile.data.chat.runtime

/**
 * Capability a [ChatGateway] may implement to answer / dismiss a parked runtime
 * approval (e.g. AskUserQuestion) over its own transport.
 *
 * desktop over Iroh uses [com.letta.mobile.data.repository.iroh.IrohAdminRpcChatGateway],
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
     * Approves the bound call and persists the server-offered rules
     * ("always allow"). A gateway that cannot carry them refuses, rather than approve without the
     * rule the person chose.
     */
    suspend fun submitAlwaysAllow(answer: AlwaysAllowAnswer): Unit =
        throw UnsupportedOperationException("always-allow rules are not supported by this connection")
}

/**
 * An approval that persists [suggestionIds], bound to the parked request
 * ([binding]: its tool call and the control request's own id) whose card offered them.
 */
data class AlwaysAllowAnswer(
    val agentId: String,
    val conversationId: String,
    val binding: com.letta.mobile.data.runtime.ApprovalBinding,
    val suggestionIds: List<String>,
)

/**
 * A gateway that queues answers made while its connection is down (the
 * offline outbox) and says which requests are waiting, so their cards show it.
 */
interface ApprovalQueueSource {
    val queuedApprovalRequestIds: kotlinx.coroutines.flow.StateFlow<Set<String>>
}

/**
 * A gateway that can say what each parked `can_use_tool` request
 * offered (always-allow rules, blocked path, diffs), by tool call id: one that runs the App Server
 * turn engine itself, or an Iroh gateway fed by its host's `pending_approvals` relay.
 */
interface PendingApprovalSource {
    val pendingApprovalDetails: kotlinx.coroutines.flow.StateFlow<Map<String, com.letta.mobile.data.runtime.PendingApprovalDetails>>
}

/**
 * A gateway that can read and change a conversation's permission mode. One
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

    /**
     * Which runtime the chip governs, named on the chip so a desktop on its own
     * bundled runtime is not expected to mirror a phone talking to an Iroh host.
     */
    val permissionModeRuntime: com.letta.mobile.data.runtime.PermissionModeRuntime
        get() = com.letta.mobile.data.runtime.PermissionModeRuntime.Local
}
