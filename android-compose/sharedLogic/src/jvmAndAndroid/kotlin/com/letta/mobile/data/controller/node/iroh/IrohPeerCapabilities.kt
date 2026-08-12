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
    // letta-mobile-qjncd: new capabilities gating the Vibesync cross-project consumer.
    // Both are CLIENT-side authorization constructs (not letta-code RPC verbs): the gate
    // here authorizes the canonical admin_rpc methods (agent.create / conversation.create
    // for spawn; workactivity.{report,list,get}) ahead of the workactivity.report server
    // handler (lgns8.25.1) which lands in a follow-up PR. Auth-first: the gate ships
    // before the handler, so callers fail fail-closed with `authz.denied` until then.
    const val SUBAGENT_SPAWN = "subagent.spawn"
    const val WORKACTIVITY_REPORT = "workactivity.report"

    val ALL: Set<String> = setOf(
        CHAT_READ, CHAT_SEND, CONVERSATION_MANAGE, MEMORY_READ, MEMORY_WRITE,
        SCHEDULE_MANAGE, SKILLS_MANAGE, TOOLS_MANAGE, PROJECTS_MANAGE, ADMIN_FULL,
        SUBAGENT_SPAWN, WORKACTIVITY_REPORT,
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

    /**
     * Vibesync consumer role (letta-mobile-qjncd): a paired desktop that may
     * additionally spawn subagents (agent.create + conversation.create,
     * without the rest of admin.full) and read/write the workactivity stream.
     * Lighter than ADMIN_FULL, broader than DEFAULT_DESKTOP_ROLE on the two
     * surfaces Vibesync specifically needs. Pairing callers (operator-side
     * config) pass this set to IrohPairingService.setCapabilities.
     */
    val VIBESYNC_ROLE: Set<String> = setOf(
        CHAT_READ, CHAT_SEND, CONVERSATION_MANAGE,
        SUBAGENT_SPAWN, WORKACTIVITY_REPORT,
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
        // P0.5 (audit): read-only server metadata (providers, goals, groups,
        // folders, archives, run/step history, identities) reclassified from the
        // deny-by-default admin.full bucket into the CHAT_READ tier — the same
        // class of benign read as agent.list/model.list — so standard paired
        // desktops can list them. Reusing an existing broadly-held read capability
        // (not a new one) means already-persisted paired peers get these with no
        // capability migration. Mutations in the same namespaces (e.g. goal.command)
        // are NOT listed here and stay admin.full via the else branch.
        method in ADMIN_READ_METHODS -> CHAT_READ
        method in CHAT_SEND_METHODS -> CHAT_SEND
        method in CONVERSATION_MANAGE_METHODS -> CONVERSATION_MANAGE
        // A paired desktop must be able to edit its own agents' config — most
        // importantly change the model (model selection) — which rides agent.update.
        // Classify it in the trusted-desktop manage surface (CONVERSATION_MANAGE,
        // held by DEFAULT_DESKTOP_ROLE) so it isn't denied. Agent LIFECYCLE
        // (agent.create / agent.delete) stays admin.full via the else branch.
        method == "agent.update" -> CONVERSATION_MANAGE
        method.startsWith("block.") || method.startsWith("passage.") ->
            if (method.isReadMethod()) MEMORY_READ else MEMORY_WRITE
        // lgns8.16: reflection/sleeptime settings control WHEN the agent
        // consolidates memory (dreaming) — a memory-management operation, not an
        // admin one. Classify it in the memory tier (read vs write) like blocks
        // and passages, so a standard paired device can read/adjust dreaming.
        // Without this, reflection.* fell into the `else -> ADMIN_FULL` deny-by-
        // default bucket and was silently unusable for every non-admin peer.
        method.startsWith("reflection.") ->
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
        // letta-mobile-qjncd: Vibesync consumer may spawn subagents (via the
        // canonical agent.create + conversation.create verbs) and report on the
        // workactivity stream. Both arms sit above the deny-by-default else so
        // Vibesync peers get SUBAGENT_SPAWN/WORKACTIVITY_REPORT without admin.full.
        method in SUBAGENT_SPAWN_METHODS -> SUBAGENT_SPAWN
        method in WORKACTIVITY_METHODS -> WORKACTIVITY_REPORT
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
        "agent.list", "agent.count", "agent.get", "agent.context",
        "model.list", "model.list.embedding",
        "subagent.list", "subagent.todos",
        "slash_command.list", "slash_command.list_agent",
    )

    /**
     * P0.5: read-only server-metadata methods classified as benign reads
     * (CHAT_READ tier). Explicit method names — only these exact reads are
     * folded in; any mutation in the same namespace stays admin.full.
     */
    private val ADMIN_READ_METHODS = setOf(
        "health.check",
        "provider.list",
        "goal.get",
        "group.list",
        "folder.list",
        "archive.list",
        "step.list",
        "identity.list", "identity.get",
        "run.list", "run.get",
    )

    private val CHAT_SEND_METHODS = setOf("approval.submit")

    private val CONVERSATION_MANAGE_METHODS = setOf(
        "conversation.create", "conversation.update", "conversation.archive",
        "conversation.restore", "conversation.delete",
    )

    // letta-mobile-qjncd: SUBAGENT_SPAWN is a CLIENT-side authorization construct.
    // letta-code has no RPC verb literally named "subagent.spawn" — the cap gates
    // the canonical verbs a Vibesync peer uses to spawn an agent + conversation
    // (agent.create + conversation.create) without granting admin.full. The second
    // verb is already in CONVERSATION_MANAGE_METHODS for the trusted-desktop surface;
    // here it's classified for the narrower Vibesync surface. Reusing the
    // ownership-matrix classification already used for the general peer roles
    // (lgns8.13) keeps the lgns8.25.1 follow-up (the workactivity.report server
    // handler) decoupled from this gate.
    private val SUBAGENT_SPAWN_METHODS = setOf(
        "agent.create",
        "conversation.create",
    )

    // WORKACTIVITY_REPORT gates the workactivity stream that Vibesync reports into.
    // Includes the canonical write (workactivity.report, lands in lgns8.25.1) plus
    // the list/get reads so a Vibesync peer can fetch its own reports back. Gate
    // ships first; the report handler lands second, callers fail-closed with
    // authz.denied until then.
    private val WORKACTIVITY_METHODS = setOf(
        "workactivity.report",
        "workactivity.list",
        "workactivity.get",
    )
}
