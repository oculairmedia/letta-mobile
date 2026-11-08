package com.letta.mobile.data.skills

import androidx.compose.runtime.Immutable
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The skills half of the shared Skills page: the server registry, what
 * [agentId] has installed, and whether a load is in flight or failed.
 */
@Immutable
data class SkillsCatalogState(
    val skills: List<Skill> = emptyList(),
    val installedSkillNames: Set<String> = emptySet(),
    val agentId: String? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    /** A failed install / remove, naming the skill; shown apart from [errorMessage] so the list stays. */
    val actionError: String? = null,
    /** False when the active backend exposes no skill API: install / remove are hidden. */
    val available: Boolean = true,
) {
    /** Install / remove need both a skill API and an agent to install onto. */
    val canManage: Boolean get() = available && agentId != null
}

/**
 * Loads the skill registry and one agent's installed set through [api], and installs / removes
 * skills for that agent, reloading after each change. Every host binds this one controller; with a
 * null [api] it stays empty and reports itself unavailable.
 */
class SkillsCatalogController(
    private val api: SkillsApi?,
    private val scope: CoroutineScope,
    agentId: String? = null,
) : AutoCloseable {
    private val stateFlow = MutableStateFlow(SkillsCatalogState(agentId = agentId, available = api != null))
    val state: StateFlow<SkillsCatalogState> = stateFlow.asStateFlow()

    private var loadJob: Job? = null
    private val loadRevision = atomic(0L)

    /** Loads [agentId]'s catalog (the current agent when null is not passed explicitly). */
    fun reload(agentId: String? = stateFlow.value.agentId) {
        val skillsApi = api ?: return
        val revision = loadRevision.incrementAndGet()
        loadJob?.cancel()
        stateFlow.update { current ->
            current.copy(
                agentId = agentId,
                installedSkillNames = current.installedSkillNames.takeIf { current.agentId == agentId }.orEmpty(),
                isLoading = true,
                errorMessage = null,
            )
        }
        loadJob = scope.launch {
            try {
                val registry = skillsApi.listSkills()
                if (loadRevision.value != revision) return@launch
                val installed = agentId?.let { id -> skillsApi.listAgentSkills(id).map { it.name }.toSet() }.orEmpty()
                if (loadRevision.value != revision) return@launch
                stateFlow.update { it.copy(skills = registry, installedSkillNames = installed, isLoading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (loadRevision.value == revision) stateFlow.update { it.copy(isLoading = false, errorMessage = e.message ?: LOAD_FAILED) }
            }
        }
    }

    fun install(skillName: String) = mutate(skillName, "install") { api, agent -> api.installSkill(agent, skillName) }

    fun uninstall(skillName: String) = mutate(skillName, "remove") { api, agent -> api.uninstallSkill(agent, skillName) }

    fun dismissActionError() = stateFlow.update { it.copy(actionError = null) }

    private fun mutate(skillName: String, verb: String, action: suspend (SkillsApi, String) -> Unit) {
        val skillsApi = api ?: return
        val agent = stateFlow.value.agentId ?: return
        if (skillName.isBlank()) return
        stateFlow.update { it.copy(actionError = null) }
        scope.launch {
            try {
                action(skillsApi, agent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = e.message?.let { ": $it" } ?: "."
                stateFlow.update {
                    if (it.agentId == agent) it.copy(actionError = "Couldn't $verb $skillName$reason") else it
                }
                return@launch
            }
            reload()
        }
    }

    override fun close() {
        loadRevision.incrementAndGet()
        loadJob?.cancel()
    }

    private companion object {
        const val LOAD_FAILED = "Could not load skills."
    }
}

/** The Skills page's search: one query narrows skills and tools alike. */
object SkillsPageFilter {
    /**
     * The registry plus any installed skill the registry does not list (so an agent's own skills
     * never vanish), narrowed to the installed set when [assignedOnly], then by [query] over the
     * name, description and tags.
     */
    fun skills(
        registry: List<Skill>,
        installedNames: Set<String>,
        query: String,
        assignedOnly: Boolean = false,
    ): List<Skill> {
        val known = registry.mapTo(HashSet()) { it.name }
        val all = registry + installedNames.filter { it !in known }.sorted().map { Skill(name = it) }
        val base = if (assignedOnly) all.filter { it.name in installedNames } else all
        val q = query.trim()
        if (q.isEmpty()) return base
        return base.filter { skill ->
            skill.name.contains(q, ignoreCase = true) ||
                skill.description?.contains(q, ignoreCase = true) == true ||
                skill.tags.any { it.contains(q, ignoreCase = true) }
        }
    }
}
