package com.letta.mobile.data.transport.appserver

import com.letta.mobile.data.controller.AppServerApprovalDecisions
import com.letta.mobile.data.controller.ApprovalRejectedException
import com.letta.mobile.data.controller.ApprovalSubmission
import com.letta.mobile.data.controller.ApprovalSubmitResult
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AskUserQuestion
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.runtime.AppServerTurnEngine

/** One approval answer for an App Server conversation. */
data class AppServerApprovalAnswer(
    val agentId: String,
    val conversationId: String,
    val requestId: String,
    /** The tool call the request is about, when known; an ask-user request is answered by its own id. */
    val toolCallId: String?,
    val approve: Boolean,
    val reason: String?,
)

/**
 * letta-mobile-o4ygk.4.5: stopping a run and answering approvals over the [AppServerTurnEngine]
 * that runs the turns, in commonMain so every App Server client answers the same way. The engine
 * waits for the server to accept an answer and re-answers a replayed request; a rejection is
 * raised as [ApprovalRejectedException] for the caller to show.
 */
class AppServerRunControls(
    private val engine: AppServerTurnEngine,
    private val clientLabel: String = "web client",
) {
    /** Asks the server to abort the conversation's active run; true when it confirmed the abort. */
    suspend fun stop(agent: AgentId, conversation: ConversationId): Boolean {
        val response = engine.abort(agent.value, conversation.value, runId = null)
        return response.success && response.aborted
    }

    suspend fun answer(answer: AppServerApprovalAnswer) {
        val captured = answer.toolCallId?.let(engine::userInputApprovalId)
        val decision = AppServerApprovalDecisions.decide(
            approve = answer.approve,
            updatedInput = if (answer.approve) AskUserQuestion.decodeAnswerReason(answer.reason) else null,
            message = answer.reason,
            defaultApproveMessage = "Approved by $clientLabel.",
            defaultDenyMessage = "Denied by $clientLabel.",
        )
        val scope = AppServerRuntimeScope(agentId = answer.agentId, conversationId = answer.conversationId)
        val result = engine.submitApprovalResponse(ApprovalSubmission(scope, captured ?: answer.requestId, decision))
        if (result is ApprovalSubmitResult.Rejected) throw ApprovalRejectedException(result.error)
        if (captured != null) engine.clearUserInputApprovalId(answer.toolCallId, captured)
    }
}
