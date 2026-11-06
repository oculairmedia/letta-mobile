package com.letta.mobile.data.projects

import com.letta.mobile.data.model.BeadsRemoteProvisionResponse
import com.letta.mobile.data.model.BeadsRemoteStatus
import com.letta.mobile.data.model.ProjectSummary
import com.letta.mobile.data.objectverbs.ObjectRef
import com.letta.mobile.data.objectverbs.VerbOutcome
import com.letta.mobile.data.objectverbs.binding.DevicePins
import com.letta.mobile.data.pins.LegacyPinSources
import com.letta.mobile.data.repository.api.IProjectRepository
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.storage.SecureSettingsStore
import kotlin.jvm.JvmInline
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/** A project's identifier on the host (`project.*`'s `identifier`). */
@JvmInline
value class ProjectKey(val value: String)

/** The project's [ProjectKey]. */
val ProjectSummary.key: ProjectKey get() = ProjectKey(identifier)

/** How a reload treats the cached catalog. */
enum class ProjectReload { Force, IfStale }

/** Whether a project is pinned to the top of the catalog. */
enum class ProjectPin { Pinned, Unpinned }

/**
 * The project operations the Projects page needs, in its own terms (typed keys, the form's draft).
 * [RepositoryProjectsBackend] adapts the session's [IProjectRepository]; tests supply their own.
 */
interface ProjectsBackend {
    val projects: StateFlow<List<ProjectSummary>>

    suspend fun reload(mode: ProjectReload)

    suspend fun beadsStatus(key: ProjectKey): BeadsRemoteStatus

    suspend fun provisionBeads(key: ProjectKey): BeadsRemoteProvisionResponse


    /** Creates a project from [draft] (name, path, optional git URL). */
    suspend fun create(draft: ProjectDraft): ProjectSummary

    /** Saves [draft]'s path and git URL on [key]. */
    suspend fun update(key: ProjectKey, draft: ProjectDraft): ProjectSummary

    suspend fun archive(key: ProjectKey): ProjectSummary

    suspend fun delete(key: ProjectKey)
}

/** The session's project repository as a [ProjectsBackend]; a reload within 60 s of the last is skipped. */
class RepositoryProjectsBackend(private val repository: IProjectRepository) : ProjectsBackend {
    override val projects: StateFlow<List<ProjectSummary>> get() = repository.projects

    override suspend fun reload(mode: ProjectReload) {
        when (mode) {
            ProjectReload.Force -> repository.refreshProjects()
            ProjectReload.IfStale -> repository.refreshProjectsIfStale(maxAgeMs = FRESH_FOR_MS)
        }
    }

    override suspend fun beadsStatus(key: ProjectKey): BeadsRemoteStatus = repository.getBeadsRemoteStatus(key.value)

    override suspend fun provisionBeads(key: ProjectKey): BeadsRemoteProvisionResponse =
        repository.provisionBeadsRemote(key.value, push = true)


    override suspend fun create(draft: ProjectDraft): ProjectSummary =
        repository.createProject(name = draft.name, filesystemPath = draft.filesystemPath, gitUrl = draft.gitUrlOrNull)

    override suspend fun update(key: ProjectKey, draft: ProjectDraft): ProjectSummary =
        repository.updateProject(identifier = key.value, filesystemPath = draft.filesystemPath, gitUrl = draft.gitUrlOrNull)

    override suspend fun archive(key: ProjectKey): ProjectSummary = repository.archiveProject(key.value)

    override suspend fun delete(key: ProjectKey) = repository.deleteProject(key.value)

    private companion object {
        const val FRESH_FOR_MS = 60_000L
    }
}

/** Where the user's pinned projects are kept (Android: the settings repository). */
interface ProjectPinStore {
    val pinned: Flow<Set<ProjectKey>>

    suspend fun set(key: ProjectKey, pin: ProjectPin)
}

/** The settings repository's project pins as a [ProjectPinStore]. */
class SettingsProjectPinStore(private val settings: ISettingsRepository) : ProjectPinStore {
    override val pinned: Flow<Set<ProjectKey>>
        get() = settings.getPinnedProjectIds().map { ids -> ids.mapTo(mutableSetOf(), ::ProjectKey) }

    override suspend fun set(key: ProjectKey, pin: ProjectPin) = settings.setProjectPinned(key.value, pin == ProjectPin.Pinned)
}

/**
 * Project pins in a [SecureSettingsStore] under [storageKey]: the desktop,
 * which has no Android settings repository, keeps them beside its other preferences.
 */
class SettingsStoreProjectPinStore(
    store: SecureSettingsStore,
    storageKey: String = DEFAULT_STORAGE_KEY,
    source: StateFlow<String?> = MutableStateFlow(null),
) : ProjectPinStore {
    val binding = DevicePins.bind(store, source)
    init {
        binding.store.tryImportLegacy(LegacyPinSources.secure(store, setOf(DEFAULT_STORAGE_KEY, storageKey)))
    }
    override val pinned: Flow<Set<ProjectKey>> = binding.ids("project").map { ids -> ids.mapTo(mutableSetOf(), ::ProjectKey) }

    override suspend fun set(key: ProjectKey, pin: ProjectPin) {
        val result = binding.setPinned(ObjectRef("project", key.value), pin == ProjectPin.Pinned)
        check(result is VerbOutcome.Updated) { "Pin update failed" }
    }

    companion object { const val DEFAULT_STORAGE_KEY = "projects.pinned" }
}
