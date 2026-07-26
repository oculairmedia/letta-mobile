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
}
