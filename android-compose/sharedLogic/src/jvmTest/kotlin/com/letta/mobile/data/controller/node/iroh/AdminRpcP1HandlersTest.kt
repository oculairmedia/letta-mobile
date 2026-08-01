package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdminRpcP1HandlersTest {
    private val calls = mutableListOf<ProxyCall>()
    private val originalFactory = AdminProxyClient.defaultTransportFactory

    @BeforeTest
    fun setUp() {
        AdminProxyClient.defaultTransportFactory = {
            AdminProxyTransport { method, url, body ->
                calls += ProxyCall(method, url, body)
                AdminProxyTransportResponse(200, responseBody(method, url))
            }
        }
    }

    @AfterTest
    fun tearDown() {
        AdminProxyClient.defaultTransportFactory = originalFactory
    }

    /**
     * lgns8.9: the block / tool / passage / agent-context admin REST proxies are
     * GONE. Each of those methods now has an explicit non-shim owner (native
     * command, read-only store, controller catalog) or a fail-closed denial, so
     * the characterization here is the SUCCESSOR contract: with no store, no
     * native client, and no VibeSync, none of them may dial the admin proxy —
     * they either deny or answer from a controller-owned constant.
     *
     * Each of these paths also 404s in admin-shim itself, so denial is parity.
     */
    @Test
    fun `block tool passage and context methods never dial an admin rest proxy`() = runTest {
        val router = AdminRpcRegistry.buildRouter("http://admin.test", vibesyncBaseUrl = "http://admin.test")

        val denied = listOf(
            "block.attach" to params("agent_id" to "agent-1", "block_id" to "block-1"),
            "block.detach" to params("agent_id" to "agent-1", "block_id" to "block-1"),
            "block.create" to params("label" to "persona", "value" to "v"),
            "block.update" to params("block_id" to "block-1", "value" to "v"),
            "block.delete" to params("block_id" to "block-1"),
            "tool.attach" to params("agent_id" to "agent-1", "tool_id" to "tool-1"),
            "tool.detach" to params("agent_id" to "agent-1", "tool_id" to "tool-1"),
            "tool.create" to params("name" to "t"),
            "tool.update" to params("tool_id" to "tool-1"),
            "tool.delete" to params("tool_id" to "tool-1"),
            "passage.create" to params("agent_id" to "agent-1", "text" to "remember this"),
            "passage.delete" to params("agent_id" to "agent-1", "passage_id" to "passage-1"),
            "passage.list" to params("agent_id" to "agent-1"),
        )
        denied.forEach { (method, p) ->
            val response = router.dispatch("test-request", method, p)
            assertTrue(response.contains("\"success\":false"), "$method must fail closed: $response")
            assertTrue(response.contains("capability_unavailable"), "$method must deny typed: $response")
        }

        // Store-owned and native-owned rows fail closed too when unwired, but
        // never by dialing HTTP.
        listOf(
            "block.list" to params(),
            "block.get" to params("block_id" to "block-1"),
            "block.update_agent" to params("agent_id" to "agent-1", "label" to "persona", "value" to "updated"),
            "agent.context" to params("agent_id" to "agent-1", "conversation_id" to "conversation-1"),
        ).forEach { (method, p) ->
            val response = router.dispatch("test-request", method, p)
            assertTrue(response.contains("\"success\":false"), "$method must fail closed unwired: $response")
        }

        assertEquals(emptyList(), calls, "no admin REST surface may remain: $calls")
    }

    /** The controller-owned constant catalogs answer without any datastore. */
    @Test
    fun `tool catalog reads are served from the controller without a proxy`() = runTest {
        val router = AdminRpcRegistry.buildRouter("http://admin.test", vibesyncBaseUrl = "http://admin.test")

        val list = router.dispatchResult("tool.list", params()).jsonArray
        assertTrue(list.isNotEmpty(), "the builtin tool catalog must not be empty")
        val toolId = list.first().jsonObject.getValue("id").jsonPrimitive.content
        val tool = router.dispatchResult("tool.get", params("tool_id" to toolId)).jsonObject
        assertEquals(toolId, tool.getValue("id").jsonPrimitive.content)

        assertEquals(emptyList(), calls, "catalog reads must not dial a proxy: $calls")
    }

    @Test
    fun `project handlers accept project_id alias and prefer identifier`() = runTest {
        val router = AdminRpcRegistry.buildRouter("http://admin.test", vibesyncBaseUrl = "http://admin.test")

        router.dispatchResult("project.get", params("project_id" to "legacy-proj"))
        router.dispatchResult(
            "project.update",
            params("identifier" to "canonical", "project_id" to "legacy-proj", "git_url" to "https://example.com/repo.git"),
        )
        router.dispatchResult(
            "project.provisionBeadsRemote",
            params("project_id" to "legacy-proj", "push" to "true"),
        )

        assertEquals(
            listOf(
                ProxyCall("GET", "http://admin.test/api/projects/legacy-proj", null),
                ProxyCall("PATCH", "http://admin.test/api/registry/projects/canonical", "{\"git_url\":\"https://example.com/repo.git\"}"),
                ProxyCall("POST", "http://admin.test/api/projects/legacy-proj/beads-remote/provision", "{\"push\":\"true\"}"),
            ),
            calls,
        )
    }

    @Test
    fun `project handlers proxy existing api project endpoints`() = runTest {
        val router = AdminRpcRegistry.buildRouter("http://admin.test", vibesyncBaseUrl = "http://admin.test")

        router.dispatchResult("project.list", params("limit" to "1"))
        router.dispatchResult("project.get", params("identifier" to "vibesync"))
        router.dispatchResult("project.beadsRemoteStatus", params("identifier" to "vibesync"))
        router.dispatchResult("project.provisionBeadsRemote", params("identifier" to "vibesync", "push" to "true"))
        router.dispatchResult("project.triggerSync", params("projectId" to "vibesync"))
        router.dispatchResult("project.create", params("filesystem_path" to "/opt/stacks/new"))
        router.dispatchResult("project.update", params("identifier" to "vibesync", "git_url" to "https://github.com/o/r.git"))
        router.dispatchResult("project.archive", params("identifier" to "vibesync", "status" to "archived"))
        router.dispatchResult("project.delete", params("identifier" to "vibesync"))

        assertEquals(
            listOf(
                ProxyCall("GET", "http://admin.test/api/projects?limit=1", null),
                ProxyCall("GET", "http://admin.test/api/projects/vibesync", null),
                ProxyCall("GET", "http://admin.test/api/projects/vibesync/beads-remote", null),
                ProxyCall("POST", "http://admin.test/api/projects/vibesync/beads-remote/provision", "{\"push\":\"true\"}"),
                ProxyCall("POST", "http://admin.test/api/sync/trigger", "{\"projectId\":\"vibesync\"}"),
                ProxyCall("POST", "http://admin.test/api/registry/projects", "{\"filesystem_path\":\"/opt/stacks/new\"}"),
                ProxyCall("PATCH", "http://admin.test/api/registry/projects/vibesync", "{\"git_url\":\"https://github.com/o/r.git\"}"),
                ProxyCall("PATCH", "http://admin.test/api/registry/projects/vibesync", "{\"status\":\"archived\"}"),
                ProxyCall("DELETE", "http://admin.test/api/registry/projects/vibesync", null),
            ),
            calls,
        )
    }

    private suspend fun AdminRpcRouter.dispatchResult(method: String, params: JsonObject): kotlinx.serialization.json.JsonElement {
        val response = dispatch("test-request", method, params).let { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject }
        assertEquals(true, response["success"]?.let { (it as JsonPrimitive).content == "true" })
        return response.getValue("result")
    }

    private fun params(vararg entries: Pair<String, String>): JsonObject = buildJsonObject {
        entries.forEach { (key, value) -> put(key, value) }
    }

    private fun responseBody(method: String, url: String): String = buildJsonObject {
        put("ok", JsonPrimitive(true))
        put("method", method)
        put("url", url)
    }.toString()

    private data class ProxyCall(
        val method: String,
        val url: String,
        val body: String?,
    )
}
