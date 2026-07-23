package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.AppServerController

object AdminRpcRegistry {
    val canonicalMethods: Set<String> = setOf(
        "conversation.list",
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
        /** lgns8.8/.11 cutover lever: capability-gated ops deny instead of using the shim. */
        shimRetired: Boolean = false,
        /**
         * lgns8.9: VibeSync product service base URL for project.* methods. When
         * null the project methods return capability-unavailable instead of
         * dialing lettashim. Defaults to [adminBaseUrl] only for backward
         * compatibility in tests; production injects VibeSync directly.
         */
        vibesyncBaseUrl: String? = adminBaseUrl,
    ): AdminRpcRouter {
        val rpcBase = adminBaseUrl.trimEnd('/')
        val router = AdminRpcRouter()

        HealthAdminHandlers.register(router, rpcBase, controller)
        AgentAdminHandlers.register(router, rpcBase, controller, nativeClient)
        SubagentAdminHandlers.register(router, subagentRegistrySource)
        ConversationAdminHandlers.register(router, rpcBase, nativeClient, shimRetired)
        ProjectAdminHandlers.register(router, vibesyncBaseUrl?.trimEnd('/'))
        RunAdminHandlers.register(router, rpcBase)
        ArchiveAdminHandlers.register(router, rpcBase)
        IdentityAdminHandlers.register(router, rpcBase)
        ModelAdminHandlers.register(router, rpcBase, nativeClient)
        ScheduleAdminHandlers.register(router, rpcBase)
        ToolAdminHandlers.register(router, rpcBase)
        McpAdminHandlers.register(router, rpcBase)
        GoalAdminHandlers.register(router, rpcBase)
        SlashCommandAdminHandlers.register(router, rpcBase)
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
