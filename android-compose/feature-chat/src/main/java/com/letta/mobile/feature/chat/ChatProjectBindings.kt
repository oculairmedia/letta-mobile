package com.letta.mobile.feature.chat

internal interface ChatProjectBindings {
    fun refreshContextWindow()

    fun loadProjectAgents()

    fun loadRecentBugReports()

    fun submitStructuredBugReport(draft: ProjectBugReportDraft)

    fun loadProjectBrief()

    fun saveProjectBriefSection(
        key: ProjectBriefSectionKey,
        content: String,
    )
}
