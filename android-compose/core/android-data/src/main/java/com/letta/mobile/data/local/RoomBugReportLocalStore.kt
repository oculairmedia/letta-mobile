package com.letta.mobile.data.local

import com.letta.mobile.data.model.ProjectBugReport
import com.letta.mobile.data.repository.api.BugReportLocalStore
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed [BugReportLocalStore]. Phase 5o — Android persistence seam only.
 *
 * [bugReportDao] is [dagger.Lazy] so Room init happens lazily on the first
 * dao.get(), not on the main thread during Hilt graph resolution.
 */
@Singleton
class RoomBugReportLocalStore @Inject constructor(
    private val bugReportDao: Lazy<BugReportDao>,
) : BugReportLocalStore {
    override suspend fun insert(report: ProjectBugReport): Long =
        bugReportDao.get().insert(BugReportEntity.fromModel(report))

    override suspend fun getRecentForProject(projectIdentifier: String, limit: Int): List<ProjectBugReport> =
        bugReportDao.get().getRecentForProject(projectIdentifier, limit).map { it.toModel() }
}
