package com.letta.mobile.data.repository

import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ApprovalCreate
import com.letta.mobile.data.model.ApprovalSubmission
import com.letta.mobile.data.model.MessageCreateRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

internal object MessageRepositoryApproval {
    suspend fun submitApproval(
        messageApi: MessageApi,
        irohApprovalSource: IrohAdminRpcApprovalSource?,
        json: Json,
        agentId: AgentId,
        approvalRequestId: String,
        toolCallIds: List<String>,
        approve: Boolean,
        reason: String?,
    ) {
        val trimmedReason = reason?.takeIf { it.isNotBlank() }
        val request = MessageCreateRequest(
            messages = listOf(
                json.encodeToJsonElement(
                    ApprovalCreate.serializer(),
                    ApprovalCreate(
                        approvals = toolCallIds.map { toolCallId ->
                            ApprovalSubmission(
                                toolCallId = toolCallId,
                                approve = approve,
                                reason = trimmedReason,
                            )
                        },
                        approve = approve,
                        approvalRequestId = approvalRequestId,
                        reason = trimmedReason,
                    )
                )
            ),
            streaming = false,
        )

        val irohApproval = irohApprovalSource
        if (irohApproval?.shouldUseIroh() == true) {
            irohApproval.submitApproval(agentId, request)
        } else {
            messageApi.sendMessage(agentId, request)
        }
    }
}
