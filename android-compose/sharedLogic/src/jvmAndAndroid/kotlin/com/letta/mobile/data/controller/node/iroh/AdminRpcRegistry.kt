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
        "agent.list",
        "health.check",
        "approval.submit",
    )

    fun buildRouter(adminBaseUrl: String, controller: AppServerController? = null): AdminRpcRouter {
        val rpcBase = adminBaseUrl.trimEnd('/')
        val router = AdminRpcRouter()

        HealthAdminHandlers.register(router, rpcBase)
        AgentAdminHandlers.register(router, rpcBase, controller)
        ConversationAdminHandlers.register(router, rpcBase)
        RunAdminHandlers.register(router, rpcBase)
        ArchiveAdminHandlers.register(router, rpcBase)
        IdentityAdminHandlers.register(router, rpcBase)
        ModelAdminHandlers.register(router, rpcBase)
        ScheduleAdminHandlers.register(router, rpcBase)
        ToolAdminHandlers.register(router, rpcBase)
        McpAdminHandlers.register(router, rpcBase)
        GoalAdminHandlers.register(router, rpcBase)
        SlashCommandAdminHandlers.register(router, rpcBase)
        ApprovalAdminHandlers.register(router, rpcBase, controller)

        router.requireNonEmpty()
        val missingMethods = canonicalMethods - router.registeredMethods
        check(missingMethods.isEmpty()) {
            "Admin RPC registry missing canonical methods: ${missingMethods.sorted().joinToString(", ")}"
        }

        return router
    }
}
