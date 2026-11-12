package com.letta.mobile.data.controller.node.iroh.secrets

import com.letta.mobile.data.controller.extras.identity.CallerIdentity
import com.letta.mobile.data.controller.extras.ExternalToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Owner decision (2026-11-11): `secret_request` serves only a caller whose agent identity the host
 * or runtime verified. A claimed identity (Meridian env-scoped binding, the legacy bare-id entry)
 * gets the same `forbidden` as every other refusal, decided before any argument is read.
 */
class SecretRequestToolIdentityTest {
    private val forbidden = ExternalToolResult.Error("forbidden")

    private suspend fun journalSize(harness: SecretRouteHarness): Int =
        checkNotNull(resultOf(harness.dispatch(SecretRequestRoute.LIST, null, HUMAN))).getValue("requests").jsonArray.size

    /** One identity case: who claims to call, how, and whether the tool should serve them. */
    private class IdentityCase(val name: String, val identity: CallerIdentity, val served: Boolean)

    private val cases = listOf(
        IdentityCase("verified", CallerIdentity.VERIFIED, served = true),
        IdentityCase("claimed", CallerIdentity.CLAIMED, served = false),
    )

    @Test
    fun onlyAVerifiedIdentityIsServedForBothActions() = runTest {
        for (case in cases) {
            val harness = SecretRouteHarness()
            val created = harness.tool(createInput(), REQUESTER, case.identity)
            val id = harness.create()
            val polled = harness.tool(statusInput(id), REQUESTER, case.identity)
            if (case.served) {
                assertIs<ExternalToolResult.Success>(created, case.name)
                assertEquals(ExternalToolResult.Success("{}"), polled, case.name)
            } else {
                assertEquals(forbidden, created, case.name)
                assertEquals(forbidden, polled, case.name)
            }
            assertEquals(if (case.served) 2 else 1, journalSize(harness), "${case.name} created only what it was allowed to")
        }
    }

    @Test
    fun aClaimedCallerCannotPollAVerifiedCallersRequestEvenUnderTheSameAgentId() = runTest {
        val harness = SecretRouteHarness()
        val id = harness.create(REQUESTER)
        harness.dispatch(SecretRequestRoute.SUBMIT, submitParams(id, TestSecret("identity-value")), HUMAN)
        assertEquals(forbidden, harness.tool(statusInput(id), REQUESTER, CallerIdentity.CLAIMED))
        assertIs<ExternalToolResult.Success>(harness.status(id))
    }

    @Test
    fun theProvenanceCheckRunsBeforeAnyArgumentIsRead() = runTest {
        val harness = SecretRouteHarness()
        val unreadable = listOf(JsonObject(emptyMap()), JsonObject(mapOf("action" to JsonPrimitive("submit"))), createInput("‮"))
        for (input in unreadable) {
            assertEquals(forbidden, harness.tool(input, REQUESTER, CallerIdentity.CLAIMED), input.toString())
        }
        assertEquals(forbidden, harness.tool(createInput(), null, CallerIdentity.VERIFIED), "no agent at all")
        assertEquals(0, journalSize(harness))
    }

    @Test
    fun argumentsCannotChangeTheProvenance() = runTest {
        val harness = SecretRouteHarness()
        for (key in listOf("identity", "callerIdentity", "verified", "agentId")) {
            for (value in listOf(JsonPrimitive("VERIFIED"), JsonPrimitive(true), JsonPrimitive(REQUESTER.agentId.value))) {
                val input = JsonObject(createInput() + (key to value))
                assertEquals(forbidden, harness.tool(input, REQUESTER, CallerIdentity.CLAIMED), "$key=$value")
            }
        }
        assertEquals(0, journalSize(harness))
    }

    @Test
    fun theLegacyBareIdEntryIsClaimedAndRefused() = runTest {
        val harness = SecretRouteHarness()
        assertEquals(forbidden, harness.tool.invoke(createInput(), REQUESTER.agentId.value))
        assertEquals(0, journalSize(harness))
    }

    @Test
    fun aClaimedRefusalIsByteIdenticalToEveryOtherForbidden() = runTest {
        val harness = SecretRouteHarness()
        val claimed = harness.tool(createInput(), REQUESTER, CallerIdentity.CLAIMED) as ExternalToolResult.Error
        val noAgent = harness.tool(createInput(), null) as ExternalToolResult.Error
        assertEquals(claimed, noAgent)
        assertTrue(harness.dispatch(SecretRequestRoute.LIST, null, ADMIN_TOKEN).endsWith(""""error":"${claimed.error}"}"""))
    }
}
