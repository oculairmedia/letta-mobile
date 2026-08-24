package com.letta.mobile.data.repository

import com.letta.mobile.data.model.ProjectIssueDetail
import com.letta.mobile.data.model.ProjectIssueListParams
import com.letta.mobile.data.model.ProjectIssueSummary
import com.letta.mobile.testutil.FakeProjectWorkApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class ProjectWorkRepositoryTest {
    private lateinit var fakeApi: FakeProjectWorkApi
    private lateinit var repository: ProjectWorkRepository

    @Before
    fun setup() {
        fakeApi = FakeProjectWorkApi()
        repository = ProjectWorkRepository(fakeApi)
    }

    @Test
    fun `refreshReadyWork caches ready issues by project`() = runTest {
        fakeApi.readyWork["letta-mobile"] = listOf(issue("letta-mobile-qmbg"))

        val result = repository.refreshReadyWork("letta-mobile")

        assertEquals(listOf("letta-mobile-qmbg"), result.map { it.id })
        assertEquals(result, repository.readyWorkByProject.first()["letta-mobile"])
        assertTrue(fakeApi.calls.contains("getReadyWork:letta-mobile:null:null"))
    }

    @Test
    fun `refreshIssues sends filters and caches issues by project`() = runTest {
        fakeApi.issues["letta-mobile"] = listOf(issue("letta-mobile-m1sq", ready = false))

        val result = repository.refreshIssues(
            projectId = "letta-mobile",
            params = ProjectIssueListParams(status = "open", ready = false, limit = 20),
        )

        assertEquals(listOf("letta-mobile-m1sq"), result.map { it.id })
        assertEquals(result, repository.issuesByProject.first()["letta-mobile"])
        assertTrue(fakeApi.calls.contains("listIssues:letta-mobile:false:open:20:null"))
    }

    @Test
    fun `refreshIssuePage appends cursor pages without replacing previous issues`() = runTest {
        fakeApi.issues["letta-mobile"] = listOf(
            issue("letta-mobile-1"),
            issue("letta-mobile-2"),
            issue("letta-mobile-3"),
        )

        val firstPage = repository.refreshIssuePage(
            projectId = "letta-mobile",
            params = ProjectIssueListParams(limit = 2, sort = "updated_desc"),
        )
        val secondPage = repository.refreshIssuePage(
            projectId = "letta-mobile",
            params = ProjectIssueListParams(limit = 2, cursor = firstPage.page.nextCursor, sort = "updated_desc"),
        )

        assertEquals(listOf("letta-mobile-1", "letta-mobile-2"), firstPage.items.map { it.id })
        assertEquals(listOf("letta-mobile-3"), secondPage.items.map { it.id })
        assertEquals(
            listOf("letta-mobile-1", "letta-mobile-2", "letta-mobile-3"),
            repository.issuesByProject.first()["letta-mobile"]?.map { it.id },
        )
        assertEquals(false, secondPage.page.hasMore)
    }

    @Test
    fun `getIssue caches detail unless force refresh is requested`() = runTest {
        fakeApi.issueDetails["letta-mobile-qmbg"] = detail("First")

        assertEquals("First", repository.getIssue("letta-mobile-qmbg").title)
        fakeApi.issueDetails["letta-mobile-qmbg"] = detail("Second")

        assertEquals("First", repository.getIssue("letta-mobile-qmbg").title)
        assertEquals("Second", repository.getIssue("letta-mobile-qmbg", forceRefresh = true).title)
    }

    @Test
    fun `invalidateProjectCache evicts details owned by project even when absent from cached pages`() = runTest {
        fakeApi.issueDetails["letta-mobile-qmbg"] = detail("Before")
        assertEquals("Before", repository.getIssue("letta-mobile-qmbg").title)

        fakeApi.issueDetails["letta-mobile-qmbg"] = detail("After")
        repository.invalidateProjectCache("letta-mobile")

        assertEquals("After", repository.getIssue("letta-mobile-qmbg").title)
    }

    @Test
    fun `claimIssue sends provided concurrency headers and updates cached lists`() = runTest {
        fakeApi.readyWork["letta-mobile"] = listOf(issue("letta-mobile-qmbg"))
        fakeApi.issues["letta-mobile"] = listOf(issue("letta-mobile-qmbg"))
        repository.refreshReadyWork("letta-mobile")
        repository.refreshIssues("letta-mobile")

        val updated = repository.claimIssue(
            issueId = "letta-mobile-qmbg",
            assignee = "emmanuel",
            ifMatch = "letta-mobile-qmbg:1",
            idempotencyKey = "android-queue-42",
        )

        assertEquals("emmanuel", updated.assignee)
        assertEquals("letta-mobile-qmbg:1", fakeApi.mutationHeaders.single().ifMatch)
        assertEquals("android-queue-42", fakeApi.mutationHeaders.single().idempotencyKey)
        assertEquals("emmanuel", repository.readyWorkByProject.first()["letta-mobile"]?.single()?.assignee)
        assertEquals("emmanuel", repository.issuesByProject.first()["letta-mobile"]?.single()?.assignee)
    }

    private fun issue(id: String, ready: Boolean = true) = ProjectIssueSummary(
        id = id,
        projectId = "letta-mobile",
        provider = "beads",
        title = "Issue $id",
        type = "task",
        priority = "high",
        status = "open",
        ready = ready,
        etag = "$id:1",
    )

    private fun detail(title: String) = ProjectIssueDetail(
        id = "letta-mobile-qmbg",
        projectId = "letta-mobile",
        title = title,
        status = "open",
        description = "Description",
    )
}
