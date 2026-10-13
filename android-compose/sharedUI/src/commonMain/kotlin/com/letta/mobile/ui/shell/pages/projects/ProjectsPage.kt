package com.letta.mobile.ui.shell.pages.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.data.model.ProjectSummary
import com.letta.mobile.data.projects.ProjectDraft
import com.letta.mobile.data.projects.ProjectsPageState
import com.letta.mobile.ui.components.LettaEmptyHint
import com.letta.mobile.ui.components.LettaListRowSpec
import com.letta.mobile.ui.components.LettaMenuItem
import com.letta.mobile.ui.components.LettaRefreshAction
import com.letta.mobile.ui.components.LettaRefreshable
import com.letta.mobile.ui.components.list.RosterActions
import com.letta.mobile.ui.components.list.RosterList
import com.letta.mobile.ui.components.list.RosterListRow
import com.letta.mobile.ui.components.list.RosterListTag
import com.letta.mobile.ui.components.list.RosterOptions
import com.letta.mobile.ui.components.list.RosterSection
import com.letta.mobile.ui.components.list.RosterSections
import com.letta.mobile.ui.components.list.RosterSelection
import com.letta.mobile.ui.components.list.RosterSpec
import com.letta.mobile.ui.shell.pages.channels.ChannelsPageSlots
import com.letta.mobile.ui.theme.LettaDimens

/** Test tags of the Projects page. */
object ProjectsPageTags {
    const val PAGE = "projects-page"
    const val SEARCH = "projects-page-search"
    const val REFRESH = "projects-page-refresh"
    const val CREATE = "projects-page-create"
    const val NOTICE = "projects-page-notice"
    const val ERROR = "projects-page-error"
    const val RETRY = "projects-page-retry"
    const val DETAIL = "projects-page-detail"
    const val EDITOR = "projects-page-editor"
    val LIST = RosterListTag("projects-page-list")
}

/** How a project's chat opens: plainly, or straight into a bug report. */
enum class ProjectChatIntent { Chat, BugReport }

/** The list's actions: reload, search, open, pin, new project, and the chat a project opens. */
@Immutable
data class ProjectsListActions(
    val onRefresh: () -> Unit = {},
    val onQueryChange: (String) -> Unit = {},
    val onSelect: (String?) -> Unit = {},
    val onTogglePinned: (String) -> Unit = {},
    val onStartCreate: () -> Unit = {},
    val onDismissNotice: () -> Unit = {},
    /** Null hides the detail's chat actions (a host with no chat page). */
    val onOpenChat: ((ProjectSummary, ProjectChatIntent) -> Unit)? = null,
)

/** The open project's actions. */
@Immutable
data class ProjectDetailActions(
    val onStartEdit: (String) -> Unit = {},
    val onSync: (String) -> Unit = {},
    val onProvisionBeads: (String) -> Unit = {},
    val onArchive: (String) -> Unit = {},
    val onDelete: (String) -> Unit = {},
)

/** The create / edit form's actions. */
@Immutable
data class ProjectEditorActions(
    val onDraftChange: (ProjectDraft) -> Unit = {},
    val onSubmit: () -> Unit = {},
    val onClose: () -> Unit = {},
)

@Immutable
data class ProjectsPageActions(
    val list: ProjectsListActions = ProjectsListActions(),
    val detail: ProjectDetailActions = ProjectDetailActions(),
    val editor: ProjectEditorActions = ProjectEditorActions(),
)

/**
 * letta-mobile-ckmub.2.5 (hard cut S17): the projects catalog shared by every host (Iroh hosts
 * only: projects ride the `project.*` relay). A search and one [RosterList], pinned first; a
 * project opens its detail in an inspector, and New project / Edit open the form in a pane.
 * [modifier] carries the host frame's insets; [slots] are the host's search field and refresh.
 */
@Composable
fun ProjectsPage(
    state: ProjectsPageState,
    actions: ProjectsPageActions,
    modifier: Modifier = Modifier,
    slots: ChannelsPageSlots = ChannelsPageSlots(),
) {
    Column(modifier.fillMaxSize().testTag(ProjectsPageTags.PAGE).padding(horizontal = LettaDimens.Space.md)) {
        ProjectsHeader(state, actions.list, slots)
        state.notice?.let { ProjectsNotice(it, actions.list.onDismissNotice) }
        val error = state.error
        if (error != null && state.projects.isEmpty()) {
            ProjectsLoadError(error, actions.list.onRefresh)
        } else {
            ProjectsRoster(state, actions.list)
        }
    }
    state.selected?.let { ProjectDetailPane(it, state, actions) }
    state.editor?.let { ProjectEditorPane(it, state.knownPaths, actions.editor) }
}

@Composable
private fun ProjectsHeader(state: ProjectsPageState, actions: ProjectsListActions, slots: ChannelsPageSlots) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = LettaDimens.Space.sm),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        slots.searchField.Content(
            query = state.query,
            onQueryChange = actions.onQueryChange,
            placeholder = SEARCH_PLACEHOLDER,
            modifier = Modifier.weight(1f).testTag(ProjectsPageTags.SEARCH),
        )
        LettaRefreshAction(state.refreshing, actions.onRefresh, Modifier.testTag(ProjectsPageTags.REFRESH)) { onRefresh, modifier ->
            slots.refreshAction.Content(onRefresh, modifier)
        }
        TextButton(onClick = actions.onStartCreate, modifier = Modifier.testTag(ProjectsPageTags.CREATE)) { Text(NEW_PROJECT) }
    }
}

@Composable
private fun ColumnScope.ProjectsRoster(state: ProjectsPageState, actions: ProjectsListActions) {
    val section = RosterSection(key = "projects", items = state.visibleProjects)
    LettaRefreshable(state.refreshing, actions.onRefresh, Modifier.fillMaxWidth().weight(1f)) {
        RosterList(
            RosterSpec(
                sections = RosterSections.pinnedFirst(section) { it.identifier in state.pinnedIds },
                key = ProjectSummary::identifier,
                selection = RosterSelection(isSelected = { it.identifier == state.selectedId }, onSelect = { actions.onSelect(it.identifier) }),
                actions = RosterActions(menu = { project, _ -> listOf(pinMenuItem(project, state, actions)) }),
                options = RosterOptions(loading = state.loading && state.projects.isEmpty(), testTag = ProjectsPageTags.LIST),
                empty = { LettaEmptyHint(emptyHint(state), Modifier.padding(LettaDimens.Space.md)) },
            ),
            modifier = Modifier.fillMaxSize(),
        ) { project, row ->
            RosterListRow(
                LettaListRowSpec(
                    title = project.name,
                    subtitle = project.filesystemPath ?: project.identifier,
                    trailing = project.status,
                    emphasized = project.identifier == state.selectedId,
                ),
                row,
            )
        }
    }
}

private fun pinMenuItem(project: ProjectSummary, state: ProjectsPageState, actions: ProjectsListActions): LettaMenuItem {
    val pinned = project.identifier in state.pinnedIds
    return LettaMenuItem(label = if (pinned) UNPIN else PIN, onClick = { actions.onTogglePinned(project.identifier) })
}

private fun emptyHint(state: ProjectsPageState): String =
    if (state.query.isNotBlank()) "No projects match \"${state.query.trim()}\"" else "No projects yet"

@Composable
private fun ProjectsLoadError(message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(LettaDimens.Space.lg).testTag(ProjectsPageTags.ERROR)) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onRetry, modifier = Modifier.testTag(ProjectsPageTags.RETRY)) { Text(RETRY) }
    }
}

@Composable
private fun ProjectsNotice(message: String, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag(ProjectsPageTags.NOTICE), verticalAlignment = Alignment.CenterVertically) {
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text(DISMISS) }
    }
}

internal const val PIN = "Pin to top"
internal const val UNPIN = "Unpin from top"
private const val SEARCH_PLACEHOLDER = "Search projects"
private const val NEW_PROJECT = "New project"
private const val RETRY = "Retry"
private const val DISMISS = "Dismiss"
