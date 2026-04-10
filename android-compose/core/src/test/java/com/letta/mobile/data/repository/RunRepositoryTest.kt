package com.letta.mobile.data.repository

import com.letta.mobile.data.model.Run
import com.letta.mobile.data.model.RunListParams
import com.letta.mobile.data.model.RunRequestConfig
import com.letta.mobile.testutil.FakeRunApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RunRepositoryTest {

    private lateinit var fakeApi: FakeRunApi
    private lateinit var repository: RunRepository

    @Before
    fun setup() {
        fakeApi = FakeRunApi()
        repository = RunRepository(fakeApi)
    }

    @Test
    fun `refreshRuns updates state flow`() = runTest {
        fakeApi.runs.addAll(listOf(sampleRun("r1", "a1"), sampleRun("r2", "a2")))

        repository.refreshRuns()

        assertEquals(2, repository.runs.first().size)
    }

    @Test
    fun `refreshRuns forwards filters`() = runTest {
        fakeApi.runs.addAll(listOf(sampleRun("r1", "a1"), sampleRun("r2", "a2")))

        repository.refreshRuns(RunListParams(agentId = "a2"))

        assertEquals(1, repository.runs.first().size)
        assertEquals("a2", repository.runs.first().first().agentId)
    }

    @Test
    fun `getRun retrieves run by id`() = runTest {
        fakeApi.runs.add(sampleRun("r1", "a1"))

        val result = repository.getRun("r1")

        assertEquals("r1", result.id)
        assertTrue(fakeApi.calls.contains("retrieveRun:r1"))
    }

    @Test
    fun `getRunMessages delegates to api`() = runTest {
        fakeApi.runs.add(sampleRun("r1", "a1"))

        val result = repository.getRunMessages("r1")

        assertEquals(1, result.size)
        assertTrue(fakeApi.calls.contains("listRunMessages:r1"))
    }

    @Test
    fun `getRunMetrics delegates to api`() = runTest {
        fakeApi.runs.add(sampleRun("r1", "a1"))

        val result = repository.getRunMetrics("r1")

        assertEquals("r1", result.id)
        assertTrue(fakeApi.calls.contains("retrieveRunMetrics:r1"))
    }

    @Test
    fun `cancelRun refreshes cached run`() = runTest {
        fakeApi.runs.add(sampleRun("r1", "a1"))
        repository.refreshRuns()

        val result = repository.cancelRun(repository.runs.first().first())

        assertEquals("cancelled", result.status)
        assertEquals("cancelled", repository.runs.first().first().status)
        assertTrue(fakeApi.calls.contains("cancelRun:a1:r1"))
    }

    @Test
    fun `deleteRun removes cached run`() = runTest {
        fakeApi.runs.addAll(listOf(sampleRun("r1", "a1"), sampleRun("r2", "a2")))
        repository.refreshRuns()

        repository.deleteRun("r1")

        assertEquals(listOf("r2"), repository.runs.first().map { it.id })
        assertTrue(fakeApi.calls.contains("deleteRun:r1"))
    }

    @Test
    fun `upsertRun updates cached run`() = runTest {
        repository.upsertRun(sampleRun("r1", "a1"))
        repository.upsertRun(sampleRun("r1", "a1").copy(status = "completed"))

        assertEquals(1, repository.runs.first().size)
        assertEquals("completed", repository.runs.first().first().status)
    }

    private fun sampleRun(id: String, agentId: String) = Run(
        id = id,
        agentId = agentId,
        status = "running",
        background = false,
        requestConfig = RunRequestConfig(useAssistantMessage = true),
    )
}
