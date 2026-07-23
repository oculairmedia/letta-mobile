package com.letta.mobile.data.controller.node.iroh

/**
 * Server-side per-peer authorization after authentication
 * (letta-mobile-d6e8g.6). Endpoint authentication alone does not imply
 * unrestricted admin: every protected command and admin_rpc method maps to a
 * capability, unknown methods deny by default, and paired peers carry an
 * explicit persisted capability set. `admin.full` is never implicit.
 */
object IrohPeerCapabilities {
    const val CHAT_READ = "chat.read"
    const val CHAT_SEND = "chat.send"
    const val CONVERSATION_MANAGE = "conversation.manage"
    const val MEMORY_READ = "memory.read"
    const val MEMORY_WRITE = "memory.write"
    const val SCHEDULE_MANAGE = "schedule.manage"
    const val SKILLS_MANAGE = "skills.manage"
    const val TOOLS_MANAGE = "tools.manage"
    const val PROJECTS_MANAGE = "projects.manage"
    const val ADMIN_FULL = "admin.full"

    val ALL: Set<String> = setOf(
        CHAT_READ, CHAT_SEND, CONVERSATION_MANAGE, MEMORY_READ, MEMORY_WRITE,
        SCHEDULE_MANAGE, SKILLS_MANAGE, TOOLS_MANAGE, PROJECTS_MANAGE, ADMIN_FULL,
    )

    /**
     * Least-privilege default role granted on pairing: a working desktop
     * (chat, conversation management, memory, schedules, skills, tools,
     * projects) WITHOUT admin.full — server administration (agent CRUD,
     * identities, runs/jobs, providers, goals, pairing management, health)
     * must be granted explicitly.
     */
    val DEFAULT_DESKTOP_ROLE: Set<String> = setOf(
        CHAT_READ, CHAT_SEND, CONVERSATION_MANAGE, MEMORY_READ, MEMORY_WRITE,
        SCHEDULE_MANAGE, SKILLS_MANAGE, TOOLS_MANAGE, PROJECTS_MANAGE,
    )

    /** Capability required for the runtime-protocol commands on the control channel. */
    fun forProtocolCommand(type: String): String? = when (type) {
        "runtime_start" -> CHAT_SEND
        "input" -> CHAT_SEND
        "sync" -> CHAT_READ
        "abort_message" -> CHAT_SEND
        "admin_rpc" -> null // resolved per-method via forAdminMethod
        else -> null
    }

    /**
     * Capability required for an admin_rpc method. Unknown/unmapped methods
     * require [ADMIN_FULL] — deny-by-default for anything new until it is
     * classified here (and in the lgns8.13 ownership matrix).
     */
    fun forAdminMethod(method: String): String = when {
        method in CHAT_READ_METHODS -> CHAT_READ
        method in CHAT_SEND_METHODS -> CHAT_SEND
        method in CONVERSATION_MANAGE_METHODS -> CONVERSATION_MANAGE
        method.startsWith("block.") || method.startsWith("passage.") ->
            if (method.isReadMethod()) MEMORY_READ else MEMORY_WRITE
        method.startsWith("schedule.") || method.startsWith("job.") || method.startsWith("cron.") -> SCHEDULE_MANAGE
        method.startsWith("skill.") -> SKILLS_MANAGE
        method.startsWith("tool.") || method == "mcp.list" -> TOOLS_MANAGE
        method.startsWith("project.") -> PROJECTS_MANAGE
        // Pairing management (invite/list/get/rename/set_capabilities/revoke) is
        // privileged: only an admin.full peer may enroll, re-scope, or revoke
        // devices (d6e8g.7). Explicit so it never silently downgrades if a
        // future prefix rule is added above.
        method.startsWith("pair.") -> ADMIN_FULL
        else -> ADMIN_FULL
    }

    fun isAllowed(capabilities: Set<String>, required: String): Boolean =
        ADMIN_FULL in capabilities || required in capabilities

    /**
     * Conversation-content scope for admin_rpc reads (lgns8.12): peers that
     * may manage conversations read any conversation (null = unrestricted);
     * lesser peers are bounded to the conversation they are actively viewing
     * (empty set when none) — cross-conversation content access is rejected
     * at the handler with no proxy side effects.
     */
    fun conversationScope(capabilities: Set<String>, viewedConversationId: String?): Set<String>? =
        if (ADMIN_FULL in capabilities || CONVERSATION_MANAGE in capabilities) {
            null
        } else {
            viewedConversationId?.let(::setOf) ?: emptySet()
        }

    private fun String.isReadMethod(): Boolean =
        endsWith(".list") || endsWith(".get") || endsWith(".list_agent")

    private val CHAT_READ_METHODS = setOf(
        "conversation.list", "conversation.get",
        "message.list", "message.get", "tool_return.get",
        "agent.list", "agent.get",
        "model.list", "model.list.embedding",
        "subagent.list", "subagent.todos",
        "slash_command.list", "slash_command.list_agent",
    )

    private val CHAT_SEND_METHODS = setOf("approval.submit")

    private val CONVERSATION_MANAGE_METHODS = setOf(
        "conversation.create", "conversation.update", "conversation.archive",
        "conversation.restore", "conversation.delete",
    )
}
