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
    /** False when the host can't remove it (letta-code only unlinks symlinked skills); remove is then hidden. */
    val removable: Boolean = true,
)
