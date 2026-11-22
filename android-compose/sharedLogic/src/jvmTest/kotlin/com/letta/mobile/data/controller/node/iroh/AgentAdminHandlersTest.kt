package com.letta.mobile.data.controller.node.iroh

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import java.nio.file.Files

class AgentAdminHandlersTest {

    private suspend fun dispatch(r: AdminRpcRouter, method: String, params: Map<String, String>?): String =
        r.dispatch(
            AdminRpcInvocation(
                requestId = "t-1",
                method = method,
                params = params?.let { p -> buildJsonObject { p.forEach { (k, v) -> put(k, v) } } },
                context = AdminRpcRequestContext.Authenticated,
            ),
        )

    @Test
    fun agentListCoercesInvalidLimitAndOffsetAndSucceeds() = runTest {
        val root = Files.createTempDirectory("agent-admin-handlers").toFile()
        LocalBackendFixtureStore.create(root)
        val native = FakeNativeClient(agentRosterSize = 5)
        val r = AdminRpcRouter()
        AgentAdminHandlers.register(
            r,
            controller = null,
            tiers = NativeReadTiers(
                nativeClient = native,
                localBackendStore = LocalBackendAdminStore(root, lmstudioBaseUrl = "http://e/v1"),
            ),
        )

        val invalidLimitResponse = dispatch(r, "agent.list", mapOf("limit" to "0"))
        val jsonLimit = Json.parseToJsonElement(invalidLimitResponse).jsonObject
        assertTrue(jsonLimit["success"]!!.jsonPrimitive.boolean)

        val invalidOffsetResponse = dispatch(r, "agent.list", mapOf("offset" to "-1"))
        val jsonOffset = Json.parseToJsonElement(invalidOffsetResponse).jsonObject
        assertTrue(jsonOffset["success"]!!.jsonPrimitive.boolean)

        root.deleteRecursively()
    }

    @Test
    fun requiresAgentIdWhereApplicable() = runTest {
        val root = Files.createTempDirectory("agent-admin-handlers2").toFile()
        LocalBackendFixtureStore.create(root)
        val native = FakeNativeClient(agentRosterSize = 5)
        val r = AdminRpcRouter()
        AgentAdminHandlers.register(
            r,
            controller = null,
            tiers = NativeReadTiers(
                nativeClient = native,
                localBackendStore = LocalBackendAdminStore(root, lmstudioBaseUrl = "http://e/v1"),
            ),
        )

        val methods = listOf("agent.get", "agent.update", "agent.delete", "agent.context")
        for (method in methods) {
            val response = dispatch(r, method, emptyMap())
            val json = Json.parseToJsonElement(response).jsonObject
            assertFalse(json["success"]!!.jsonPrimitive.boolean, "$method did not return success=false")
            assertEquals("agent_id required", json["error"]?.jsonPrimitive?.content)

            val responseNull = dispatch(r, method, null)
            val jsonNull = Json.parseToJsonElement(responseNull).jsonObject
            assertFalse(jsonNull["success"]!!.jsonPrimitive.boolean, "$method did not return success=false on null")
            assertEquals("agent_id required", jsonNull["error"]?.jsonPrimitive?.content)
        }

        root.deleteRecursively()
    }

    /**
     * A 136-agent roster swept the way clients sweep it (pages of 50) carries
     * only the list fields, inside a byte budget, with every kept value unchanged.
     */
    @Test
    fun agentListServesTheSlimListProjectionWithinBudgetForA136AgentRoster() = runTest {
        val rows = (1..ROSTER_SIZE).map(::liveShapedAgentRow)
        val (router, root) = rosterRouter(FakeNativeClient(agentRows = rows))
        val pages = (0 until ROSTER_SIZE step SWEEP_PAGE).map { offset ->
            dispatch(router, "agent.list", mapOf("limit" to "$SWEEP_PAGE", "offset" to "$offset"))
        }
        val listed = pages.flatMap { page ->
            val envelope = Json.parseToJsonElement(page).jsonObject
            assertTrue(envelope.getValue("success").jsonPrimitive.boolean, page.take(300))
            envelope.getValue("result").jsonArray.map { it.jsonObject }
        }
        val fullBytes = rows.sumOf { it.toString().length }
        val slimBytes = pages.sumOf { it.length }
        println("agent.list 136-agent sweep: before=$fullBytes chars, after=$slimBytes chars")

        assertEquals(rows.map { it.getValue("id") }, listed.map { it.getValue("id") })
        listed.forEach { row ->
            val extra = row.keys - LIST_FIELDS
            assertTrue(extra.isEmpty(), "agent.list row ${row["id"]} carries detail fields $extra")
        }
        val source = rows.associateBy { it.getValue("id") }
        listed.forEach { row -> row.forEach { (key, value) -> assertEquals(source.getValue(row.getValue("id"))[key], value, key) } }
        assertTrue(slimBytes <= ROSTER_SIZE * ROW_BUDGET_BYTES, "slim sweep is $slimBytes chars, budget ${ROSTER_SIZE * ROW_BUDGET_BYTES}")
        root.deleteRecursively()
    }

    /** The detail an open or edit needs still arrives whole from agent.get; an unknown id is refused. */
    @Test
    fun agentGetStillReturnsTheFullAgentAndRefusesAnUnknownId() = runTest {
        val row = liveShapedAgentRow(1)
        val (router, root) = rosterRouter(FakeNativeClient(agentRows = listOf(row)))

        val detail = Json.parseToJsonElement(dispatch(router, "agent.get", mapOf("agent_id" to "agent-1"))).jsonObject
        assertEquals(row, detail.getValue("result"))
        val missing = Json.parseToJsonElement(dispatch(router, "agent.get", mapOf("agent_id" to "agent-404"))).jsonObject
        assertFalse(missing.getValue("success").jsonPrimitive.boolean)
        root.deleteRecursively()
    }

    private fun rosterRouter(native: FakeNativeClient): Pair<AdminRpcRouter, java.io.File> {
        val root = Files.createTempDirectory("agent-list-slim").toFile()
        LocalBackendFixtureStore.create(root)
        val router = AdminRpcRouter()
        AgentAdminHandlers.register(
            router,
            controller = null,
            tiers = NativeReadTiers(
                nativeClient = native,
                localBackendStore = LocalBackendAdminStore(root, lmstudioBaseUrl = "http://e/v1"),
            ),
        )
        return router to root
    }

    /** One row as letta-code 0.30.5 `projectLocalAgentState` emits it (median live system prompt ~12 KB). */
    private fun liveShapedAgentRow(n: Int): JsonObject = buildJsonObject {
        put("id", "agent-$n")
        put("name", "Agent $n")
        put("description", "Owns project $n")
        put("system", "You are a Letta Code agent. ".repeat(SYSTEM_PROMPT_REPEATS))
        put("tools", JsonArray(emptyList()))
        put("tags", JsonArray(listOf(JsonPrimitive("origin:letta-code"), JsonPrimitive("pm"))))
        put("model", "anthropic/claude-sonnet-5-5")
        put("model_settings", buildJsonObject { put("provider_type", "anthropic"); put("context_window_limit", 1_000_000) })
        val messages = JsonArray((1..MESSAGE_IDS).map { JsonPrimitive("message-$n-$it") })
        put("message_ids", messages)
        put("in_context_message_ids", messages)
        put("last_run_completion", "2026-11-01T03:16:48.945Z")
        put("llm_config", buildJsonObject { put("model", "claude-sonnet-5-5"); put("context_window", 1_000_000) })
        put("compaction_settings", buildJsonObject { put("mode", "sliding_window") })
    }

    private companion object {
        const val ROSTER_SIZE = 136
        const val SWEEP_PAGE = 50
        const val ROW_BUDGET_BYTES = 1_024
        const val SYSTEM_PROMPT_REPEATS = 430
        const val MESSAGE_IDS = 20

        /** The list projection named in docs/reference/contracts/agent.list/slim-list-v1.md. */
        val LIST_FIELDS = setOf(
            "id", "name", "description", "tags", "hidden", "metadata", "model", "model_settings",
            "llm_config", "context_window_limit", "last_run_completion", "created_at", "updated_at",
        )
    }
}
