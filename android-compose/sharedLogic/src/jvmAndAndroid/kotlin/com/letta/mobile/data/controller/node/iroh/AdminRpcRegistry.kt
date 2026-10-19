package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.plugin.catalog.PluginAdminRpc

/**
 * The two non-shim read sources available to admin handlers.
 *
 *  - [nativeClient]: the App Server v2 protocol — the owner of every
 *    runtime-scoped operation and of every admin WRITE that has a native
 *    command.
 *  - [localBackendStore]: a READ-ONLY reader over the letta-code on-disk
 *    backend. It is the owner for admin READS the App Server exposes
 *    no command for and that lettashim itself served by reading this same
 *    store (runs/steps, agent context, memory blocks). Never used for writes:
 *    the epic forbids a second writer against one local-backend root.
 */
data class NativeReadTiers(
    val nativeClient: com.letta.mobile.data.transport.appserver.AppServerClient? = null,
    val localBackendStore: LocalBackendAdminStore? = null,
    /** Agent metadata the App Server drops; see [AgentMetadataSidecar]. Null keeps pass-through. */
    val agentMetadata: AgentMetadataSidecar? = null,
    /** Pushes `agent_updated` to connected clients after agent writes; null sends nothing. */
    val agentChanges: AgentChangeNotifier? = null,
    /** Pushes `conversation_updated` to connected clients after conversation writes; null sends nothing. */
    val conversationChanges: ConversationChangeNotifier? = null,
    /** Serves `logical_message_id` / `turn_id` on `message.list` rows; null serves rows as stored. */
    val turnIdentity: com.letta.mobile.data.runtime.TurnIdentityLedger? = null,
)

/** Admin RPC handlers a platform adds to the router: the JVM host's plugin runtime. */
fun interface AdminRpcRoutes {
    fun register(router: AdminRpcRouter)
}

object AdminRpcRegistry {
    val canonicalMethods: Set<String> = setOf(
        "conversation.list",
        "conversation.list_agent",
        // P2.6 (audit): conversation.get is registered by ConversationAdminHandlers
        // but was missing here, so the canonical/registered assertion passed only
        // by accident (it compares against the full router set).
        "conversation.get",
        // letta-mobile-57cta: manual compaction relay (ConversationCompactHandlers).
        "conversation.compact",
        // letta-mobile-joigh: context-limit relay (ConversationContextLimitHandlers).
        "conversation.context_limit",
        // letta-mobile-by4vm.51: system-prompt recompile relay (ConversationRecompileHandlers).
        "conversation.recompile",
        // letta-mobile-iczm7: dreaming (ReflectionAdminHandlers).
        "reflection.get",
        "reflection.set",
        "reflection.run",
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
        "agent.count",
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
    ) + PluginAdminRpc.METHODS

    fun buildRouter(
        services: AdminRpcHostServices = AdminRpcHostServices(),
        pairing: AdminRpcPairing = AdminRpcPairing(),
        native: AdminRpcNativeSources = AdminRpcNativeSources(),
        notifications: AdminRpcNotifications = AdminRpcNotifications(),
    ): AdminRpcRouter {
        val router = AdminRpcRouter()

        // Constructed only when a backend root is explicitly configured, and
        // read-only by construction (see LocalBackendAdminStore).
        val localBackendStore = native.localBackendDir
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { LocalBackendAdminStore(java.io.File(it)) }.getOrNull() }
        // Beside the store it annotates, but written only by Meridian (the store stays read-only).
        val agentMetadata = native.localBackendDir
            ?.takeIf { it.isNotBlank() }
            ?.let { AgentMetadataSidecar.inLocalBackend(java.io.File(it)) }
        val tiers = NativeReadTiers(
            native.nativeClient, localBackendStore, agentMetadata,
            notifications.agentChanges, notifications.conversationChanges, notifications.turnIdentity,
        )

        HealthAdminHandlers.register(router, services.controller)
        AgentAdminHandlers.register(router, services.controller, tiers)
        SubagentAdminHandlers.register(router, services.subagentRegistrySource)
        ConversationAdminHandlers.register(router, tiers, controller = services.controller)
        ProjectAdminHandlers.register(router, services.vibesyncBaseUrl?.trimEnd('/'))
        RunAdminHandlers.register(router, localBackendStore)
        ArchiveAdminHandlers.register(router)
        IdentityAdminHandlers.register(router)
        ModelAdminHandlers.register(router, native.nativeClient, modelExposureStore(native.modelExposureFile))
        ScheduleAdminHandlers.register(router, native.nativeClient)
        ToolAdminHandlers.register(router, localBackendStore, native.nativeClient)
        McpAdminHandlers.register(router)
        // letta-mobile-by4vm.84: goals have no App Server owner; slash commands come from device status.
        GoalAdminHandlers.register(router)
        SlashCommandAdminHandlers.register(router, native.slashCommands ?: native.nativeClient?.let { NativeSlashCommandCatalog() })
        SkillAdminHandlers.register(
            router,
            nativeClient = native.nativeClient,
            controller = services.controller,
            skillsListing = native.skillsListing,
        )
        ApprovalAdminHandlers.register(router, services.controller)
        // letta-mobile-bzvro.40: the conversation's permission mode for Iroh clients.
        PermissionModeAdminHandlers.register(router, services.controller, notifications.permissionModes)
        PairingAdminHandlers.register(router, pairing.pairingService, pairing.pairingQr)
        CronAdminHandlers.register(router, native.nativeClient)
        ReflectionAdminHandlers.register(router, native.nativeClient)
        // letta-mobile-bzvro.37: MemFS / secrets / workspace files for Iroh clients.
        WorkspaceAdminHandlers.register(router, native.nativeClient)
        // letta-mobile-57cta: manual compaction for Iroh clients (execute_command / conversation_compact).
        ConversationCompactHandlers.register(router, native.nativeClient, localBackendStore)
        // letta-mobile-joigh: the context limit for Iroh clients (execute_command context-limit).
        ConversationContextLimitHandlers.register(router, native.nativeClient)
        // letta-mobile-by4vm.51: /recompile for Iroh clients (conversation_recompile).
        ConversationRecompileHandlers.register(router, native.nativeClient)
        services.pluginRoutes?.register(router)
            ?: CapabilityUnavailable.register(router, PluginAdminRpc.METHODS, "plugin host")

        router.requireNonEmpty()
        val enabledMethods = if (services.subagentRegistrySource == null) {
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

    private fun modelExposureStore(path: String?): ModelExposureStore =
        path?.takeIf { it.isNotBlank() }
            ?.let { FileModelExposureStore(java.io.File(it)) }
            ?: InMemoryModelExposureStore()
}
