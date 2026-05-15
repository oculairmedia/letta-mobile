package com.letta.mobile.ui.screens.chat

internal interface ChatProjectBindings {
    fun refreshClientModeLocation()

    fun sendClientModeLocationChange(path: String)

    fun openClientModeLocationPicker()

    fun closeClientModeLocationPicker()

    fun browseClientModeLocation(path: String?)

    fun selectClientModeLocation(path: String)

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
