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
        adminBaseUrl: String,
        controller: AppServerController? = null,
        subagentRegistrySource: SubagentRegistrySource? = null,
        pairingService: IrohPairingService? = null,
        nativeClient: com.letta.mobile.data.transport.appserver.AppServerClient? = null,
        /** Phase 2: conversation.delete is always fail-closed; parameter retained for call-site compatibility. */
        @Suppress("UNUSED_PARAMETER") shimRetired: Boolean = true,
        /**
         * lgns8.9: VibeSync product service base URL for project.* methods. When
         * null the project methods return capability-unavailable instead of
         * dialing lettashim. Defaults to [adminBaseUrl] only for backward
         * compatibility in tests; production injects VibeSync directly.
         */
        vibesyncBaseUrl: String? = adminBaseUrl,
        /**
         * lgns8.9: base URL for the bounded admin_rest_service adapters (runs,
         * archives, identities, models, schedules, tools, blocks, mcp, goals,
         * slash-commands). When null those methods return capability-unavailable
         * instead of dialing lettashim. Defaults to [adminBaseUrl] for backward
         * compatibility; a shim-less deployment passes null.
         */
        adminRestBaseUrl: String? = adminBaseUrl,
        /**
         * Ignored since Phase 2. Direct Letta backend reads are not an accepted
         * production route; retained only so older call sites compile.
         */
        @Suppress("UNUSED_PARAMETER") localBackendDir: String? = null,
    ): AdminRpcRouter {
        val rpcBase = adminBaseUrl.trimEnd('/')
        val adminRestBase = adminRestBaseUrl?.trimEnd('/')
        val router = AdminRpcRouter()

        val tiers = NativeReadTiers(nativeClient)

        HealthAdminHandlers.register(router, rpcBase, controller)
        AgentAdminHandlers.register(router, rpcBase, controller, tiers)
        SubagentAdminHandlers.register(router, subagentRegistrySource)
        ConversationAdminHandlers.register(router, rpcBase, tiers, shimRetired = true)
        ProjectAdminHandlers.register(router, vibesyncBaseUrl?.trimEnd('/'))
        RunAdminHandlers.register(router, adminRestBase)
        ArchiveAdminHandlers.register(router, adminRestBase)
        IdentityAdminHandlers.register(router, adminRestBase)
        ModelAdminHandlers.register(router, adminRestBase, nativeClient)
        ScheduleAdminHandlers.register(router, adminRestBase)
        ToolAdminHandlers.register(router, adminRestBase)
        McpAdminHandlers.register(router, adminRestBase)
        GoalAdminHandlers.register(router, adminRestBase)
        SlashCommandAdminHandlers.register(router, adminRestBase)
        SkillAdminHandlers.register(router, rpcBase, nativeClient)
        ApprovalAdminHandlers.register(router, rpcBase, controller)
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
