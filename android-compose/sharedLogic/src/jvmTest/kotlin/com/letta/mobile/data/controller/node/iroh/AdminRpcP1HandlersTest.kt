package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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

    @Test
    fun `block handlers proxy PATCH method and expected core memory paths`() = runTest {
        val router = AdminRpcRegistry.buildRouter("http://admin.test")

        router.dispatchResult("block.attach", params("agent_id" to "agent-1", "block_id" to "block-1"))
        router.dispatchResult("block.detach", params("agent_id" to "agent-1", "block_id" to "block-1"))
        router.dispatchResult("block.update_agent", params("agent_id" to "agent-1", "label" to "persona", "value" to "updated"))

        assertEquals(
            listOf(
                ProxyCall("PATCH", "http://admin.test/v1/agents/agent-1/core-memory/blocks/attach/block-1", "{}"),
                ProxyCall("PATCH", "http://admin.test/v1/agents/agent-1/core-memory/blocks/detach/block-1", "{}"),
                ProxyCall("PATCH", "http://admin.test/v1/agents/agent-1/core-memory/blocks/persona", "{\"value\":\"updated\"}"),
            ),
            calls,
        )
    }

    @Test
    fun `tool handlers proxy PATCH method and expected attach detach paths`() = runTest {
        val router = AdminRpcRegistry.buildRouter("http://admin.test")

        router.dispatchResult("tool.attach", params("agent_id" to "agent-1", "tool_id" to "tool-1"))
        router.dispatchResult("tool.detach", params("agent_id" to "agent-1", "tool_id" to "tool-1"))

        assertEquals(
            listOf(
                ProxyCall("PATCH", "http://admin.test/v1/agents/agent-1/tools/attach/tool-1", "{}"),
                ProxyCall("PATCH", "http://admin.test/v1/agents/agent-1/tools/detach/tool-1", "{}"),
            ),
            calls,
        )
    }

    @Test
    fun `passage handlers proxy POST DELETE and expected archival memory paths`() = runTest {
        val router = AdminRpcRegistry.buildRouter("http://admin.test")

        router.dispatchResult("passage.create", params("agent_id" to "agent-1", "text" to "remember this"))
        router.dispatchResult("passage.delete", params("agent_id" to "agent-1", "passage_id" to "passage-1"))

        assertEquals(
            listOf(
                ProxyCall("POST", "http://admin.test/v1/agents/agent-1/archival-memory", "{\"text\":\"remember this\"}"),
                ProxyCall("DELETE", "http://admin.test/v1/agents/agent-1/archival-memory/passage-1", null),
            ),
            calls,
        )
    }

    @Test
    fun `agent context handler proxies GET with conversation id query`() = runTest {
        val router = AdminRpcRegistry.buildRouter("http://admin.test")

        val result = router.dispatchResult("agent.context", params("agent_id" to "agent-1", "conversation_id" to "conversation-1"))

        assertEquals(
            listOf(ProxyCall("GET", "http://admin.test/v1/agents/agent-1/context?conversation_id=conversation-1", null)),
            calls,
        )
        assertTrue(result.jsonObject.containsKey("ok"))
    }

    @Test
    fun `project handlers reject missing identifier without proxying`() = runTest {
        val router = AdminRpcRegistry.buildRouter("http://admin.test")
        val methods = listOf(
            "project.get",
            "project.beadsRemoteStatus",
            "project.provisionBeadsRemote",
            "project.update",
            "project.archive",
            "project.delete",
        )

        methods.forEach { method ->
            val response = router.dispatch("test-request", method, buildJsonObject {})
                .let { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject }

            assertEquals("false", response.getValue("success").jsonPrimitive.content, method)
            assertEquals(PROJECT_IDENTIFIER_REQUIRED, response.getValue("error").jsonPrimitive.content, method)
        }
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `project handlers accept project_id alias and prefer identifier`() = runTest {
        val router = AdminRpcRegistry.buildRouter("http://admin.test")

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
        val router = AdminRpcRegistry.buildRouter("http://admin.test")

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
