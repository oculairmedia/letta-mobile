package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.AppServerController

/**
 * Native App Server client for runtime-owned admin handlers.
 * Phase 2 removed the optional on-disk backend read tier from production routing.
 */
data class NativeReadTiers(
    val nativeClient: com.letta.mobile.data.transport.appserver.AppServerClient? = null,
)

object AdminRpcRegistry {
    val canonicalMethods: Set<String> = setOf(
        "conversation.list",
        // P2.6 (audit): conversation.get is registered by ConversationAdminHandlers
        // but was missing here, so the canonical/registered assertion passed only
        // by accident (it compares against the full router set).
        "conversation.get",
        "message.list",
        "message.get",
        "tool_return.get",
        "goal.get",
        "goal.command",
        "slash_command.list",
        "slash_command.list_agent",
        "skill.list",
        "skill.list_agent",
        "skill.install",
        "skill.uninstall",
        "tool.list",
        "tool.get",
        "tool.create",
        "tool.update",
        "tool.delete",
        "tool.attach",
        "tool.detach",
        "agent.list",
        "subagent.list",
        "subagent.todos",
        "health.check",
        "approval.submit",
        "project.list",
        "project.get",
        "project.beadsRemoteStatus",
        "project.provisionBeadsRemote",
        "project.triggerSync",
        "project.create",
        "project.update",
        "project.archive",
        "project.delete",
    )

    fun buildRouter(
        /**
         * Ignored since Phase 4. Former LettaShim admin base is not an accepted
         * production route; retained so older call sites compile.
         */
        @Suppress("UNUSED_PARAMETER") adminBaseUrl: String = "",
        controller: AppServerController? = null,
        subagentRegistrySource: SubagentRegistrySource? = null,
        pairingService: IrohPairingService? = null,
        nativeClient: com.letta.mobile.data.transport.appserver.AppServerClient? = null,
        /** Ignored since Phase 2/4 — conversation.delete is always fail-closed. */
        @Suppress("UNUSED_PARAMETER") shimRetired: Boolean = true,
        /**
         * VibeSync product service base URL for project.* methods. When null the
         * project methods return capability-unavailable. Production must inject
         * VibeSync explicitly — never fall back to the LettaShim admin base.
         */
        vibesyncBaseUrl: String? = null,
        /**
         * Bounded admin_rest_service adapters (runs, archives, identities,
         * embedding models, schedules, tools, blocks, mcp). When null those
         * methods return capability-unavailable. Must be an explicitly owned
         * service URL — never implicitly the LettaShim :8291 base.
         */
        adminRestBaseUrl: String? = null,
        /**
         * Ignored since Phase 2. Direct Letta backend reads are not an accepted
         * production route; retained only so older call sites compile.
         */
        @Suppress("UNUSED_PARAMETER") localBackendDir: String? = null,
        /**
         * Optional skills listing projection (device-status / skills_updated).
         * When null, skill.list returns an empty skills array until a catalog is wired.
         */
        skillsListing: SkillsListingSource? = null,
    ): AdminRpcRouter {
        val adminRestBase = adminRestBaseUrl?.trimEnd('/')
        val router = AdminRpcRouter()

        val tiers = NativeReadTiers(nativeClient)

        HealthAdminHandlers.register(router, controller)
        AgentAdminHandlers.register(router, controller, tiers, adminRestBase)
        SubagentAdminHandlers.register(router, subagentRegistrySource)
        ConversationAdminHandlers.register(router, tiers, controller = controller)
        ProjectAdminHandlers.register(router, vibesyncBaseUrl?.trimEnd('/'))
        RunAdminHandlers.register(router, adminRestBase)
        ArchiveAdminHandlers.register(router, adminRestBase)
        IdentityAdminHandlers.register(router, adminRestBase)
        ModelAdminHandlers.register(router, adminRestBase, nativeClient)
        ScheduleAdminHandlers.register(router, adminRestBase)
        ToolAdminHandlers.register(router, adminRestBase)
        McpAdminHandlers.register(router, adminRestBase)
        // Phase 3: shim-era goal/slash surfaces are product-removed.
        GoalAdminHandlers.register(router, adminBaseUrl = null)
        SlashCommandAdminHandlers.register(router, adminBaseUrl = null)
        SkillAdminHandlers.register(
            router,
            nativeClient = nativeClient,
            controller = controller,
            skillsListing = skillsListing,
        )
        ApprovalAdminHandlers.register(router, controller)
        PairingAdminHandlers.register(router, pairingService)
        CronAdminHandlers.register(router, nativeClient)
        ReflectionAdminHandlers.register(router, nativeClient)

        router.requireNonEmpty()
        val enabledMethods = if (subagentRegistrySource == null) {
            canonicalMethods - subagentMethods
        } else {
            canonicalMethods
        }
        val missingMethods = enabledMethods - router.registeredMethods
        check(missingMethods.isEmpty()) {
            "Admin RPC registry missing canonical methods: ${missingMethods.sorted().joinToString(", ")}"
        }

        return router
    }

    val subagentMethods: Set<String> = setOf("subagent.list", "subagent.todos")
}
