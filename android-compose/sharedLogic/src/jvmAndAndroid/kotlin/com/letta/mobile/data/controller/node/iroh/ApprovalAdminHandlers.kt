package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * letta-mobile-qfa81 (P4, inventory row 13): user-critical approval submission
 * over `admin_rpc`. Approvals arrive while a turn is streaming (an
 * `approval_request_message` interrupts the run) and must be delivered on the
 * per-request `admin_rpc` stream path so the submission is isolated from the
 * live turn stream (k7yyc). The handler proxies the approval message to the
 * agent's non-streaming messages endpoint — the same REST call the HTTP path
 * (`MessageApi.sendMessage`) makes — so server-side semantics are identical.
 *
 * Wire params (from the client [IrohAdminRpcApprovalSource]):
 * - `agent_id`: the agent whose run is awaiting approval
 * - `payload`: the full `MessageCreateRequest` JSON (an `approval` message with
 *   `streaming=false`), forwarded verbatim as the POST body
 */
object ApprovalAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
        val proxy = AdminProxyClient(adminBaseUrl)
        router.register("approval.submit") { params -> submit(proxy, params) }
    }

    private fun submit(proxy: AdminProxyClient, params: JsonObject?): JsonElement {
        val agentId = params?.get("agent_id")?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("agent_id required")
        val payload = params["payload"]?.jsonObject
            ?: throw IllegalArgumentException("payload required")
        return proxy.post(
            adminProxyRequest("v1", "agents", agentId, "messages").build(),
            payload.toString(),
        )
    }
}
