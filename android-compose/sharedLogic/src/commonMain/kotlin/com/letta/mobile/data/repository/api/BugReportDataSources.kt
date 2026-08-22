package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.ProjectBugReport

/**
 * Room-backed bug report persistence seam for
 * [com.letta.mobile.data.repository.CachedBugReportRepository].
 *
 * Production impl: [com.letta.mobile.data.local.RoomBugReportLocalStore].
 */
interface BugReportLocalStore {
    suspend fun insert(report: ProjectBugReport): Long

    suspend fun getRecentForProject(projectIdentifier: String, limit: Int): List<ProjectBugReport>
}
