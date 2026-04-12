package com.letta.mobile.data.repository

import com.letta.mobile.testutil.FakeToolApi
import com.letta.mobile.testutil.TestData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ToolRepositoryTest {

    private lateinit var fakeApi: FakeToolApi
    private lateinit var repository: ToolRepository

    @Before
    fun setup() {
        fakeApi = FakeToolApi()
        repository = ToolRepository(fakeApi)
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
        assertTrue(repository.getTools().first().none { it.id == "t1" })
    }

    @Test(expected = com.letta.mobile.data.api.ApiException::class)
    fun `refreshTools throws on API failure`() = runTest {
        fakeApi.shouldFail = true
        repository.refreshTools()
    }
}
