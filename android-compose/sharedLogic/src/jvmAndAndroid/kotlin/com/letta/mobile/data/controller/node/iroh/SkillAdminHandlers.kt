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
 * - Listings come only from an authoritative [SkillsListingSource]. There is no
 *   upstream `skill_list` command, `skills_updated` carries only a timestamp, and
 *   `device_status.current_available_skills` is hard-coded `[]` in 0.29.12, so an
 *   absent or unhydrated source yields `capability_unavailable` / `hydrated=false`
 *   rather than a fabricated empty catalog.
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
            val result = NativeAdmin.require(nativeClient, NativeAdminOp.SkillInstall) { c ->
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
            val result = NativeAdmin.require(nativeClient, NativeAdminOp.SkillUninstall) { c ->
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
        if (skillsListing == null) {
            adminError(
                "capability_unavailable: skill.list has no authoritative catalog source. " +
                    "letta-code 0.29.12 advertises no skill enumeration " +
                    "(skills_updated is {type,timestamp}; device_status.current_available_skills " +
                    "is hard-coded []; there is no skill_list command). Inject a " +
                    "SkillsListingSource backed by host skill-root enumeration to enable this method.",
            )
        }
        val hydrated = skillsListing.isHydrated()
        // Cold start: report an explicitly non-authoritative empty listing so the
        // UI can wait, instead of claiming hydrated=true over an invented catalog.
        val skills = if (hydrated) {
            skillsListing.currentSkills() ?: JsonArray(emptyList())
        } else {
            JsonArray(emptyList())
        }
        return buildJsonObject {
            put("skills", skills)
            put("hydrated", hydrated)
            put("stale", skillsListing.isStale())
            put("catalog_source", skillsListing.catalogSource())
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
