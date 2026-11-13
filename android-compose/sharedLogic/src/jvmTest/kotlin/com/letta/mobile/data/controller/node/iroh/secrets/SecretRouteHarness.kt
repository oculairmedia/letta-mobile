package com.letta.mobile.data.controller.node.iroh.secrets

import com.letta.mobile.data.controller.extras.identity.CallerIdentity
import com.letta.mobile.data.controller.extras.ExternalToolCaller
import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.controller.extras.HostExternalTool
import com.letta.mobile.data.controller.node.iroh.AdminRpcInvocation
import com.letta.mobile.data.controller.node.iroh.AdminRpcRequestContext
import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.controller.node.iroh.IrohPeerCapabilities
import com.letta.mobile.data.controller.node.iroh.auth.IrohAuthPolicy
import com.letta.mobile.data.secrets.host.CountingRandom
import com.letta.mobile.data.secrets.host.SecretRequestService
import com.letta.mobile.data.secrets.host.SecretRequestSettings
import com.letta.mobile.data.secrets.host.TEAM_SCOPE
import com.letta.mobile.data.secrets.host.TestClock
import com.letta.mobile.data.secrets.host.TestKeystoreProvider
import com.letta.mobile.data.secrets.journal.InMemorySecretRequestJournal
import com.letta.mobile.data.secrets.journal.SecretRequestJournal
import com.letta.mobile.data.secrets.request.RequestingAgent
import com.letta.mobile.data.secrets.request.SecretRequestId
import com.letta.mobile.data.secrets.request.SecretRequestTestData
import com.letta.mobile.data.secrets.wire.SecretRequestRouteWire
import com.letta.mobile.plugin.keystore.SecretValue
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The endpoint id of the paired human device in these tests. */
internal val HUMAN_ENDPOINT: String = "ab".repeat(32)

/** A paired device holding admin.full and seeing every agent: the human principal. */
internal val HUMAN = AdminRpcRequestContext(
    authenticated = true,
    authenticatedEndpointId = HUMAN_ENDPOINT,
    pairedDevice = true,
    grantedCapabilities = setOf(IrohPeerCapabilities.ADMIN_FULL),
)

/** A host-local process holding the admin token: a bootstrap session with admin.full, not a paired device. */
internal val ADMIN_TOKEN = HUMAN.copy(authenticatedEndpointId = "cd".repeat(32), pairedDevice = false)

/** A paired device with admin.full that may see only the bystander agent. */
internal val WRONG_AGENT_HUMAN = HUMAN.copy(authorizedAgentIds = setOf(SecretRequestTestData.OTHER_AGENT.value))

/** A paired device holding the answer capability but not admin.full. */
internal val LIMITED_HUMAN = HUMAN.copy(
    grantedCapabilities = IrohPeerCapabilities.DEFAULT_DESKTOP_ROLE + IrohPeerCapabilities.SECRET_REQUEST_ANSWER,
)

/** No authentication at all. */
internal val UNAUTHENTICATED: AdminRpcRequestContext = AdminRpcRequestContext.Unauthenticated

/** The requesting agent and a bystander, as the runtime stamps them on a tool call. */
internal val REQUESTER = RequestingAgent(SecretRequestTestData.AGENT)
internal val BYSTANDER = RequestingAgent(SecretRequestTestData.OTHER_AGENT)

/** A well-formed request id no request has. */
internal val UNKNOWN_ID = SecretRequestId(SecretRequestId.PREFIX + "Z".repeat(43))

/** Pieces of a harness a test may replace; [decoded] records every value a submission decoded. */
internal class SecretRouteParts(
    val journal: SecretRequestJournal = InMemorySecretRequestJournal(),
    val provider: TestKeystoreProvider = TestKeystoreProvider(),
    val clock: TestClock = TestClock(),
) {
    val decoded: MutableList<SecretValue> = java.util.Collections.synchronizedList(mutableListOf())

    /** The production decoder, recording what it made. */
    fun recordingDecoder(): (String) -> SecretValue? = { text -> SecretSubmission.decode(text)?.also(decoded::add) }
}

/**
 * The real feature under a secure policy over a test keystore: a router with the routes
 * registered, the tool, and the clock. Calls go through [AdminRpcRouter.dispatch] or the tool.
 */
internal class SecretRouteHarness(val parts: SecretRouteParts = SecretRouteParts()) {
    private val service = SecretRequestService(
        parts.journal,
        parts.provider,
        SecretRequestSettings(TEAM_SCOPE, parts.clock, CountingRandom()),
    )
    val feature = SecretRequestFeature(IrohAuthPolicy.PairedPeersOnly, SecretRequestHandlers(service, parts.recordingDecoder()))
    val router = AdminRpcRouter().also(feature.routes::register)
    val tool: HostExternalTool = feature.hostTools.single()
    val provider: TestKeystoreProvider get() = parts.provider
    val clock: TestClock get() = parts.clock

    /** Dispatches [route] through the real router as the connection [context] describes. */
    suspend fun dispatch(route: SecretRequestRoute, params: JsonObject?, context: AdminRpcRequestContext): String =
        router.dispatch(AdminRpcInvocation(REQUEST_ID, route.method, params, context))

    /** Calls the tool as [agent] (null: no agent stamped) with the given provenance of that identity. */
    suspend fun tool(input: JsonObject, agent: RequestingAgent?, identity: CallerIdentity = CallerIdentity.VERIFIED): ExternalToolResult =
        tool.invoke(input, callerOf(identity, agent?.agentId?.value))

    /** Creates a pending request through the tool as [agent] and answers its id. */
    suspend fun create(agent: RequestingAgent = REQUESTER): SecretRequestId {
        val result = tool(createInput(), agent) as ExternalToolResult.Success
        return SecretRequestId(Json.parseToJsonElement(result.content).jsonObject.getValue("requestId").jsonPrimitive.content)
    }

    /** Polls the receipt of [id] through the tool as [agent]. */
    suspend fun status(id: SecretRequestId, agent: RequestingAgent = REQUESTER): ExternalToolResult =
        tool(statusInput(id), agent)

    companion object {
        const val REQUEST_ID = "t"
        private const val CONVERSATION = "conv-secret-test"

        private fun callerOf(identity: CallerIdentity, agentId: String?): ExternalToolCaller = when (identity) {
            CallerIdentity.VERIFIED -> ExternalToolCaller.verified(agentId, CONVERSATION)
            CallerIdentity.CLAIMED -> ExternalToolCaller.claimed(agentId, CONVERSATION)
        }
    }
}

/** Tool input for `create` with the test target and [purpose]. */
internal fun createInput(purpose: String = SecretRequestTestData.request().purpose.value): JsonObject = buildJsonObject {
    put("action", "create")
    put(SecretRequestRouteWire.PURPOSE, purpose)
    put(SecretRequestRouteWire.VAULT_ITEM_NAME, SecretRequestTestData.TARGET.item.value)
    put(SecretRequestRouteWire.FIELD_NAME, SecretRequestTestData.TARGET.field.value)
}

/** Tool input for `status` of [id]. */
internal fun statusInput(id: SecretRequestId): JsonObject = buildJsonObject {
    put("action", "status")
    put(SecretRequestRouteWire.REQUEST_ID, id.value)
}

/** The params naming [id]. */
internal fun idParams(id: SecretRequestId): JsonObject = buildJsonObject { put(SecretRequestRouteWire.REQUEST_ID, id.value) }

/** Submit params carrying [value]'s UTF-8 bytes as base64. */
internal fun submitParams(id: SecretRequestId, value: TestSecret): JsonObject = buildJsonObject {
    put(SecretRequestRouteWire.REQUEST_ID, id.value)
    put(SecretRequestRouteWire.VALUE, value.base64())
}

/** One test secret; typed so helpers take a secret, not any string. */
internal class TestSecret(val text: String) {
    fun bytes(): ByteArray = text.encodeToByteArray()

    fun base64(): String = Base64.getEncoder().encodeToString(bytes())
}

/** The failure envelope the router writes for [code]. */
internal fun refusalEnvelope(code: String): String =
    """{"type":"admin_rpc_response","request_id":"${SecretRouteHarness.REQUEST_ID}","success":false,"error":"$code"}"""

/** The `result` of a success envelope, or null for a failure. */
internal fun resultOf(response: String): JsonObject? {
    val envelope = Json.parseToJsonElement(response).jsonObject
    return envelope["result"]?.jsonObject?.takeIf { envelope["success"] == JsonPrimitive(true) }
}
