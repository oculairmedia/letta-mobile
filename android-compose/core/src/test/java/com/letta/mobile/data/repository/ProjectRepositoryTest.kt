package com.letta.mobile.data.repository

import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.model.ProjectDataFreshness
import com.letta.mobile.data.model.ProjectId
import com.letta.mobile.data.model.ProjectRepoInfo
import com.letta.mobile.data.model.ProjectSummary
import com.letta.mobile.data.model.ProjectTrackerInfo
import com.letta.mobile.data.model.ProjectTrackerSummary
import com.letta.mobile.testutil.FakeProjectApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class ProjectRepositoryTest {

    private lateinit var fakeApi: FakeProjectApi
    private lateinit var repository: ProjectRepository

    @Before
    fun setup() {
        fakeApi = FakeProjectApi()
        repository = ProjectRepository(fakeApi)
    }

    @Test
    fun `concurrent refreshProjectsIfStale callers share one list request`() = runTest {
        fakeApi.projects.add(ProjectSummary(identifier = "GRAPH", name = "Graphiti"))
        fakeApi.listDelayMillis = 1L

        List(8) {
            launch { repository.refreshProjectsIfStale(maxAgeMs = 60_000) }
        }.joinAll()

        assertEquals(1, fakeApi.calls.count { it == "listProjects" })
        assertEquals(listOf("GRAPH"), repository.projects.first().map { it.identifier })
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
        // normalizeTimestamp converts epoch-millis strings to ISO-8601
        assertEquals("2026-04-13T07:45:15.930Z", project.updatedAt)
        assertTrue(fakeApi.calls.contains("listProjects"))
    }

    @Test
    fun `refreshProjects preserves clean git urls and iso timestamps`() = runTest {
        fakeApi.projects.add(
            ProjectSummary(
                identifier = "LETMOB",
                name = "Letta Mobile",
                gitUrl = "https://github.com/oculairmedia/letta-mobile.git",
                updatedAt = "2026-04-14T07:45:15.930Z",
            )
        )

        repository.refreshProjects()

        val project = repository.projects.first().single()
        assertEquals("https://github.com/oculairmedia/letta-mobile.git", project.gitUrl)
        assertEquals("2026-04-14T07:45:15.930Z", project.updatedAt)
    }

    @Test
    fun `refreshProjects normalizes blank timestamps to null and preserves non epoch strings`() = runTest {
        fakeApi.projects.add(
            ProjectSummary(
                identifier = "GRAPH",
                name = "Graphiti",
                updatedAt = "not-a-timestamp",
                lastScanAt = "   ",
                lastSyncAt = "1776066315930",
            )
        )

        repository.refreshProjects()

        val project = repository.projects.first().single()
        assertEquals("not-a-timestamp", project.updatedAt)
        assertEquals(null, project.lastScanAt)
        assertEquals("2026-04-13T07:45:15.930Z", project.lastSyncAt)
    }

    @Test
    fun `refreshProjects flattens mobile project api repo and tracker summary fields`() = runTest {
        fakeApi.projects.add(
            ProjectSummary(
                id = ProjectId("LETMOB"),
                identifier = "LETMOB",
                name = "Letta Mobile",
                repo = ProjectRepoInfo(
                    filesystemPath = "/opt/stacks/letta-mobile",
                    remoteUrl = "https://token@github.com/oculairmedia/letta-mobile.git",
                ),
                tracker = ProjectTrackerInfo(
                    summary = ProjectTrackerSummary(totalKnown = 42, ready = 5),
                    dataFreshness = ProjectDataFreshness(lastSyncAt = "1776066315930"),
                ),
            )
        )

        repository.refreshProjects()

        val project = repository.projects.first().single()
        assertEquals("/opt/stacks/letta-mobile", project.filesystemPath)
        assertEquals("https://github.com/oculairmedia/letta-mobile.git", project.gitUrl)
        assertEquals(42, project.issueCount)
        assertEquals(42, project.beadsIssueCount)
        assertEquals("2026-04-13T07:45:15.930Z", project.lastSyncAt)
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
