package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.AppServerController

/**
 * The two non-shim read sources available to admin handlers.
 *
 *  - [nativeClient]: the App Server v2 protocol — the owner of every
 *    runtime-scoped operation and of every admin WRITE that has a native
 *    command.
 *  - [localBackendStore]: a READ-ONLY reader over the letta-code on-disk
 *    backend (lgns8.9). It is the owner for admin READS the App Server exposes
 *    no command for and that lettashim itself served by reading this same
 *    store (runs/steps, agent context, memory blocks). Never used for writes:
 *    the epic forbids a second writer against one local-backend root.
 */
data class NativeReadTiers(
    val nativeClient: com.letta.mobile.data.transport.appserver.AppServerClient? = null,
    val localBackendStore: LocalBackendAdminStore? = null,
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
         * lgns8.9 retired the generic admin REST adapter: every former
         * admin_rest_service method now has an explicit owner (App Server v2
         * command, read-only local-backend store, controller-native catalog, or
         * fail-closed denial). Retained, ignored, so older call sites compile —
         * production must never reintroduce an admin REST base.
         */
        @Suppress("UNUSED_PARAMETER") adminRestBaseUrl: String? = null,
        /**
         * lgns8.9: the letta-code on-disk backend root (`LETTA_LOCAL_BACKEND_DIR`).
         * When set, admin READS the App Server exposes no command for — run/step
         * history, agent context, memory blocks — are served from it directly,
         * which is what lettashim did with the same directory. When null those
         * methods fail closed with a typed capability error; they never dial a
         * REST admin host.
         */
        localBackendDir: String? = null,
        /**
         * Optional skills listing projection (device-status / skills_updated).
         * When null, skill.list returns an empty skills array until a catalog is wired.
         */
        skillsListing: SkillsListingSource? = null,
    ): AdminRpcRouter {
        val router = AdminRpcRouter()

        // Constructed only when a backend root is explicitly configured, and
        // read-only by construction (see LocalBackendAdminStore).
        val localBackendStore = localBackendDir
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { LocalBackendAdminStore(java.io.File(it)) }.getOrNull() }
        val tiers = NativeReadTiers(nativeClient, localBackendStore)

        HealthAdminHandlers.register(router, controller)
        AgentAdminHandlers.register(router, controller, tiers)
        SubagentAdminHandlers.register(router, subagentRegistrySource)
        ConversationAdminHandlers.register(router, tiers, controller = controller)
        ProjectAdminHandlers.register(router, vibesyncBaseUrl?.trimEnd('/'))
        RunAdminHandlers.register(router, localBackendStore)
        ArchiveAdminHandlers.register(router)
        IdentityAdminHandlers.register(router)
        ModelAdminHandlers.register(router, nativeClient)
        ScheduleAdminHandlers.register(router, nativeClient)
        ToolAdminHandlers.register(router, localBackendStore, nativeClient)
        McpAdminHandlers.register(router)
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
