package com.letta.mobile.data.projects

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.model.BeadsRemoteStatus
import com.letta.mobile.data.model.ProjectSummary

/** How a project's filesystem path reads: the host needs an absolute path it can reach. */
enum class ProjectPathCheck { Missing, MustBeAbsolute, Valid }

/** The create / edit form. Editing keeps the project's name; only path and git URL change. */
@Immutable
data class ProjectDraft(
    val name: String = "",
    val filesystemPath: String = "",
    val gitUrl: String = "",
) {
    val pathCheck: ProjectPathCheck
        get() = when {
            filesystemPath.isBlank() -> ProjectPathCheck.Missing
            !filesystemPath.trim().startsWith("/") -> ProjectPathCheck.MustBeAbsolute
            else -> ProjectPathCheck.Valid
        }

    /** The git URL to send: none when the field is blank. */
    val gitUrlOrNull: String? get() = gitUrl.takeIf { it.isNotBlank() }

    fun normalized(): ProjectDraft = ProjectDraft(name.trim(), filesystemPath.trim(), gitUrl.trim())

    /** Ready to send: a new project needs a name, both need a valid path. */
    fun isReady(creating: Boolean): Boolean = pathCheck == ProjectPathCheck.Valid && (!creating || name.isNotBlank())

    companion object {
        fun of(project: ProjectSummary): ProjectDraft =
            ProjectDraft(project.name, project.filesystemPath.orEmpty(), project.gitUrl.orEmpty())
    }
}

/** The open form: a new project, or the settings of [target]. */
@Immutable
data class ProjectEditor(
    val draft: ProjectDraft = ProjectDraft(),
    val target: ProjectKey? = null,
    val submitting: Boolean = false,
) {
    val creating: Boolean get() = target == null
}

/**
 *  (hard cut S17): what the shared Projects page shows. Projects reach the
 * client only over an Iroh host's `project.*` relay; [error] is a list that could not load at all,
 * [notice] the outcome of the last action (created, archived, a failed sync).
 */
@Immutable
data class ProjectsPageState(
    val projects: List<ProjectSummary> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val query: String = "",
    val pinned: Set<ProjectKey> = emptySet(),
    val selectedKey: ProjectKey? = null,
    val beadsStatus: Map<ProjectKey, BeadsRemoteStatus> = emptyMap(),
    val syncing: ProjectKey? = null,
    val provisioning: ProjectKey? = null,
    val editor: ProjectEditor? = null,
    val notice: String? = null,
) {
    /** The projects matching [query] (name, identifier, path), pinned first then the most recent. */
    val visibleProjects: List<ProjectSummary> get() = ProjectOrdering.visible(this)

    val selected: ProjectSummary? get() = selectedKey?.let(::projectOf)

    val refreshing: Boolean get() = loading && projects.isNotEmpty()

    /** Paths of the known projects: what a new project's path field suggests. */
    val knownPaths: List<String>
        get() = projects.mapNotNull { it.filesystemPath?.trim()?.takeIf(String::isNotEmpty) }.distinct().sorted()

    fun projectOf(key: ProjectKey): ProjectSummary? = projects.firstOrNull { it.key == key }

    fun isPinned(project: ProjectSummary): Boolean = project.key in pinned

    /** The Beads remote status read for [project], else the one its summary carried. */
    fun beadsOf(project: ProjectSummary): BeadsRemoteStatus? = beadsStatus[project.key] ?: project.beadsRemote
}

/** The page's order and search. */
object ProjectOrdering {
    fun visible(state: ProjectsPageState): List<ProjectSummary> {
        val needle = state.query.trim().lowercase()
        val matching = if (needle.isEmpty()) state.projects else state.projects.filter { it.matches(needle) }
        return matching.sortedWith(
            compareByDescending<ProjectSummary> { state.isPinned(it) }
                .thenByDescending { it.lastActivity() }
                .thenBy { it.name.lowercase() },
        )
    }

    private fun ProjectSummary.matches(needle: String): Boolean =
        listOfNotNull(name, identifier, filesystemPath, description).any { it.lowercase().contains(needle) }
}

/** The newest of the project's activity stamps (ISO strings sort by time). */
fun ProjectSummary.lastActivity(): String = lastActivityAt ?: updatedAt ?: lastSyncAt ?: lastCheckedAt ?: lastScanAt ?: ""

/** A project's Beads remote is set up. */
val BeadsRemoteStatus.isProvisioned: Boolean get() = status == PROVISIONED

private const val PROVISIONED = "provisioned"
