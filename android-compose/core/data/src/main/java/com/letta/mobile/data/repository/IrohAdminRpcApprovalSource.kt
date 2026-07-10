package com.letta.mobile.data.repository

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * letta-mobile-qfa81 (P4, inventory row 13): routes user-critical approval
 * submission over `admin_rpc` while the active backend is `iroh://`.
 *
 * Approvals arrive mid-turn (an `approval_request_message` interrupts a running
 * turn). We submit on the per-request `admin_rpc` stream path
 * ([IChannelTransport.adminRpc] → its own bi-stream), so the submission is
 * isolated from the live turn stream and a failure is scoped to this request
 * only (k7yyc) — it never tears down the streaming turn.
 *
 * The server [ApprovalAdminHandlers] `approval.submit` handler forwards
 * [payload] verbatim to `POST /v1/agents/{agent_id}/messages`, identical to the
 * HTTP path (`MessageApi.sendMessage`).
 */
class IrohAdminRpcApprovalSource(
    private val channelTransport: IChannelTransport,
    private val settingsRepository: ISettingsRepository,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {
    fun shouldUseIroh(): Boolean =
        settingsRepository.activeBackendIsIroh()

    suspend fun submitApproval(agentId: AgentId, payload: MessageCreateRequest) {
        val body = buildJsonObject {
            put("agent_id", agentId.value)
            put(
                "payload",
                json.encodeToJsonElement(MessageCreateRequest.serializer(), payload),
            )
        }
        val response = channelTransport.adminRpc(
            method = "approval.submit",
            path = "/v1/agents/${agentId.value}/messages",
            body = body.toString(),
        )
        if (!response.success) {
            error(response.error ?: "Iroh admin_rpc approval.submit failed")
        }
    }
}
