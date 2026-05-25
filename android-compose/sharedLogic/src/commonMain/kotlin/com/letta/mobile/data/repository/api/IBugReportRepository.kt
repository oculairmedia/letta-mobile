package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.ProjectBugReport

interface IBugReportRepository {
    suspend fun logBugReport(report: ProjectBugReport): ProjectBugReport
    suspend fun getRecentBugReports(projectIdentifier: String, limit: Int = 5): List<ProjectBugReport>
}
