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
    ): AdminRpcRouter {
        val rpcBase = adminBaseUrl.trimEnd('/')
        val router = AdminRpcRouter()

        HealthAdminHandlers.register(router, rpcBase)
        AgentAdminHandlers.register(router, rpcBase, controller)
        SubagentAdminHandlers.register(router, subagentRegistrySource)
        ConversationAdminHandlers.register(router, rpcBase)
        ProjectAdminHandlers.register(router, rpcBase)
        RunAdminHandlers.register(router, rpcBase)
        ArchiveAdminHandlers.register(router, rpcBase)
        IdentityAdminHandlers.register(router, rpcBase)
        ModelAdminHandlers.register(router, rpcBase)
        ScheduleAdminHandlers.register(router, rpcBase)
        ToolAdminHandlers.register(router, rpcBase)
        McpAdminHandlers.register(router, rpcBase)
        GoalAdminHandlers.register(router, rpcBase)
        SlashCommandAdminHandlers.register(router, rpcBase)
        SkillAdminHandlers.register(router, rpcBase)
        ApprovalAdminHandlers.register(router, rpcBase, controller)

        router.requireNonEmpty()
        val enabledMethods = if (subagentRegistrySource == null) {
            canonicalMethods - SUBAGENT_METHODS
        } else {
            canonicalMethods
        }
        val missingMethods = enabledMethods - router.registeredMethods
        check(missingMethods.isEmpty()) {
            "Admin RPC registry missing canonical methods: ${missingMethods.sorted().joinToString(", ")}"
        }

        return router
    }

    private val SUBAGENT_METHODS = setOf("subagent.list", "subagent.todos")
}
