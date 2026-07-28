package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.AppServerController
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
 * - Skill enable/disable is process-global; after mutation every cached runtime
 *   is evicted so agents reseed their toolset on the next turn.
 */
object SkillAdminHandlers {
    fun register(
        router: AdminRpcRouter,
        nativeClient: AppServerClient? = null,
        controller: AppServerController? = null,
        skillsListing: SkillsListingSource? = null,
    ) {
        router.register("skill.list") {
            listSkills(skillsListing)
        }
        router.register("skill.list_agent") { params ->
            // Agent-scoped assignment state is not projected yet. Returning the
            // process-global catalog would mark every available skill as installed
            // on every agent in Desktop. Fail closed until a real assignment source exists.
            params.requireParam(AdminParamKey("agent_id"))
            adminError(
                "capability_unavailable: skill.list_agent requires agent-scoped assignment " +
                    "projection; use skill.list for process-global availability",
            )
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
            invalidateRuntimesAfterSkillMutation(controller)
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
            invalidateRuntimesAfterSkillMutation(controller)
            result
        }
    }

    private fun listSkills(skillsListing: SkillsListingSource?): JsonObjectEnvelope {
        val hydrated = skillsListing?.isHydrated() != false
        // Cold start: return an empty listing with hydrated=false so UI can wait
        // without treating the absence of the first device-status frame as an error.
        if (!hydrated) {
            return buildJsonObject {
                put("skills", JsonArray(emptyList()))
                put("hydrated", false)
            }
        }
        val skills = skillsListing?.currentSkills() ?: JsonArray(emptyList())
        return buildJsonObject {
            put("skills", skills)
            put("hydrated", true)
        }
    }

    private fun resolveSkillPath(params: kotlinx.serialization.json.JsonObject?): String? {
        param(params, AdminParamKey("skill_path"))?.takeIf { it.isNotBlank() }?.let { return it }
        return param(params, AdminParamKey("name"))?.takeIf { it.isNotBlank() }
    }

    private suspend fun invalidateRuntimesAfterSkillMutation(
        controller: AppServerController?,
    ) {
        if (!RuntimeInvalidationPolicy.skillMutationRequiresRestart()) return
        // Filesystem skill availability is process-global; every cached runtime
        // captured the prior toolset and must reseed.
        controller?.stopAllRuntimes()
    }
}

private typealias JsonObjectEnvelope = kotlinx.serialization.json.JsonObject
