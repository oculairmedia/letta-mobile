package com.letta.mobile.data.repository

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ApprovalCreate
import com.letta.mobile.data.model.ApprovalSubmission
import com.letta.mobile.data.model.AskUserQuestion
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.repository.api.MessageRemoteSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal data class ApprovalSubmitParams(
    val remote: MessageRemoteSource,
    val irohApprovalSource: IrohAdminRpcApprovalSource?,
    val json: Json,
    val agentId: AgentId,
    val approvalRequestId: String,
    val toolCallIds: List<String>,
    val approve: Boolean,
    val reason: String?,
    val conversationId: String? = null,
)

internal data class ApprovalRequestBuildParams(
    val json: Json,
    val approvalRequestId: String,
    val toolCallIds: List<String>,
    val approve: Boolean,
    val reason: String?,
    val updatedInput: JsonObject?,
)

internal object MessageRepositoryApproval {
    suspend fun submitApproval(params: ApprovalSubmitParams) {
        val decodedAnswer = AskUserQuestion.decodeAnswerReason(params.reason)
        val effectiveReason = if (decodedAnswer != null) {
            null
        } else {
            params.reason?.takeIf { it.isNotBlank() }
        }
        val request = buildApprovalRequest(
            ApprovalRequestBuildParams(
                json = params.json,
                approvalRequestId = params.approvalRequestId,
                toolCallIds = params.toolCallIds,
                approve = params.approve,
                reason = effectiveReason,
                updatedInput = decodedAnswer,
            ),
        )

        val irohApproval = params.irohApprovalSource
        if (irohApproval?.shouldUseIroh() == true) {
            irohApproval.submitApproval(params.agentId, request, params.conversationId)
        } else {
            params.remote.sendMessage(params.agentId, request)
        }
    }

    private fun buildApprovalRequest(params: ApprovalRequestBuildParams): MessageCreateRequest =
        MessageCreateRequest(
            messages = listOf(
                params.json.encodeToJsonElement(
                    ApprovalCreate.serializer(),
                    ApprovalCreate(
                        approvals = params.toolCallIds.map { toolCallId ->
                            ApprovalSubmission(
                                toolCallId = toolCallId,
                                approve = params.approve,
                                reason = params.reason,
                            )
                        },
                        approve = params.approve,
                        approvalRequestId = params.approvalRequestId,
                        reason = params.reason,
                        updatedInput = params.updatedInput,
                    )
                )
            ),
            streaming = false,
        )
}
