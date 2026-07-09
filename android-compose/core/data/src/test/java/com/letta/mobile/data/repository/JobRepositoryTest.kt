package com.letta.mobile.data.repository

import com.letta.mobile.data.api.IrohAdminApiUnavailableException
import com.letta.mobile.data.model.Job
import com.letta.mobile.data.model.JobListParams
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.testutil.FakeChannelTransport
import com.letta.mobile.testutil.FakeJobApi
import com.letta.mobile.testutil.FakeSettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class JobRepositoryTest {

    private lateinit var fakeApi: FakeJobApi
    private lateinit var repository: JobRepository

    @Before
    fun setup() {
        fakeApi = FakeJobApi()
        repository = JobRepository(fakeApi)
    }

    @Test
    fun `refreshJobs calls API with correct params`() = runTest {
        fakeApi.jobs.add(Job(id = "j1", status = "completed", agentId = "a1"))
        repository.refreshJobs(JobListParams(active = false))
        assertEquals(1, fakeApi.calls.size)
    }

    @Test
    fun `getJob returns correct job`() = runTest {
        val testJob = Job(id = "j1", status = "running", agentId = "a1")
        fakeApi.jobs.add(testJob)
        val result = repository.getJob("j1")
        assertEquals("j1", result.id)
        assertEquals("running", result.status)
    }

    // ─── Iroh Purity Tests (letta-mobile client batch) ────────────────────────

    @Test(expected = IrohAdminApiUnavailableException::class)
    fun `refreshJobs in iroh mode without source throws IrohAdminApiUnavailableException`() = runTest {
        val settings = FakeSettingsRepository(
            initialActiveConfig = LettaConfig(
                id = "test",
                mode = LettaConfig.Mode.SELF_HOSTED,
                serverUrl = "iroh://test-node",
                accessToken = "token",
            )
        )
        val apiThatThrows = object : FakeJobApi() {
            override suspend fun listJobs(params: JobListParams): List<Job> {
                throw IrohAdminApiUnavailableException("Raw HTTP forbidden in iroh:// mode")
            }
        }
        val repo = JobRepository(apiThatThrows)
        repo.refreshJobs(JobListParams())
    }

    @Test
    fun `refreshJobs in iroh mode routes via admin_rpc`() = runTest {
        val settings = FakeSettingsRepository(
            initialActiveConfig = LettaConfig(
                id = "test",
                mode = LettaConfig.Mode.SELF_HOSTED,
                serverUrl = "iroh://test-node",
                accessToken = "token",
            )
        )
        val transport = FakeChannelTransport()
        val testJobs = listOf(
            Job(id = "j1", status = "completed", agentId = "a1"),
            Job(id = "j2", status = "running", agentId = "a2"),
        )
        transport.adminRpcHandler = { method, path, body ->
            assertEquals("job.list", method)
            assertEquals("/v1/jobs", path)
            val json = Json { ignoreUnknownKeys = true }
            AppServerInboundFrame.AdminRpcResponse(
                requestId = "req",
                success = true,
                result = json.encodeToJsonElement(ListSerializer(Job.serializer()), testJobs),
                error = null,
            )
        }
        val irohSource = IrohAdminRpcJobSource(transport, settings)
        val apiThatThrows = object : FakeJobApi() {
            override suspend fun listJobs(params: JobListParams): List<Job> {
                throw IrohAdminApiUnavailableException("Raw HTTP forbidden")
            }
        }
        val repo = JobRepository(apiThatThrows, irohSource)
        repo.refreshJobs(JobListParams())
        assertEquals(2, repo.jobs.value.size)
        assertEquals(1, transport.adminRpcCalls.size)
    }

    @Test
    fun `getJob in iroh mode routes via admin_rpc`() = runTest {
        val settings = FakeSettingsRepository(
            initialActiveConfig = LettaConfig(
                id = "test",
                mode = LettaConfig.Mode.SELF_HOSTED,
                serverUrl = "iroh://test-node",
                accessToken = "token",
            )
        )
        val transport = FakeChannelTransport()
        val testJob = Job(id = "j1", status = "completed", agentId = "a1")
        transport.adminRpcHandler = { method, path, body ->
            assertEquals("job.get", method)
            assertEquals("/v1/jobs/j1", path)
            val json = Json { ignoreUnknownKeys = true }
            AppServerInboundFrame.AdminRpcResponse(
                requestId = "req",
                success = true,
                result = json.encodeToJsonElement(Job.serializer(), testJob),
                error = null,
            )
        }
        val irohSource = IrohAdminRpcJobSource(transport, settings)
        val apiThatThrows = object : FakeJobApi() {
            override suspend fun retrieveJob(jobId: String): Job {
                throw IrohAdminApiUnavailableException("Raw HTTP forbidden")
            }
        }
        val repo = JobRepository(apiThatThrows, irohSource)
        val result = repo.getJob("j1")
        assertEquals("j1", result.id)
        assertEquals(1, transport.adminRpcCalls.size)
    }
}
