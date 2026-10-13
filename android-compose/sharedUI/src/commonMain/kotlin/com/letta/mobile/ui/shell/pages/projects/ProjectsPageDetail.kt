package com.letta.mobile.ui.shell.pages.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.letta.mobile.data.model.ProjectSummary
import com.letta.mobile.data.projects.ProjectDraft
import com.letta.mobile.data.projects.ProjectEditor
import com.letta.mobile.data.projects.ProjectPathCheck
import com.letta.mobile.data.projects.ProjectsPageState
import com.letta.mobile.data.projects.isProvisioned
import com.letta.mobile.data.projects.key
import com.letta.mobile.data.settings.SettingsConfirmText
import com.letta.mobile.data.settings.SettingsRow
import com.letta.mobile.data.settings.SettingsStatus
import com.letta.mobile.data.settings.SettingsTone
import com.letta.mobile.ui.host.InspectorPane
import com.letta.mobile.ui.host.LettaPane
import com.letta.mobile.ui.host.LettaPaneKind
import com.letta.mobile.ui.settings.ActionButton
import com.letta.mobile.ui.settings.SettingsHeader
import com.letta.mobile.ui.settings.SettingsSection
import com.letta.mobile.ui.theme.LettaDimens

/** Row keys inside the project detail and the form, for tests (the settings rows tag by key). */
object ProjectDetailKeys {
    const val OPEN_CHAT = "project-open-chat"
    const val BUG_REPORT = "project-bug-report"
    const val SYNC = "project-sync"
    const val PROVISION = "project-provision"
    const val PIN = "project-pin"
    const val EDIT = "project-edit"
    const val ARCHIVE = "project-archive"
    const val DELETE = "project-delete"
    const val FIELD_NAME = "project-field-name"
    const val FIELD_PATH = "project-field-path"
    const val FIELD_GIT = "project-field-git"
    const val SAVE = "project-save"
    const val CANCEL = "project-cancel"
}

/** The open project: its facts, then what can be done with it. */
@Composable
internal fun ProjectDetailPane(project: ProjectSummary, state: ProjectsPageState, actions: ProjectsPageActions) {
    InspectorPane(title = project.name, onClose = { actions.list.onSelect(null) }, testTag = ProjectsPageTags.DETAIL) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(LettaDimens.Space.lg),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
        ) {
            SettingsSection(SettingsHeader("project-facts", "Project")) {
                projectFacts(project, state).forEach { InfoRow(it) }
            }
            SettingsSection(SettingsHeader("project-actions", "Actions")) {
                projectActions(project, state, actions).forEach { LinkRow(it) }
            }
        }
    }
}

/** The project's facts as rows, leaving out what the host did not report. */
internal fun projectFacts(project: ProjectSummary, state: ProjectsPageState): List<SettingsRow.Info> {
    val beads = state.beadsOf(project)
    return listOfNotNull(
        SettingsRow.Info("project-id", "Identifier", project.identifier),
        project.filesystemPath?.let { SettingsRow.Info("project-path", "Path", it) },
        project.gitUrl?.let { SettingsRow.Info("project-git", "Git", it) },
        project.status?.let { SettingsRow.Info("project-status", "Status", it) },
        project.lastSyncAt?.let { SettingsRow.Info("project-synced", "Last sync", it) },
        beads?.let { SettingsRow.Info("project-beads", "Beads remote", it.status, if (it.isProvisioned) SettingsTone.Positive else SettingsTone.Normal) },
    )
}

/** What the open project offers, in order: chat, sync, Beads, pin, edit, archive, delete. */
internal fun projectActions(project: ProjectSummary, state: ProjectsPageState, actions: ProjectsPageActions): List<SettingsRow.Link> {
    val id = project.key
    val pinned = state.isPinned(project)
    val beads = state.beadsOf(project)
    return projectChatActions(project, actions.list) + listOfNotNull(
        SettingsRow.Link(ProjectDetailKeys.SYNC, "Sync now", enabled = state.syncing != id, value = if (state.syncing == id) "Syncing…" else null) {
            actions.detail.onSync(id)
        },
        SettingsRow.Link(
            ProjectDetailKeys.PROVISION,
            "Provision Beads remote",
            supporting = beads?.error,
            enabled = state.provisioning != id,
            confirm = SettingsConfirmText("Provision Beads remote?", "This creates the remote backing store for ${project.name}.", "Provision"),
        ) { actions.detail.onProvisionBeads(id) }.takeUnless { beads?.isProvisioned == true },
        SettingsRow.Link(ProjectDetailKeys.PIN, if (pinned) UNPIN else PIN) { actions.list.onTogglePinned(id) },
        SettingsRow.Link(ProjectDetailKeys.EDIT, "Edit settings", supporting = "Path and git URL") { actions.detail.onStartEdit(id) },
        SettingsRow.Link(
            ProjectDetailKeys.ARCHIVE,
            "Archive",
            confirm = SettingsConfirmText("Archive project?", "${project.name} leaves the active list.", "Archive"),
        ) { actions.detail.onArchive(id) },
        SettingsRow.Link(
            ProjectDetailKeys.DELETE,
            "Delete",
            destructive = true,
            confirm = SettingsConfirmText("Delete project?", "${project.name} is removed from the host. This cannot be undone.", "Delete"),
        ) { actions.detail.onDelete(id) },
    )
}

private fun projectChatActions(project: ProjectSummary, actions: ProjectsListActions): List<SettingsRow.Link> {
    val openChat = actions.onOpenChat ?: return emptyList()
    if (project.lettaAgentId?.value.isNullOrBlank()) {
        return listOf(SettingsRow.Link(ProjectDetailKeys.OPEN_CHAT, "Open chat", supporting = "This project has no agent yet.", enabled = false) {})
    }
    return listOf(
        SettingsRow.Link(ProjectDetailKeys.OPEN_CHAT, "Open chat") { openChat(project, ProjectChatIntent.Chat) },
        SettingsRow.Link(ProjectDetailKeys.BUG_REPORT, "Report a bug") { openChat(project, ProjectChatIntent.BugReport) },
    )
}

/** The create / edit form: a sheet on touch, a dialog on a pointer. */
@Composable
internal fun ProjectEditorPane(editor: ProjectEditor, knownPaths: List<String>, actions: ProjectEditorActions) {
    val draft = editor.draft
    LettaPane(LettaPaneKind.Modal, onDismiss = actions.onClose, title = if (editor.creating) "New project" else "Project settings", testTag = ProjectsPageTags.EDITOR) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(LettaDimens.Space.lg)) {
            SettingsSection(SettingsHeader("project-form", draft.name.ifBlank { "Project" }, showTitle = !editor.creating)) {
                if (editor.creating) {
                    TextFieldRow(SettingsRow.Text(ProjectDetailKeys.FIELD_NAME, "Name", placeholder = "My project"), draft.name) {
                        actions.onDraftChange(draft.copy(name = it))
                    }
                }
                TextFieldRow(pathRow(knownPaths), draft.filesystemPath) { actions.onDraftChange(draft.copy(filesystemPath = it)) }
                TextFieldRow(SettingsRow.Text(ProjectDetailKeys.FIELD_GIT, "Git URL", placeholder = "Optional"), draft.gitUrl) {
                    actions.onDraftChange(draft.copy(gitUrl = it))
                }
                if (editor.submitting) NoteRow(SettingsRow.Note("project-saving", SettingsStatus("Saving…")))
                ActionRow(
                    "project-form-actions",
                    listOf(
                        ActionButton(ProjectDetailKeys.CANCEL, "Cancel", actions.onClose),
                        ActionButton(
                            ProjectDetailKeys.SAVE,
                            if (editor.creating) "Create" else "Save",
                            actions.onSubmit,
                            primary = true,
                            enabled = !editor.submitting && draft.normalized().isReady(editor.creating),
                        ),
                    ),
                )
            }
        }
    }
}

private fun pathRow(knownPaths: List<String>) = SettingsRow.Text(
    key = ProjectDetailKeys.FIELD_PATH,
    title = "Path on the host",
    placeholder = knownPaths.firstOrNull() ?: "/home/me/project",
    supporting = "An absolute path the host can reach.",
    problem = { value -> pathProblem(ProjectDraft(filesystemPath = value).pathCheck) },
)

private fun pathProblem(check: ProjectPathCheck): String? = when (check) {
    ProjectPathCheck.MustBeAbsolute -> "Use an absolute path (starting with /)."
    ProjectPathCheck.Missing, ProjectPathCheck.Valid -> null
}
