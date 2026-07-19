package com.letta.mobile.data.repository

import com.letta.mobile.data.model.ToolId
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.testutil.FakeChannelTransport
import com.letta.mobile.testutil.FakeSettingsRepository
import com.letta.mobile.testutil.FakeToolApi
import com.letta.mobile.testutil.TestData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class ToolRepositoryTest {

    private lateinit var fakeApi: FakeToolApi
    private lateinit var repository: ToolRepository

    @Before
    fun setup() {
        fakeApi = FakeToolApi()
        repository = ToolRepository(fakeApi)
    }

    @Test
    fun `concurrent refreshToolsIfStale callers share one list request`() = runTest {
        fakeApi.tools.add(TestData.tool(id = "1"))
        fakeApi.listDelayMillis = 1L

        List(8) {
            launch { repository.refreshToolsIfStale(maxAgeMs = 60_000) }
        }.joinAll()

        assertEquals(1, fakeApi.calls.count { it == "listTools" })
        assertEquals(listOf(ToolId("1")), repository.getTools().first().map { it.id })
    }

    @Test
    fun `refreshTools updates StateFlow`() = runTest {
        fakeApi.tools.addAll(listOf(TestData.tool(id = "1"), TestData.tool(id = "2")))
        repository.refreshTools()
        val result = repository.getTools().first()
        assertEquals(2, result.size)
    }

    @Test
    fun `refreshToolsIfStale skips fresh cache`() = runTest {
        fakeApi.tools.add(TestData.tool(id = "1"))
        repository.refreshTools()
        fakeApi.calls.clear()

        repository.refreshToolsIfStale(maxAgeMs = 60_000)

        assertTrue(fakeApi.calls.none { it == "listTools" })
    }

    @Test
    fun `getTools returns empty initially`() = runTest {
        val result = repository.getTools().first()
        assertTrue(result.isEmpty())
    }

    @Test
    fun `countTools delegates to api`() = runTest {
        fakeApi.tools.addAll(listOf(TestData.tool(id = "1"), TestData.tool(id = "2"), TestData.tool(id = "3")))

        val count = repository.countTools()

        assertEquals(3, count)
        assertTrue(fakeApi.calls.contains("countTools"))
    }

    @Test
    fun `attachTool adds to agent tools`() = runTest {
        fakeApi.tools.add(TestData.tool(id = "t1", name = "search"))
        repository.refreshTools()
        repository.attachTool("a1", "t1")
        assertTrue(fakeApi.calls.contains("attachTool:a1:t1"))
    }

    @Test
    fun `detachTool removes from agent tools`() = runTest {
        fakeApi.tools.add(TestData.tool(id = "t1"))
        repository.refreshTools()
        repository.attachTool("a1", "t1")
        repository.detachTool("a1", "t1")
        assertTrue(fakeApi.calls.contains("detachTool:a1:t1"))
    }

    @Test
    fun `attach detach tool route through admin rpc in iroh mode`() = runTest {
        val transport = FakeChannelTransport().apply {
            adminRpcHandler = { _, _, _ -> AppServerInboundFrame.AdminRpcResponse("req", true, Json.parseToJsonElement("{}")) }
        }
        val irohRepository = ToolRepository(
            toolApi = fakeApi,
            irohToolSource = IrohAdminRpcToolSource(transport, irohSettings()),
        )

        irohRepository.attachTool("agent-1", "tool-1")
        irohRepository.detachTool("agent-1", "tool-1")

        assertTrue(fakeApi.calls.none { it.startsWith("attachTool") || it.startsWith("detachTool") })
        assertEquals(listOf("tool.attach", "tool.detach"), transport.adminRpcCalls.map { it.method })
        assertEquals("/v1/agents/agent-1/tools/attach/tool-1", transport.adminRpcCalls[0].path)
        assertEquals("/v1/agents/agent-1/tools/detach/tool-1", transport.adminRpcCalls[1].path)
    }

    private fun irohSettings() = FakeSettingsRepository(
        initialActiveConfig = LettaConfig(
            id = "iroh",
            mode = LettaConfig.Mode.SELF_HOSTED,
            serverUrl = "iroh://EndpointTicket",
        ),
    )

    @Test
    fun `upsertTool creates and refreshes`() = runTest {
        val tool = repository.upsertTool(
            com.letta.mobile.data.model.ToolCreateParams(
                sourceCode = "def new_tool():\n    return 'ok'",
            )
        )
        assertEquals("new_tool", tool.name)
        assertTrue(fakeApi.calls.any { it.startsWith("upsertTool") })
        assertTrue(repository.getTools().first().any { it.id == tool.id })
    }

    @Test
    fun `updateTool updates existing cached tool`() = runTest {
        fakeApi.tools.add(
            TestData.tool(id = "t1", name = "tool_one", description = "old").copy(
                sourceCode = "def tool_one():\n    return 'old'",
                toolType = "custom",
            )
        )
        repository.refreshTools()

        val updated = repository.updateTool(
            "t1",
            com.letta.mobile.data.model.ToolUpdateParams(
                description = "new",
                sourceCode = "def tool_one_v2():\n    return 'new'",
                sourceType = "python",
                jsonSchema = buildJsonObject { put("name", "tool_one_v2") },
            )
        )

        assertEquals("new", updated.description)
        assertEquals("tool_one_v2", updated.name)
        assertTrue(fakeApi.calls.contains("updateTool:t1"))
        val cachedTool = repository.getTools().first().first()
        assertEquals("new", cachedTool.description)
        assertEquals("tool_one_v2", cachedTool.name)
    }

    @Test
    fun `deleteTool removes tool from caches`() = runTest {
        fakeApi.tools.add(TestData.tool(id = "t1", name = "tool_one").copy(toolType = "custom"))
        repository.refreshTools()

        repository.deleteTool("t1")

        assertTrue(fakeApi.calls.contains("deleteTool:t1"))
        assertTrue(repository.getTools().first().none { it.id == ToolId("t1") })
    }

    @Test(expected = com.letta.mobile.data.api.ApiException::class)
    fun `refreshTools throws on API failure`() = runTest {
        fakeApi.shouldFail = true
        repository.refreshTools()
    }

    // ─── Iroh Purity Tests (letta-mobile client batch) ────────────────────────

    @Test(expected = com.letta.mobile.data.api.IrohAdminApiUnavailableException::class)
    fun `refreshTools in iroh mode without source throws IrohAdminApiUnavailableException`() = runTest {
        val apiThatThrows = object : FakeToolApi() {
            override suspend fun listTools(tags: List<String>?, limit: Int?, offset: Int?): List<com.letta.mobile.data.model.Tool> {
                throw com.letta.mobile.data.api.IrohAdminApiUnavailableException("Raw HTTP forbidden in iroh:// mode")
            }
        }
        val repo = ToolRepository(apiThatThrows)
        repo.refreshTools()
    }

    @Test
    fun `refreshTools in iroh mode routes via admin_rpc`() = runTest {
        val settings = com.letta.mobile.testutil.FakeSettingsRepository(
            initialActiveConfig = com.letta.mobile.data.model.LettaConfig(
                id = "test",
                mode = com.letta.mobile.data.model.LettaConfig.Mode.SELF_HOSTED,
                serverUrl = "iroh://test-node",
                accessToken = "token",
            )
        )
        val transport = com.letta.mobile.testutil.FakeChannelTransport()
        val testTools = listOf(TestData.tool(id = "t1", name = "search"))
        transport.adminRpcHandler = { method, path, body ->
            assertEquals("tool.list", method)
            assertEquals("/v1/tools", path)
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            com.letta.mobile.data.transport.appserver.AppServerInboundFrame.AdminRpcResponse(
                requestId = "req",
                success = true,
                result = json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(com.letta.mobile.data.model.Tool.serializer()), testTools),
                error = null,
            )
        }
        val irohSource = IrohAdminRpcToolSource(transport, settings)
        val apiThatThrows = object : FakeToolApi() {
            override suspend fun listTools(tags: List<String>?, limit: Int?, offset: Int?): List<com.letta.mobile.data.model.Tool> {
                throw com.letta.mobile.data.api.IrohAdminApiUnavailableException("Raw HTTP forbidden")
            }
        }
        val repo = ToolRepository(apiThatThrows, irohSource)
        repo.refreshTools()
        assertEquals(1, repo.getTools().first().size)
        assertEquals(1, transport.adminRpcCalls.size)
    }

    @Test
    fun `upsertTool in iroh mode routes via admin_rpc`() = runTest {
        val settings = com.letta.mobile.testutil.FakeSettingsRepository(
            initialActiveConfig = com.letta.mobile.data.model.LettaConfig(id = "test", mode = com.letta.mobile.data.model.LettaConfig.Mode.SELF_HOSTED, serverUrl = "iroh://test", accessToken = "t")
        )
        val transport = com.letta.mobile.testutil.FakeChannelTransport()
        val createdTool = TestData.tool(id = "t1", name = "new_tool")
        transport.adminRpcHandler = { method, _, _ ->
            assertEquals("tool.create", method)
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            com.letta.mobile.data.transport.appserver.AppServerInboundFrame.AdminRpcResponse(
                requestId = "req",
                success = true,
                result = json.encodeToJsonElement(com.letta.mobile.data.model.Tool.serializer(), createdTool),
                error = null,
            )
        }
        val irohSource = IrohAdminRpcToolSource(transport, settings)
        val apiThatThrows = object : FakeToolApi() {
            override suspend fun upsertTool(params: com.letta.mobile.data.model.ToolCreateParams): com.letta.mobile.data.model.Tool {
                throw com.letta.mobile.data.api.IrohAdminApiUnavailableException("Raw HTTP forbidden")
            }
        }
        val repo = ToolRepository(apiThatThrows, irohSource)
        val result = repo.upsertTool(com.letta.mobile.data.model.ToolCreateParams(sourceCode = "def new_tool(): return 'ok'"))
        assertEquals("new_tool", result.name)
        assertEquals(1, transport.adminRpcCalls.size)
    }

    @Test
    fun `updateTool in iroh mode routes via admin_rpc`() = runTest {
        val settings = com.letta.mobile.testutil.FakeSettingsRepository(
            initialActiveConfig = com.letta.mobile.data.model.LettaConfig(id = "test", mode = com.letta.mobile.data.model.LettaConfig.Mode.SELF_HOSTED, serverUrl = "iroh://test", accessToken = "t")
        )
        val transport = com.letta.mobile.testutil.FakeChannelTransport()
        val updatedTool = TestData.tool(id = "t1", name = "updated_tool", description = "Updated")
        transport.adminRpcHandler = { method, path, _ ->
            assertEquals("tool.update", method)
            assertEquals("/v1/tools/t1", path)
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            com.letta.mobile.data.transport.appserver.AppServerInboundFrame.AdminRpcResponse(
                requestId = "req",
                success = true,
                result = json.encodeToJsonElement(com.letta.mobile.data.model.Tool.serializer(), updatedTool),
                error = null,
            )
        }
        val irohSource = IrohAdminRpcToolSource(transport, settings)
        val apiThatThrows = object : FakeToolApi() {
            override suspend fun updateTool(toolId: ToolId, params: com.letta.mobile.data.model.ToolUpdateParams): com.letta.mobile.data.model.Tool {
                throw com.letta.mobile.data.api.IrohAdminApiUnavailableException("Raw HTTP forbidden")
            }
        }
        val repo = ToolRepository(apiThatThrows, irohSource)
        val result = repo.updateTool("t1", com.letta.mobile.data.model.ToolUpdateParams(description = "Updated"))
        assertEquals("Updated", result.description)
        assertEquals(1, transport.adminRpcCalls.size)
    }

    @Test
    fun `deleteTool in iroh mode routes via admin_rpc`() = runTest {
        val settings = com.letta.mobile.testutil.FakeSettingsRepository(
            initialActiveConfig = com.letta.mobile.data.model.LettaConfig(id = "test", mode = com.letta.mobile.data.model.LettaConfig.Mode.SELF_HOSTED, serverUrl = "iroh://test", accessToken = "t")
        )
        val transport = com.letta.mobile.testutil.FakeChannelTransport()
        transport.adminRpcHandler = { method, path, _ ->
            assertEquals("tool.delete", method)
            assertEquals("/v1/tools/t1", path)
            com.letta.mobile.data.transport.appserver.AppServerInboundFrame.AdminRpcResponse(requestId = "req", success = true, result = kotlinx.serialization.json.JsonNull, error = null)
        }
        val irohSource = IrohAdminRpcToolSource(transport, settings)
        val apiThatThrows = object : FakeToolApi() {
            override suspend fun deleteTool(toolId: ToolId) {
                throw com.letta.mobile.data.api.IrohAdminApiUnavailableException("Raw HTTP forbidden")
            }
        }
        val repo = ToolRepository(apiThatThrows, irohSource)
        repo.deleteTool("t1")
        assertEquals(1, transport.adminRpcCalls.size)
    }
}
