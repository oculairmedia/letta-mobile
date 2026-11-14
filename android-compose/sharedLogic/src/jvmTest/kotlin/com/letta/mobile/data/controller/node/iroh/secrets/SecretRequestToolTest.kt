package com.letta.mobile.data.controller.node.iroh.secrets

import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.secrets.request.SecretRequestLimits
import com.letta.mobile.data.secrets.request.SecretRequestTestData
import com.letta.mobile.data.secrets.wire.SecretRequestRouteWire
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The `secret_request` tool: who the agent is, what it may send, and exactly what it gets back. */
class SecretRequestToolTest {
    private suspend fun requests(harness: SecretRouteHarness): List<JsonObject> {
        val listed = checkNotNull(resultOf(harness.dispatch(SecretRequestRoute.LIST, null, HUMAN)))
        return listed.getValue("requests").jsonArray.map { it.jsonObject }
    }

    @Test
    fun theRequestingAgentIsTheOneTheRuntimeStampedNeverAnArgument() = runTest {
        val harness = SecretRouteHarness()
        val spoofed = JsonObject(createInput() + ("agentId" to JsonPrimitive(BYSTANDER.agentId.value)))
        assertEquals(ExternalToolResult.Error("invalid_params"), harness.tool(spoofed, REQUESTER))
        val requestingAgent = JsonObject(createInput() + ("requestingAgent" to JsonPrimitive(BYSTANDER.agentId.value)))
        assertEquals(ExternalToolResult.Error("invalid_params"), harness.tool(requestingAgent, REQUESTER))

        val id = harness.create(REQUESTER)
        val details = requests(harness).single().getValue("details").jsonObject
        assertEquals(REQUESTER.agentId.value, details.getValue("requestingAgent").jsonPrimitive.content)
        assertEquals(ExternalToolResult.Error("unknown_request"), harness.status(id, BYSTANDER))
    }

    @Test
    fun aCallWithNoStampedAgentIsForbidden() = runTest {
        val harness = SecretRouteHarness()
        assertEquals(ExternalToolResult.Error("forbidden"), harness.tool(createInput(), null))
        assertTrue(requests(harness).isEmpty())
    }

    @Test
    fun createAnswersExactlyTheIdThePendingStatusAndTheExpiry() = runTest {
        val harness = SecretRouteHarness()
        val created = Json.parseToJsonElement((harness.tool(createInput(), REQUESTER) as ExternalToolResult.Success).content).jsonObject
        assertEquals(setOf("requestId", "status", "expiresAtMs"), created.keys)
        assertEquals("pending", created.getValue("status").jsonPrimitive.content)
        val expected = harness.clock.nowMs + SecretRequestLimits.DEFAULT_TTL_MS
        assertEquals(expected, created.getValue("expiresAtMs").jsonPrimitive.content.toLong())
    }

    @Test
    fun statusIsEmptyWhileOpenThenOnlyTheReceipt() = runTest {
        val harness = SecretRouteHarness()
        val id = harness.create()
        assertEquals(ExternalToolResult.Success("{}"), harness.status(id))
        harness.dispatch(SecretRequestRoute.SUBMIT, submitParams(id, TestSecret("tool-shape-value")), HUMAN)
        val receipt = Json.parseToJsonElement((harness.status(id) as ExternalToolResult.Success).content).jsonObject
        assertEquals(setOf("receipt"), receipt.keys)
        assertEquals(setOf("status", "itemId", "fieldName"), receipt.getValue("receipt").jsonObject.keys)
    }

    @Test
    fun onlyCreateAndStatusAreActions() = runTest {
        val harness = SecretRouteHarness()
        val id = harness.create()
        for (action in listOf("submit", "list", "get", "cancel", "", "CREATE")) {
            val input = buildJsonObject {
                put("action", action)
                put(SecretRequestRouteWire.REQUEST_ID, id.value)
            }
            assertEquals(ExternalToolResult.Error("invalid_params"), harness.tool(input, REQUESTER), action)
        }
        assertEquals(ExternalToolResult.Error("invalid_params"), harness.tool(JsonObject(createInput() - "action"), REQUESTER))
        assertEquals(0, harness.provider.writes)
    }

    @Test
    fun aPurposeWithMarkupLinksControlOrBidiCharactersIsRefusedAndNothingIsStored() = runTest {
        val harness = SecretRouteHarness()
        val notPlain = listOf(
            "see [the docs](x)",
            "`code` span",
            "visit https://example.test",
            "ring the bell \u0007",
            "right-to-left \u202e override",
            "zero\u200bwidth",
            "line\u2028separator",
            "# heading",
        )
        for (purpose in notPlain) {
            val answer = harness.tool(createInput(purpose), REQUESTER)
            assertEquals(ExternalToolResult.Error("invalid_request:purpose_not_plain_text"), answer, purpose)
        }
        val tooLong = "a".repeat(SecretRequestLimits.MAX_PURPOSE_LENGTH + 1)
        assertEquals(ExternalToolResult.Error("invalid_request:purpose_too_long"), harness.tool(createInput(tooLong), REQUESTER))
        assertEquals(ExternalToolResult.Error("invalid_request:purpose_blank"), harness.tool(createInput(" \t "), REQUESTER))
        assertTrue(requests(harness).isEmpty())
    }

    @Test
    fun aPlainPurposeIsStoredTrimmedAndListedAsAgentAuthoredText() = runTest {
        val harness = SecretRouteHarness()
        val purpose = "a".repeat(SecretRequestLimits.MAX_PURPOSE_LENGTH)
        harness.tool(createInput("  $purpose  "), REQUESTER)
        val listed = requests(harness).single().getValue("details").jsonObject.getValue("purpose").jsonObject
        assertEquals(buildJsonObject {
            put("text", purpose)
            put("author", "agent")
        }, listed)
    }

    @Test
    fun emphasisMarksAreNotMarkupTheyAreStoredAndShownLiterally() = runTest {
        val harness = SecretRouteHarness()
        harness.tool(createInput("**bold** reason"), REQUESTER)
        val listed = requests(harness).single().getValue("details").jsonObject.getValue("purpose").jsonObject
        assertEquals("**bold** reason", listed.getValue("text").jsonPrimitive.content)
    }

    @Test
    fun theDescriptionTellsTheAgentItNeverSeesTheValueAndMustPoll() {
        val description = SecretRequestTool.DESCRIPTION
        assertTrue("never see the value" in description)
        assertTrue("Poll status" in description)
        assertEquals(SecretRequestTool.NAME, SecretRouteHarness().tool.name)
        val schema = checkNotNull(SecretRouteHarness().tool.inputSchema)
        assertEquals(JsonPrimitive(false), schema["additionalProperties"])
        assertTrue(SecretRequestTestData.AGENT.value !in schema.toString(), "the schema names no agent")
    }
}
