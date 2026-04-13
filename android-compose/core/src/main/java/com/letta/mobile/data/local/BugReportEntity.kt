package com.letta.mobile.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.letta.mobile.data.model.ProjectBugReport

@Entity(tableName = "project_bug_reports")
data class BugReportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectIdentifier: String,
    val title: String,
    val description: String,
    val severity: String,
    val tagsJson: String = "",
    val attachmentReferencesJson: String = "",
    val structuredPrompt: String,
    val createdAt: String,
) {
    fun toModel(): ProjectBugReport = ProjectBugReport(
        id = id,
        projectIdentifier = projectIdentifier,
        title = title,
        description = description,
        severity = severity,
        tags = tagsJson.split(",").map { it.trim() }.filter { it.isNotBlank() },
        attachmentReferences = attachmentReferencesJson.split("||").map { it.trim() }.filter { it.isNotBlank() },
        structuredPrompt = structuredPrompt,
        createdAt = createdAt,
    )

    companion object {
        fun fromModel(report: ProjectBugReport): BugReportEntity = BugReportEntity(
            id = report.id,
            projectIdentifier = report.projectIdentifier,
            title = report.title,
            description = report.description,
            severity = report.severity,
            tagsJson = report.tags.joinToString(","),
            attachmentReferencesJson = report.attachmentReferences.joinToString("||"),
            structuredPrompt = report.structuredPrompt,
            createdAt = report.createdAt,
        )
    }
}
