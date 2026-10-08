package com.letta.mobile.data.secrets

import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerSecretCommand
import com.letta.mobile.data.transport.appserver.DefaultAppServerClient
import com.letta.mobile.data.transport.appserver.ScriptedAppServerTransport
import com.letta.mobile.data.transport.appserver.requestId
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AppServerAgentSecretsSourceTest {
    private val openAiKey = "sk-live-0penai-VALUE-123"
    private val githubToken = "ghp_GITHUB-VALUE-456"

    private fun TestScope.source(respond: (JsonObject) -> List<String>): Pair<AppServerAgentSecretsSource, ScriptedAppServerTransport> {
        val transport = ScriptedAppServerTransport(respond)
        val client = DefaultAppServerClient(transport, parentScope = backgroundScope)
        return AppServerAgentSecretsSource(client = { client }, requestId = { "$it-1" }) to transport
    }

    private fun listResponse(command: JsonObject) =
        """{"type":"secret_list_response","request_id":"${command.requestId}","success":true,"secrets":[{"key":"OPENAI_API_KEY","value":"$openAiKey"},{"key":"GITHUB_TOKEN","value":"$githubToken"}]}"""

    @Test
    fun listingReturnsKeysWithMaskedValuesSortedByKey() = runTest {
        val (source, transport) = source { listOf(listResponse(it)) }
        val secrets = source.list("agent-1")

        assertEquals(listOf("GITHUB_TOKEN", "OPENAI_API_KEY"), secrets.map { it.key })
        assertEquals(openAiKey, secrets.last().value.reveal())
        assertFalse(secrets.toString().contains(openAiKey), "a listing must not print its values")
        assertEquals("secret_list", (transport.sent.single()["type"] as JsonPrimitive).content)
    }

    @Test
    fun applySendsOneBatchWithNormalisedKeys() = runTest {
        val (source, transport) = source { command ->
            listOf("""{"type":"secret_apply_response","request_id":"${command.requestId}","success":true,"names":["NEW_KEY"]}""")
        }
        val names = source.apply("agent-1", AgentSecretChanges(set = mapOf(" new_key " to SecretValue("v1")), unset = setOf("old_key")))

        assertEquals(listOf("NEW_KEY"), names)
        val sent = transport.sent.single()
        assertEquals("secret_apply", (sent["type"] as JsonPrimitive).content)
        assertEquals(JsonObject(mapOf("NEW_KEY" to JsonPrimitive("v1"))), sent["set"])
        assertEquals(JsonArray(listOf(JsonPrimitive("OLD_KEY"))), sent["unset"])
    }

    @Test
    fun aRefusedApplyCarriesTheServerError() = runTest {
        val (source, _) = source { command ->
            listOf("""{"type":"secret_apply_response","request_id":"${command.requestId}","success":false,"names":[],"error":"core rejected the update"}""")
        }
        val error = assertFailsWith<AgentSecretsException> {
            source.apply("agent-1", AgentSecretChanges(unset = setOf("X")))
        }
        assertEquals("core rejected the update", error.message)
    }

    @Test
    fun anUnreadableListingFailsWithoutQuotingTheFrame() = runTest {
        val (source, _) = source { command ->
            listOf("""{"type":"secret_list_response","request_id":"${command.requestId}","success":true,"secrets":"$openAiKey"}""")
        }
        val error = assertFailsWith<AgentSecretsException> { source.list("agent-1") }
        assertFalse(error.stackTraceToString().contains(openAiKey), "the decode error must not carry the frame")
    }

    @Test
    fun valuesNeverReachTelemetryCommandTextOrState() = runTest {
        val (source, transport) = source { command ->
            when ((command["type"] as JsonPrimitive).content) {
                "secret_list" -> listOf(listResponse(command))
                else -> listOf("""{"type":"secret_apply_response","request_id":"${command.requestId}","success":true,"names":["OPENAI_API_KEY"]}""")
            }
        }
        val before = Telemetry.events.value.size
        val controller = AgentVaultController(source, backgroundScope)
        controller.selectAgent("agent-1")
        testScheduler.runCurrent()
        controller.toggleReveal(SecretKey("OPENAI_API_KEY"))
        controller.startAdding()
        controller.updateDraftKey("NEW_KEY")
        controller.updateDraftValue(githubToken)
        controller.saveDraft()
        testScheduler.runCurrent()

        val telemetry = Telemetry.events.value.drop(before).joinToString("\n")
        listOf(openAiKey, githubToken).forEach { value ->
            assertFalse(telemetry.contains(value), "telemetry must not carry a secret value")
            assertFalse(controller.state.value.toString().contains(value), "state dumps must not carry a secret value")
        }
        val apply = AppServerSecretCommand.SecretApply("r", "agent-1", set = mapOf("K" to githubToken))
        assertFalse(apply.toString().contains(githubToken), "the command's toString must not carry the value")
        assertTrue(transport.sentText.any { it.contains(githubToken) }, "the value does go to the server")
    }

    @Test
    fun redactionBlanksValuesInListingsAndApplies() {
        val listing = AppServerProtocol.json.parseToJsonElement(
            """{"type":"secret_list_response","request_id":"r","success":true,"secrets":[{"key":"A","value":"$openAiKey"}]}""",
        ).jsonObject
        val apply = AppServerProtocol.json.parseToJsonElement(
            """{"type":"secret_apply","request_id":"r","agent_id":"a","set":{"A":"$githubToken"},"unset":["B"]}""",
        ).jsonObject
        val other = AppServerProtocol.json.parseToJsonElement("""{"type":"sync","value":"keep"}""").jsonObject

        val redactedListing = AgentSecretsRedaction.redact(listing).toString()
        val redactedApply = AgentSecretsRedaction.redact(apply).toString()
        assertFalse(redactedListing.contains(openAiKey))
        assertTrue(redactedListing.contains("\"key\":\"A\""), "key names stay for diagnosis")
        assertFalse(redactedApply.contains(githubToken))
        assertTrue(redactedApply.contains("\"unset\":[\"B\"]"))
        assertEquals(other, AgentSecretsRedaction.redact(other))
    }

    @Test
    fun secretResponsesDecodeAsUnknownFrames() {
        listOf("secret_list_response", "secret_apply_response").forEach { type ->
            assertIs<AppServerInboundFrame.Unknown>(AppServerProtocol.decodeFrame("""{"type":"$type","request_id":"r"}""").frame, type)
        }
    }
}
