package com.letta.mobile.data.model

data class ProjectBugReport(
    val id: Long = 0,
    val projectIdentifier: String,
    val title: String,
    val description: String,
    val severity: String,
    val tags: List<String> = emptyList(),
    val attachmentReferences: List<String> = emptyList(),
    val structuredPrompt: String,
    val createdAt: String,
)
