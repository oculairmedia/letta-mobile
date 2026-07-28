package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Skill admin handlers.
 *
 * Phase 2 semantic model (native App Server v2):
 * - Enable/disable is filesystem-scoped (`skill_enable` / `skill_disable`), not
 *   the retired shim agent-scoped install REST.
 * - Listings are projections from [skillsListing] (device-status /
 *   `skills_updated`); there is no upstream `skill_list` command.
 * - Optional `agent_id` only drives runtime invalidation after mutation.
 */
object SkillAdminHandlers {
    fun register(
        router: AdminRpcRouter,
        @Suppress("UNUSED_PARAMETER") adminBaseUrl: String? = null,
        nativeClient: AppServerClient? = null,
        controller: AppServerController? = null,
        skillsListing: SkillsListingSource? = null,
    ) {
        router.register("skill.list") {
            listSkills(skillsListing)
        }
        router.register("skill.list_agent") { params ->
            // Native skills are process-global; agent_id is accepted for API
            // compatibility but does not filter until upstream adds agent scope.
            params.requireParam(AdminParamKey("agent_id"))
            listSkills(skillsListing)
        }
        router.register("skill.install") { params ->
            val skillPath = resolveSkillPath(params)
                ?: adminError(
                    "capability_unavailable: skill.install requires skill_path " +
                        "(or name) for App Server v2 skill_enable; agent-scoped shim install is retired",
                )
            val result = NativeAdmin.require(nativeClient, "skill.install") { c ->
                val response = c.skillEnable(
                    AppServerCommand.SkillEnable(
                        requestId = NativeAdmin.requestId(),
                        skillPath = skillPath,
                    ),
                )
                if (response.success) {
                    buildJsonObject {
                        put("enabled", true)
                        response.skillName?.let { put("skill_name", it) }
                    }
                } else {
                    null
                }
            }
            maybeInvalidateAfterSkillMutation(controller, params)
            result
        }
        router.register("skill.uninstall") { params ->
            val skillName = params.requireParam(AdminParamKey("name"))
            val result = NativeAdmin.require(nativeClient, "skill.uninstall") { c ->
                val response = c.skillDisable(
                    AppServerCommand.SkillDisable(
                        requestId = NativeAdmin.requestId(),
                        name = skillName,
                    ),
                )
                if (response.success) buildJsonObject { put("disabled", true) } else null
            }
            maybeInvalidateAfterSkillMutation(controller, params)
            result
        }
    }

    private fun listSkills(skillsListing: SkillsListingSource?): JsonObjectEnvelope {
        val skills = skillsListing?.currentSkills() ?: JsonArray(emptyList())
        return buildJsonObject { put("skills", skills) }
    }

    private fun resolveSkillPath(params: kotlinx.serialization.json.JsonObject?): String? {
        param(params, AdminParamKey("skill_path"))?.takeIf { it.isNotBlank() }?.let { return it }
        return param(params, AdminParamKey("name"))?.takeIf { it.isNotBlank() }
    }

    private suspend fun maybeInvalidateAfterSkillMutation(
        controller: AppServerController?,
        params: kotlinx.serialization.json.JsonObject?,
    ) {
        if (!RuntimeInvalidationPolicy.skillMutationRequiresRestart()) return
        val agentId = param(params, AdminParamKey("agent_id")) ?: return
        controller?.stopRuntime(AgentId(agentId))
    }
}

private typealias JsonObjectEnvelope = kotlinx.serialization.json.JsonObject
