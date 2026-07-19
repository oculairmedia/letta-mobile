package com.letta.mobile.data.repository.iroh

import com.letta.mobile.data.model.ToolCreateParams
import com.letta.mobile.data.model.ToolUpdateParams
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class IrohToolRepositoryTest {

    @Test
    fun refreshToolsStopsPagingWhenPageAddsNoNewTools() = runTest(UnconfinedTestDispatcher()) {
        var listCallCount = 0
        val transport = FakeIrohAdminTransport()
        transport.rpcResponder = { call ->
            assertEquals("tool.list", call.method)
            listCallCount++
            ok(toolsJson(startIndex = 0, count = 100))
        }
        val repository = IrohToolRepository { IrohAdminRpcAgentDirectory(transport) }

        repository.refreshTools()

        assertEquals(100, repository.getTools().value.size)
        assertEquals(2, listCallCount)
    }

    @Test
    fun refreshToolsMergesMultipleDistinctPages() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeIrohAdminTransport()
        transport.rpcResponder = { call ->
            assertEquals("tool.list", call.method)
            when {
                call.path.contains("offset=0") -> ok(toolsJson(startIndex = 0, count = 100))
                call.path.contains("offset=100") -> ok(toolsJson(startIndex = 100, count = 25))
                else -> error("unexpected path ${call.path}")
            }
        }
        val repository = IrohToolRepository { IrohAdminRpcAgentDirectory(transport) }

        repository.refreshTools()

        assertEquals(125, repository.getTools().value.size)
        assertEquals(
            listOf("tool-0", "tool-124"),
            listOf(
                repository.getTools().value.first().id.value,
                repository.getTools().value.last().id.value,
            ),
        )
    }

    @Test
    fun fetchToolsPageReturnsRequestedSlice() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeIrohAdminTransport()
        transport.rpcResponder = { call ->
            assertEquals("tool.list", call.method)
            assertEquals("/v1/tools?limit=10&offset=5", call.path)
            ok(toolsJson(startIndex = 5, count = 2))
        }
        val repository = IrohToolRepository { IrohAdminRpcAgentDirectory(transport) }

        val page = repository.fetchToolsPage(limit = 10, offset = 5)

        assertEquals(listOf("tool-5", "tool-6"), page.map { it.id.value })
    }

    @Test
    fun upsertToolCreatesAndUpdatesCache() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeIrohAdminTransport()
        transport.rpcResponder = { call ->
            when (call.method) {
                "tool.create" -> {
                    assertEquals("/v1/tools", call.path)
                    assertTrue(call.body.orEmpty().contains("\"source_code\":\"print('hi')\""))
                    ok("""{"id":"tool-new","name":"Created","source_type":"python"}""")
                }
                else -> error("unexpected rpc ${call.method}")
            }
        }
        val repository = IrohToolRepository { IrohAdminRpcAgentDirectory(transport) }

        val created = repository.upsertTool(ToolCreateParams(sourceCode = "print('hi')"))

        assertEquals("tool-new", created.id.value)
        assertEquals("Created", created.name)
        assertEquals(listOf("tool-new"), repository.getTools().value.map { it.id.value })
    }

    @Test
    fun updateToolReplacesCachedTool() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeIrohAdminTransport()
        transport.rpcResponder = { call ->
            when (call.method) {
                "tool.list" -> ok("""[{"id":"tool-1","name":"Original","source_type":"python"}]""")
                "tool.update" -> {
                    assertEquals("tool.update", call.method)
                    assertEquals("/v1/tools/tool-1", call.path)
                    assertTrue(call.body.orEmpty().contains("\"tool_id\":\"tool-1\""))
                    assertTrue(call.body.orEmpty().contains("\"description\":\"updated\""))
                    ok("""{"id":"tool-1","name":"Renamed","description":"updated","source_type":"python"}""")
                }
                else -> error("unexpected rpc ${call.method}")
            }
        }
        val repository = IrohToolRepository { IrohAdminRpcAgentDirectory(transport) }
        repository.refreshTools()
        assertEquals("Original", repository.getTools().value.single().name)

        val updated = repository.updateTool("tool-1", ToolUpdateParams(description = "updated"))

        assertEquals("Renamed", updated.name)
        assertEquals("updated", updated.description)
        assertEquals(1, repository.getTools().value.size)
        assertEquals("Renamed", repository.getTools().value.single().name)
    }

    private fun ok(resultJson: String) = AppServerInboundFrame.AdminRpcResponse(
        requestId = "req-1",
        success = true,
        result = Json.parseToJsonElement(resultJson),
    )

    private fun toolsJson(startIndex: Int, count: Int): String {
        val tools = (startIndex until startIndex + count).map { index ->
            """{"id":"tool-$index","name":"Tool $index","source_type":"python"}"""
        }
        return "[${tools.joinToString(",")}]"
    }
}
