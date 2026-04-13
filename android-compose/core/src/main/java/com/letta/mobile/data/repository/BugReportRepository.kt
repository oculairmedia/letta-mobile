package com.letta.mobile.data.repository

import com.letta.mobile.data.local.BugReportDao
import com.letta.mobile.data.local.BugReportEntity
import com.letta.mobile.data.model.ProjectBugReport
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BugReportRepository @Inject constructor(
    private val bugReportDao: BugReportDao,
) {
    suspend fun logBugReport(report: ProjectBugReport): ProjectBugReport {
        val id = bugReportDao.insert(BugReportEntity.fromModel(report))
        return report.copy(id = id)
    }

    suspend fun getRecentBugReports(
        projectIdentifier: String,
        limit: Int = 5,
    ): List<ProjectBugReport> {
        return bugReportDao.getRecentForProject(projectIdentifier, limit).map { it.toModel() }
    }
}
