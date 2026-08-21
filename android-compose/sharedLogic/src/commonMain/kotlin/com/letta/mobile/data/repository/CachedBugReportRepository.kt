package com.letta.mobile.data.repository

import com.letta.mobile.data.model.ProjectBugReport
import com.letta.mobile.data.repository.api.BugReportLocalStore
import com.letta.mobile.data.repository.api.IBugReportRepository

/** Phase 5o: platform-neutral bug report repository backed by [BugReportLocalStore]. */
open class CachedBugReportRepository(
    private val localStore: BugReportLocalStore,
) : IBugReportRepository {
    override suspend fun logBugReport(report: ProjectBugReport): ProjectBugReport {
        val id = localStore.insert(report)
        return report.copy(id = id)
    }

    override suspend fun getRecentBugReports(
        projectIdentifier: String,
        limit: Int,
    ): List<ProjectBugReport> = localStore.getRecentForProject(projectIdentifier, limit)
}
