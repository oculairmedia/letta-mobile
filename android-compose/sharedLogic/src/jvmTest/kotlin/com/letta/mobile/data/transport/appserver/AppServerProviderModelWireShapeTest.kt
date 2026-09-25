package com.letta.mobile.data.transport.appserver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * letta-mobile-w4q4p: hermetic contract probes for list_connect_providers,
 * connect_provider, disconnect_provider and update_model against frames
 * RECORDED from the live App Server 0.32.17 (`ws://127.0.0.1:4500/ws`):
 * the full provider listing (trimmed to five rows) and the three rejection
 * frames produced by an unknown provider / unknown agent. Only read-only or
 * rejected commands were sent to record them.
 */
class AppServerProviderModelWireShapeTest {
    @Test
    fun recordedProviderListingDecodesTyped() {
        val frame = assertIs<AppServerInboundFrame.ListConnectProvidersResponse>(decode(resource("probe-list-connect-providers-response.json")))

        assertTrue(frame.success)
        assertEquals("local", frame.target)
        assertEquals(listOf("amazon-bedrock", "anthropic", "anthropic-oauth", "lmstudio", "openai-compatible"), frame.providers.map { it.id })
        val lmstudio = frame.providers.first { it.id == "lmstudio" }
        assertEquals("lmstudio_openai", lmstudio.providerType)
        assertTrue(lmstudio.connected.isConnected)
        assertEquals("lc-lmstudio", lmstudio.connectedProviders.single().providerName)
        assertEquals(listOf(false, false), lmstudio.fields!!.map { it.required })
        assertEquals(true, lmstudio.fields!!.first { it.key == "apiKey" }.secret)
        val oauth = frame.providers.first { it.id == "anthropic-oauth" }
        assertEquals(true, oauth.isOauth)
        assertEquals(null, oauth.fields)
        val bedrock = frame.providers.first { it.id == "amazon-bedrock" }
        assertEquals(listOf("accessKey", "apiKey", "region"), bedrock.authMethods!!.first().fields.map { it.key })
    }

    @Test
    fun recordedRejectionFramesDecodeTyped() {
        val frames = resource("probe-provider-model-error-responses.jsonl").lineSequence().filter { it.isNotBlank() }.map(::decode).toList()

        val connect = assertIs<AppServerInboundFrame.ConnectProviderResponse>(frames[0])
        val disconnect = assertIs<AppServerInboundFrame.DisconnectProviderResponse>(frames[1])
        val update = assertIs<AppServerInboundFrame.UpdateModelResponse>(frames[2])
        assertEquals("Unknown provider: no-such-provider", connect.error)
        assertFalse(disconnect.modelsMayHaveChanged)
        assertEquals("conv-does-not-exist", update.scope?.conversationId)
        assertEquals(null, update.runtime, "update_model_response is a correlated answer, not a turn event")
    }

    @Test
    fun commandsEncodeToTheExactShapesTheLiveServerAccepted() {
        assertEquals(
            """{"type":"list_connect_providers","request_id":"p1","target":"local"}""",
            encode(AppServerCommand.ListConnectProviders("p1", APP_SERVER_PROVIDER_TARGET_LOCAL)),
        )
        assertEquals(
            """{"type":"connect_provider","request_id":"c1","target":"local","provider_id":"no-such-provider","fields":{}}""",
            encode(AppServerCommand.ConnectProvider("c1", APP_SERVER_PROVIDER_TARGET_LOCAL, "no-such-provider", fields = emptyMap())),
        )
        assertEquals(
            """{"type":"disconnect_provider","request_id":"d1","target":"local","provider_id":"no-such-provider"}""",
            encode(AppServerCommand.DisconnectProvider("d1", APP_SERVER_PROVIDER_TARGET_LOCAL, "no-such-provider")),
        )
        assertEquals(
            """{"type":"update_model","request_id":"u1","runtime":{"agent_id":"agent-does-not-exist",""" +
                """"conversation_id":"conv-does-not-exist"},"payload":{"model_handle":"lmstudio/none"}}""",
            encode(updateModel(AppServerUpdateModelPayload(modelHandle = "lmstudio/none"))),
        )
    }

    @Test
    fun updateModelKeepsAnExplicitNullEffortAndValidatesNamedOnes() {
        val restore = encode(updateModel(AppServerUpdateModelPayload(reasoningEffort = AppServerUpdateModelPayload.RESTORE_DEFAULT_REASONING_EFFORT)))
        val high = encode(updateModel(AppServerUpdateModelPayload(reasoningEffort = AppServerUpdateModelPayload.reasoningEffortOf("high"))))

        assertTrue(restore.endsWith(""""payload":{"reasoning_effort":null}}"""), restore)
        assertTrue(high.endsWith(""""payload":{"reasoning_effort":"high"}}"""), high)
        assertTrue(runCatching { AppServerUpdateModelPayload.reasoningEffortOf("turbo") }.isFailure)
    }

    @Test
    fun connectProviderNeverPrintsCredentialValuesAndLogsRedactThem() {
        val command = AppServerCommand.ConnectProvider(
            "c1",
            APP_SERVER_PROVIDER_TARGET_LOCAL,
            "openai",
            fields = mapOf("apiKey" to "fixture-secret-value"),
        )
        val redacted = AppServerProtocol.redactCredentials(AppServerProtocol.json.parseToJsonElement(encode(command)))

        assertFalse(command.toString().contains("fixture-secret-value"))
        assertFalse(redacted.toString().contains("fixture-secret-value"))
    }

    @Test
    fun onlyTheProviderListingReplaysAfterAnAmbiguousDisconnect() {
        val scope = AppServerConversationRuntimeScope("a", "c")
        assertTrue(AppServerCommandRetryClass.isRetryableAfterAmbiguousDisconnect(AppServerCommand.ListConnectProviders("p", "local")))
        listOf(
            AppServerCommand.ConnectProvider("c", "local", "openai", fields = emptyMap()),
            AppServerCommand.DisconnectProvider("d", "local", "openai"),
            AppServerCommand.UpdateModel("u", scope, AppServerUpdateModelPayload(modelHandle = "x/y")),
        ).forEach { assertFalse(AppServerCommandRetryClass.isRetryableAfterAmbiguousDisconnect(it), "$it") }
    }

    private fun updateModel(payload: AppServerUpdateModelPayload) = AppServerCommand.UpdateModel(
        requestId = "u1",
        runtime = AppServerConversationRuntimeScope("agent-does-not-exist", "conv-does-not-exist"),
        payload = payload,
    )

    private fun encode(command: AppServerCommand): String = AppServerProtocol.encodeCommand(command)

    private fun decode(raw: String): AppServerInboundFrame = AppServerProtocol.decodeFrame(raw).frame

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/appserver/$name")) { "missing $name" }.bufferedReader().use { it.readText() }
}
