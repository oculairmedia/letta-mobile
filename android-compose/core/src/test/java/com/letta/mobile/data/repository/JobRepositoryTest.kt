package com.letta.mobile.data.repository

import com.letta.mobile.data.model.Job
import com.letta.mobile.data.model.JobListParams
import com.letta.mobile.testutil.FakeJobApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JobRepositoryTest {

    private lateinit var fakeApi: FakeJobApi
    private lateinit var repository: JobRepository

    @Before
    fun setup() {
        fakeApi = FakeJobApi()
        repository = JobRepository(fakeApi)
    }

    @Test
    fun `refreshJobs updates state flow`() = runTest {
        fakeApi.jobs.addAll(listOf(sampleJob("job-1", "created"), sampleJob("job-2", "completed")))

        repository.refreshJobs()

        assertEquals(2, repository.jobs.first().size)
    }

    @Test
    fun `refreshJobs forwards active filter`() = runTest {
        fakeApi.jobs.addAll(listOf(sampleJob("job-1", "running"), sampleJob("job-2", "completed")))

        repository.refreshJobs(JobListParams(active = true))

        assertEquals(1, repository.jobs.first().size)
        assertEquals("running", repository.jobs.first().first().status)
    }

    @Test
    fun `getJob retrieves job by id`() = runTest {
        fakeApi.jobs.add(sampleJob("job-1", "running"))

        val result = repository.getJob("job-1")

        assertEquals("job-1", result.id)
        assertTrue(fakeApi.calls.contains("retrieveJob:job-1"))
    }

    @Test
    fun `cancelJob updates cached job`() = runTest {
        repository.upsertJob(sampleJob("job-1", "running"))
        fakeApi.jobs.add(sampleJob("job-1", "running"))

        repository.cancelJob("job-1")

        assertEquals("cancelled", repository.jobs.first().first().status)
    }

    @Test
    fun `deleteJob removes cached job`() = runTest {
        repository.upsertJob(sampleJob("job-1", "running"))
        fakeApi.jobs.add(sampleJob("job-1", "running"))

        repository.deleteJob("job-1")

        assertTrue(repository.jobs.first().isEmpty())
    }
}

private fun sampleJob(id: String, status: String) = Job(
    id = id,
    status = status,
    agentId = "agent-1",
    jobType = "job",
)
