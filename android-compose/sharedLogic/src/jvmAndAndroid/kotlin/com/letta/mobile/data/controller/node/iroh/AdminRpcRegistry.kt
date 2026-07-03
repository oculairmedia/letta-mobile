package com.letta.mobile.data.controller.node.iroh

object AdminRpcRegistry {
    val canonicalMethods: Set<String> = setOf(
        "conversation.list",
        "message.list",
        "message.get",
        "goal.get",
        "goal.command",
        "agent.list",
        "health.check",
    )

    fun buildRouter(adminBaseUrl: String): AdminRpcRouter {
        val rpcBase = adminBaseUrl.trimEnd('/')
        val router = AdminRpcRouter()

        HealthAdminHandlers.register(router, rpcBase)
        AgentAdminHandlers.register(router, rpcBase)
        ConversationAdminHandlers.register(router, rpcBase)
        RunAdminHandlers.register(router, rpcBase)
        ArchiveAdminHandlers.register(router, rpcBase)
        IdentityAdminHandlers.register(router, rpcBase)
        ModelAdminHandlers.register(router, rpcBase)
        ScheduleAdminHandlers.register(router, rpcBase)
        ToolAdminHandlers.register(router, rpcBase)
        McpAdminHandlers.register(router, rpcBase)
        GoalAdminHandlers.register(router, rpcBase)

        router.requireNonEmpty()
        val missingMethods = canonicalMethods - router.registeredMethods
        check(missingMethods.isEmpty()) {
            "Admin RPC registry missing canonical methods: ${missingMethods.sorted().joinToString(", ")}"
        }

        return router
    }
}
