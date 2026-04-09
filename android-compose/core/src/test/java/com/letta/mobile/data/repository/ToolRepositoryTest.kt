package com.letta.mobile.data.repository

import com.letta.mobile.testutil.FakeToolApi
import com.letta.mobile.testutil.TestData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
    fun `getTools returns empty initially`() = runTest {
        val result = repository.getTools().first()
        assertTrue(result.isEmpty())
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
                name = "new_tool",
                sourceCode = "def new_tool():\n    return 'ok'",
            )
        )
        assertEquals("new_tool", tool.name)
        assertTrue(fakeApi.calls.any { it.startsWith("upsertTool") })
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
                sourceCode = "def tool_one():\n    return 'new'",
            )
        )

        assertEquals("new", updated.description)
        assertTrue(fakeApi.calls.contains("updateTool:t1"))
        assertEquals("new", repository.getTools().first().first().description)
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
