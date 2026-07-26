package com.letta.mobile.data.repository

import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ApprovalCreate
import com.letta.mobile.data.model.ApprovalSubmission
import com.letta.mobile.data.model.AskUserQuestion
import com.letta.mobile.data.model.MessageCreateRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal data class ApprovalSubmitParams(
    val messageApi: MessageApi,
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
        // letta-mobile-vilsn: an AskUserQuestion answer rides the `reason` channel
        // as a sentinel-encoded `updated_input` JSON (see
        // AskUserQuestion.encodeAnswerReason). Decode it HERE so BOTH transports
        // carry the structured answer: promote it to ApprovalCreate.updated_input
        // and drop the sentinel from the plain reason. Previously only the Iroh
        // admin_rpc path (DefaultAppServerController) re-decoded it while the plain
        // HTTP path sent it as an ordinary reason and dropped the answer.
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
            params.messageApi.sendMessage(params.agentId, request)
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
