package com.letta.mobile.data.controller.node.iroh.secrets

import com.letta.mobile.data.secrets.host.SecretRequestService
import com.letta.mobile.data.secrets.host.SecretServiceResult
import com.letta.mobile.data.secrets.request.AgentSecretRequest
import com.letta.mobile.data.secrets.request.RequestingAgent
import com.letta.mobile.data.secrets.request.SecretPurpose
import com.letta.mobile.data.secrets.request.SecretRequestCreated
import com.letta.mobile.data.secrets.request.SecretRequestId
import com.letta.mobile.data.secrets.request.SecretRequestList
import com.letta.mobile.data.secrets.request.SecretRequestReceipt
import com.letta.mobile.data.secrets.request.SecretRequestReceiptResult
import com.letta.mobile.data.secrets.request.SecretRequestRecord
import com.letta.mobile.data.secrets.request.SecretServiceError
import com.letta.mobile.data.secrets.request.VaultFieldName
import com.letta.mobile.data.secrets.request.VaultItemName
import com.letta.mobile.data.secrets.wire.SecretRequestRouteWire
import com.letta.mobile.plugin.keystore.SecretValue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One handler for every route, shared by the admin RPC registration and the `secret_request` tool.
 *
 * Order of work on every call: the principal from [SecretRequestCaller] (never from params), then
 * the strict params, then (human calls naming a request) whether the request exists and belongs to
 * an agent the device may see, then the service. A request that does not exist and one the caller
 * may not see answer the same code, so a denial tells nothing about other requests.
 */
internal class SecretRequestHandlers(
    private val service: SecretRequestService,
    /** Turns the submitted text into a value; a seam for tests that watch every value get wiped. */
    private val decode: (String) -> SecretValue? = SecretSubmission::decode,
) {
    /** Answers [route] for [caller]; [via] names the transport for telemetry only. */
    suspend fun handle(route: SecretRequestRoute, params: JsonObject?, caller: SecretRequestCaller, via: String): SecretRouteAnswer =
        answer(route, params, caller).report(route, via)

    private suspend fun answer(route: SecretRequestRoute, params: JsonObject?, caller: SecretRequestCaller): SecretRouteAnswer =
        when (route.side) {
            SecretRouteSide.AGENT -> agentOf(caller)?.let { agent -> withInput(route, params) { agentAnswer(route, agent, it) } }
            SecretRouteSide.HUMAN -> humanOf(caller)?.let { human -> withInput(route, params) { humanAnswer(route, human, it) } }
        } ?: SecretRouteAnswer.refused(SecretServiceError.FORBIDDEN)

    private suspend fun agentAnswer(route: SecretRequestRoute, agent: RequestingAgent, input: SecretRouteInput): SecretRouteAnswer =
        when (route) {
            SecretRequestRoute.REQUEST ->
                service.request(agent, input.agentRequest()).answer { wireOf(SecretRequestCreated.serializer(), it) }
            else -> status(agent, input.requestId())
        }

    private suspend fun humanAnswer(route: SecretRequestRoute, human: HumanCaller, input: SecretRouteInput): SecretRouteAnswer =
        when (route) {
            SecretRequestRoute.LIST -> list(human)
            SecretRequestRoute.GET -> get(human, input.requestId())
            SecretRequestRoute.SUBMIT -> submit(human, input)
            else -> cancel(human, input.requestId())
        }

    private suspend fun status(agent: RequestingAgent, id: SecretRequestId): SecretRouteAnswer {
        if (!id.isWellFormed) return SecretRouteAnswer.refused(SecretServiceError.UNKNOWN_REQUEST)
        return service.receipt(agent, id).answer(::receiptResult)
    }

    private suspend fun list(human: HumanCaller): SecretRouteAnswer = service.list(human.principal).answer { all ->
        wireOf(SecretRequestList.serializer(), SecretRequestList(all.requests.filter(human::sees)))
    }

    private suspend fun get(human: HumanCaller, id: SecretRequestId): SecretRouteAnswer {
        val visible = visibleRecord(human, id, SecretServiceError.UNKNOWN_REQUEST)
        return visible.record?.let { SecretRouteAnswer.Ok(wireOf(SecretRequestRecord.serializer(), it)) } ?: visible.denial()
    }

    /**
     * The value becomes a [com.letta.mobile.plugin.keystore.SecretValue] before anything else is
     * looked at, and is wiped in `finally` whatever happens next; the service wipes it too.
     */
    private suspend fun submit(human: HumanCaller, input: SecretRouteInput): SecretRouteAnswer {
        val value = decode(input[SecretRequestRouteWire.VALUE])
            ?: return SecretRouteAnswer.refused(SecretServiceError.INVALID_VALUE)
        return try {
            val id = input.requestId()
            val visible = visibleRecord(human, id, SecretServiceError.NOT_PENDING)
            if (visible.record == null) visible.denial() else service.submit(human.principal, id, value).answer(::receiptResult)
        } finally {
            value.wipe()
        }
    }

    private suspend fun cancel(human: HumanCaller, id: SecretRequestId): SecretRouteAnswer {
        val visible = visibleRecord(human, id, SecretServiceError.NOT_PENDING)
        if (visible.record == null) return visible.denial()
        return service.cancel(human.principal, id).answer(::receiptResult)
    }

    /**
     * The record [id] names when it exists and [human] may see it. A malformed id, an unknown one
     * and another device's agent's request all answer [denial]; only a journal failure differs.
     */
    private suspend fun visibleRecord(human: HumanCaller, id: SecretRequestId, denial: SecretServiceError): Visible {
        if (!id.isWellFormed) return Visible(null, denial)
        return when (val found = service.get(human.principal, id)) {
            is SecretServiceResult.Ok -> Visible(found.value.takeIf(human::sees), denial)
            is SecretServiceResult.Refused -> Visible(null, journalOr(found.error, denial))
        }
    }

    private class Visible(val record: SecretRequestRecord?, private val error: SecretServiceError) {
        fun denial(): SecretRouteAnswer = SecretRouteAnswer.refused(error)
    }
}

private fun journalOr(error: SecretServiceError, denial: SecretServiceError): SecretServiceError =
    if (error == SecretServiceError.JOURNAL_UNAVAILABLE) error else denial

private fun agentOf(caller: SecretRequestCaller): RequestingAgent? = (caller as? SecretRequestCaller.Agent)?.agent

private fun humanOf(caller: SecretRequestCaller): HumanCaller? =
    (caller as? SecretRequestCaller.Connection)?.let { HumanCaller.of(it.context) }

private inline fun withInput(
    route: SecretRequestRoute,
    params: JsonObject?,
    block: (SecretRouteInput) -> SecretRouteAnswer,
): SecretRouteAnswer = route.parse(params)?.let(block) ?: SecretRouteAnswer.refused(SecretServiceError.INVALID_PARAMS)

private fun receiptResult(receipt: SecretRequestReceipt?): JsonElement =
    wireOf(SecretRequestReceiptResult.serializer(), SecretRequestReceiptResult(receipt))

private fun SecretRouteInput.requestId(): SecretRequestId = SecretRequestId(this[SecretRequestRouteWire.REQUEST_ID])

private fun SecretRouteInput.agentRequest(): AgentSecretRequest = AgentSecretRequest(
    purpose = SecretPurpose(this[SecretRequestRouteWire.PURPOSE]),
    vaultItemName = VaultItemName(this[SecretRequestRouteWire.VAULT_ITEM_NAME]),
    fieldName = VaultFieldName(this[SecretRequestRouteWire.FIELD_NAME]),
)
