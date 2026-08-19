package com.letta.mobile.data.repository

import com.letta.mobile.data.local.BugReportDao
import com.letta.mobile.data.local.BugReportEntity
import com.letta.mobile.data.model.ProjectBugReport
import com.letta.mobile.data.repository.api.IBugReportRepository
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
// letta-mobile-g2ff0: bugReportDao is dagger.Lazy<BugReportDao> so Room init
// happens lazily on the first dao.get() (inside the suspend functions below),
// not on the main thread during Hilt graph resolution.
// (dagger.Lazy, not javax.inject.Provider — Hilt's KSP processor rejects
// @Provides methods returning framework types like Provider.)
class BugReportRepository @Inject constructor(
    private val bugReportDao: Lazy<BugReportDao>,
) : IBugReportRepository {
    override suspend fun logBugReport(report: ProjectBugReport): ProjectBugReport {
        val id = bugReportDao.get().insert(BugReportEntity.fromModel(report))
        return report.copy(id = id)
    }

    override suspend fun getRecentBugReports(
        projectIdentifier: String,
        limit: Int,
    ): List<ProjectBugReport> {
        return bugReportDao.get().getRecentForProject(projectIdentifier, limit).map { it.toModel() }
    }
}
