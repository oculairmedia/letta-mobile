package com.letta.mobile.data.repository

import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.model.ProjectSummary
import com.letta.mobile.testutil.FakeProjectApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectRepositoryTest {

    private lateinit var fakeApi: FakeProjectApi
    private lateinit var repository: ProjectRepository

    @Before
    fun setup() {
        fakeApi = FakeProjectApi()
        repository = ProjectRepository(fakeApi)
    }

    @Test
    fun `refreshProjects updates StateFlow and sanitizes git urls`() = runTest {
        fakeApi.projects.add(
            ProjectSummary(
                identifier = "GRAPH",
                name = "Graphiti",
                gitUrl = "https://token@github.com/getzep/graphiti.git",
                updatedAt = "1776066315930",
            )
        )

        val result = repository.refreshProjects()

        assertEquals(1, result.total)
        val project = repository.projects.first().single()
        assertEquals("https://github.com/getzep/graphiti.git", project.gitUrl)
        assertEquals("1776066315930", project.updatedAt)
        assertTrue(fakeApi.calls.contains("listProjects"))
    }

    @Test
    fun `getProject uses cache when available`() = runTest {
        fakeApi.projects.add(ProjectSummary(identifier = "GRAPH", name = "Graphiti"))
        repository.refreshProjects()
        fakeApi.calls.clear()

        val result = repository.getProject("GRAPH")

        assertEquals("Graphiti", result.name)
        assertTrue(fakeApi.calls.none { it.startsWith("getProject:") })
    }

    @Test
    fun `getProject fetches and caches when missing locally`() = runTest {
        fakeApi.projects.add(ProjectSummary(identifier = "HVSYN", name = "Huly-Vibe Sync Service"))

        val result = repository.getProject("HVSYN")

        assertEquals("HVSYN", result.identifier)
        assertTrue(fakeApi.calls.contains("getProject:HVSYN"))
        assertEquals(listOf("HVSYN"), repository.projects.first().map { it.identifier })
    }

    @Test
    fun `refreshProjectsIfStale skips fresh cache`() = runTest {
        fakeApi.projects.add(ProjectSummary(identifier = "GRAPH", name = "Graphiti"))
        repository.refreshProjects()
        fakeApi.calls.clear()

        repository.refreshProjectsIfStale(maxAgeMs = 60_000)

        assertTrue(fakeApi.calls.isEmpty())
    }

    @Test(expected = ApiException::class)
    fun `refreshProjects throws on api failure`() = runTest {
        fakeApi.shouldFail = true
        repository.refreshProjects()
    }
}
