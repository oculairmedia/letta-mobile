package com.letta.mobile.data.controller.node.iroh.secrets

import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.controller.node.iroh.AdminRpcRequestContext
import com.letta.mobile.data.secrets.request.RequestingAgent
import com.letta.mobile.data.secrets.request.SecretRequestId
import com.letta.mobile.data.secrets.request.SecretRequestState
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every principal against every route, through the real router (connections) or the real tool and
 * handler (agents): who may do what, and that every denial is the same bytes whether or not the
 * request exists.
 */
class SecretRequestPrincipalMatrixTest {
    /** Who calls, and through which door the transport would let them in. */
    private enum class Who(val context: AdminRpcRequestContext?, val agent: RequestingAgent?) {
        AGENT(null, REQUESTER),
        OTHER_AGENT(null, BYSTANDER),
        PAIRED_HUMAN(HUMAN, null),
        ADMIN_TOKEN_PROCESS(ADMIN_TOKEN, null),
        UNAUTHENTICATED_PEER(UNAUTHENTICATED, null),
        WRONG_AGENT_HUMAN(com.letta.mobile.data.controller.node.iroh.secrets.WRONG_AGENT_HUMAN, null),
        HUMAN_WITHOUT_ADMIN(LIMITED_HUMAN, null),
    }

    private val forbidden = "forbidden"

    /** The expected outcome ("ok" or the refusal text) of every route for [who]. */
    private fun expected(route: SecretRequestRoute, who: Who): String = when {
        who.agent != null -> agentExpectation(route, who)
        who == Who.PAIRED_HUMAN -> humanExpectation(route)
        who == Who.WRONG_AGENT_HUMAN -> wrongAgentExpectation(route)
        else -> forbidden
    }

    private fun agentExpectation(route: SecretRequestRoute, who: Who): String = when (route) {
        SecretRequestRoute.REQUEST -> "ok"
        SecretRequestRoute.STATUS -> if (who == Who.AGENT) "ok" else "unknown_request"
        else -> forbidden
    }

    private fun humanExpectation(route: SecretRequestRoute): String =
        if (route.side == SecretRouteSide.HUMAN) "ok" else forbidden

    private fun wrongAgentExpectation(route: SecretRequestRoute): String = when (route) {
        SecretRequestRoute.LIST -> "ok"
        SecretRequestRoute.GET -> "unknown_request"
        SecretRequestRoute.SUBMIT, SecretRequestRoute.CANCEL -> "not_pending"
        else -> forbidden
    }

    private fun params(route: SecretRequestRoute, id: SecretRequestId): JsonObject? = when (route) {
        SecretRequestRoute.REQUEST -> JsonObject(createInput() - "action")
        SecretRequestRoute.LIST -> null
        SecretRequestRoute.SUBMIT -> submitParams(id, TestSecret("matrix-value"))
        else -> idParams(id)
    }

    /** The outcome of [route] for [who] on request [id]: "ok" or the refusal text. */
    private suspend fun outcome(harness: SecretRouteHarness, route: SecretRequestRoute, who: Who, id: SecretRequestId): String {
        val context = who.context ?: return agentOutcome(harness, route, checkNotNull(who.agent), id)
        val response = harness.dispatch(route, params(route, id), context)
        return if (resultOf(response) != null) "ok" else errorOf(response)
    }

    private suspend fun agentOutcome(
        harness: SecretRouteHarness,
        route: SecretRequestRoute,
        agent: RequestingAgent,
        id: SecretRequestId,
    ): String {
        val answer = harness.feature.handlers.handle(route, params(route, id), SecretRequestCaller.Agent(agent), "test")
        return (answer as? SecretRouteAnswer.Refused)?.text ?: "ok"
    }

    @Test
    fun everyPrincipalGetsExactlyItsOutcomeOnEveryRoute() = runTest {
        for (route in SecretRequestRoute.entries) {
            for (who in Who.entries) {
                val harness = SecretRouteHarness()
                val id = harness.create()
                assertEquals(expected(route, who), outcome(harness, route, who, id), "${route.method} as $who")
                val wrote = if (expected(route, who) == "ok" && route == SecretRequestRoute.SUBMIT) 1 else 0
                assertEquals(wrote, harness.provider.writes, "${route.method} as $who wrote")
            }
        }
    }

    @Test
    fun aDeniedCallerGetsTheSameBytesWhetherOrNotTheRequestExists() = runTest {
        val refusedConnections = listOf(ADMIN_TOKEN, UNAUTHENTICATED, LIMITED_HUMAN)
        for (route in SecretRequestRoute.entries) {
            for (context in refusedConnections + listOfNotNull(HUMAN.takeIf { route.side == SecretRouteSide.AGENT })) {
                val harness = SecretRouteHarness()
                val existing = harness.dispatch(route, params(route, harness.create()), context)
                assertEquals(existing, harness.dispatch(route, params(route, UNKNOWN_ID), context), route.method)
                assertEquals(refusalEnvelope(forbidden), existing, route.method)
            }
        }
    }

    @Test
    fun anotherAgentsRequestLooksExactlyLikeNoRequestToAWrongAgentHuman() = runTest {
        for (route in listOf(SecretRequestRoute.GET, SecretRequestRoute.SUBMIT, SecretRequestRoute.CANCEL)) {
            val harness = SecretRouteHarness()
            val existing = harness.dispatch(route, params(route, harness.create()), WRONG_AGENT_HUMAN)
            assertEquals(harness.dispatch(route, params(route, UNKNOWN_ID), HUMAN), existing, route.method)
            assertEquals(0, harness.provider.writes)
        }
    }

    @Test
    fun aWrongAgentHumanListsNothingOfAnotherAgentsAndTheRequestStaysPending() = runTest {
        val harness = SecretRouteHarness()
        val id = harness.create()
        val scoped = checkNotNull(resultOf(harness.dispatch(SecretRequestRoute.LIST, null, WRONG_AGENT_HUMAN)))
        assertTrue(scoped.getValue("requests").jsonArray.isEmpty())
        harness.dispatch(SecretRequestRoute.SUBMIT, submitParams(id, TestSecret("scoped-value")), WRONG_AGENT_HUMAN)
        val full = checkNotNull(resultOf(harness.dispatch(SecretRequestRoute.GET, idParams(id), HUMAN)))
        assertEquals(SecretRequestState.PENDING.name.lowercase(), full.stateOf())
    }

    @Test
    fun anAgentLearnsNothingAboutAnotherAgentsRequest() = runTest {
        val harness = SecretRouteHarness()
        val id = harness.create()
        val probe = harness.status(id, BYSTANDER)
        assertEquals(harness.status(UNKNOWN_ID, BYSTANDER), probe)
        assertEquals(ExternalToolResult.Error("unknown_request"), probe)
    }

    @Test
    fun noConnectionIsEverAnAgentEvenWithAdminFull() = runTest {
        val harness = SecretRouteHarness()
        val id = harness.create()
        for (context in listOf(HUMAN, ADMIN_TOKEN)) {
            assertEquals(refusalEnvelope(forbidden), harness.dispatch(SecretRequestRoute.STATUS, idParams(id), context))
            val create = harness.dispatch(SecretRequestRoute.REQUEST, params(SecretRequestRoute.REQUEST, id), context)
            assertEquals(refusalEnvelope(forbidden), create)
        }
    }
}

private fun errorOf(response: String): String =
    kotlinx.serialization.json.Json.parseToJsonElement(response)
        .let { it as JsonObject }["error"]
        .let { (it as kotlinx.serialization.json.JsonPrimitive).content }

private fun JsonObject.stateOf(): String =
    ((getValue("status") as JsonObject).getValue("state") as kotlinx.serialization.json.JsonPrimitive).content
