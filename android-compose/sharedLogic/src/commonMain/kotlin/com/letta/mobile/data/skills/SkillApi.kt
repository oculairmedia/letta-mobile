package com.letta.mobile.data.skills

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Platform-neutral skills API: the skill registry, what an agent has installed, and install /
 * uninstall. Served over an Iroh host's `admin_rpc` relay (`skill.*`, see
 * [com.letta.mobile.data.repository.iroh.IrohSkillsApi]); letta-mobile-ckmub.4 removed the Letta
 * REST implementation (`/v1/skills`).
 */
interface SkillsApi : AutoCloseable {
    suspend fun listSkills(): List<Skill>
    suspend fun listAgentSkills(agentId: String): List<Skill>
    suspend fun installSkill(agentId: String, skillName: String)
    suspend fun uninstallSkill(agentId: String, skillName: String)
    override fun close() = Unit
}

@Serializable
data class Skill(
    val name: String,
    val version: String? = null,
    val description: String? = null,
    val author: String? = null,
    @SerialName("installed_count") val installedCount: Int? = null,
    val tags: List<String> = emptyList(),
    /** Where letta-code discovered it: `global`, `bundled`, `agent`, `project` (see [SkillSourceLabels]). */
    val source: String? = null,
    /**
     * True only when the host found it as a symlink in letta-code's skills folder, the one thing
     * `skill_disable` can undo. Absent means read-only: the page never offers a control it can't honour.
     */
    val controllable: Boolean = false,
)

/** Plain wording for a read-only skill's source on the Skills page. */
object SkillSourceLabels {
    fun label(source: String?): String = when (source) {
        "global" -> "Global folder"
        "bundled" -> "Built-in"
        "agent" -> "Agent memory"
        "project" -> "Project"
        else -> "Host"
    }

    /** Why the skill has no toggle, in one line. */
    fun readOnlyHint(source: String?): String = when (source) {
        "global" -> "Real folder in the skills directory: manage it on disk."
        "bundled" -> "Built into letta-code; it can't be turned off from here."
        "agent" -> "Lives in the agent's memory; edit it there."
        "project" -> "Comes from the project's skills folder; manage it on disk."
        else -> "Managed on the host; it can't be changed from here."
    }
}
