package com.letta.mobile.data.repository

import com.letta.mobile.data.local.BugReportDao
import com.letta.mobile.data.local.BugReportEntity
import com.letta.mobile.data.model.ProjectBugReport
import com.letta.mobile.data.repository.api.IBugReportRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BugReportRepository @Inject constructor(
    private val bugReportDao: BugReportDao,
) : IBugReportRepository {
    override suspend fun logBugReport(report: ProjectBugReport): ProjectBugReport {
        val id = bugReportDao.insert(BugReportEntity.fromModel(report))
        return report.copy(id = id)
    }

    override suspend fun getRecentBugReports(
        projectIdentifier: String,
        limit: Int,
    ): List<ProjectBugReport> {
        return bugReportDao.getRecentForProject(projectIdentifier, limit).map { it.toModel() }
    }
}
