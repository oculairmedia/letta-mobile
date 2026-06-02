package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.feature.chat.render.ProjectBriefSectionKey
import com.letta.mobile.feature.chat.render.ProjectBugReportDraft

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
