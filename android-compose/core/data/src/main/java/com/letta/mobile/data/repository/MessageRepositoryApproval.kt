package com.letta.mobile.data.repository

import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ApprovalCreate
import com.letta.mobile.data.model.ApprovalSubmission
import com.letta.mobile.data.model.MessageCreateRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

internal data class ApprovalSubmitParams(
    val messageApi: MessageApi,
    val irohApprovalSource: IrohAdminRpcApprovalSource?,
    val json: Json,
    val agentId: AgentId,
    val approvalRequestId: String,
    val toolCallIds: List<String>,
    val approve: Boolean,
    val reason: String?,
)

internal object MessageRepositoryApproval {
    suspend fun submitApproval(params: ApprovalSubmitParams) {
        val trimmedReason = params.reason?.takeIf { it.isNotBlank() }
        val request = buildApprovalRequest(
            json = params.json,
            approvalRequestId = params.approvalRequestId,
            toolCallIds = params.toolCallIds,
            approve = params.approve,
            reason = trimmedReason,
        )

        val irohApproval = params.irohApprovalSource
        if (irohApproval?.shouldUseIroh() == true) {
            irohApproval.submitApproval(params.agentId, request)
        } else {
            params.messageApi.sendMessage(params.agentId, request)
        }
    }

    private fun buildApprovalRequest(
        json: Json,
        approvalRequestId: String,
        toolCallIds: List<String>,
        approve: Boolean,
        reason: String?,
    ): MessageCreateRequest =
        MessageCreateRequest(
            messages = listOf(
                json.encodeToJsonElement(
                    ApprovalCreate.serializer(),
                    ApprovalCreate(
                        approvals = toolCallIds.map { toolCallId ->
                            ApprovalSubmission(
                                toolCallId = toolCallId,
                                approve = approve,
                                reason = reason,
                            )
                        },
                        approve = approve,
                        approvalRequestId = approvalRequestId,
                        reason = reason,
                    )
                )
            ),
            streaming = false,
        )
}
