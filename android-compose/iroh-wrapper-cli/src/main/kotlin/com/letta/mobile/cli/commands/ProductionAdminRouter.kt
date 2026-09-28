package com.letta.mobile.cli.commands

import com.letta.mobile.data.controller.DefaultAppServerController
import com.letta.mobile.data.controller.node.iroh.AdminRpcRegistry
import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.controller.node.iroh.HostSkillsEnumerator
import com.letta.mobile.data.controller.node.iroh.IrohPairingService
import com.letta.mobile.data.controller.node.iroh.NativeSkillsCatalog
import com.letta.mobile.data.controller.node.iroh.SubagentRegistrySource
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CoroutineScope

fun buildProductionAdminRouter(
    controller: DefaultAppServerController,
    subagentRegistrySource: SubagentRegistrySource? = null,
    /**
     * lgns8.22.8: when set, the controller-native subagent registry is backed by
     * this JSON file, so chips survive a controller restart and are reconciled
     * against live state on the next authoritative snapshot. Unset keeps the
     * previous in-memory-only behaviour.
     */
    subagentRegistryFile: String? = null,
    pairingService: IrohPairingService? = null,
    nativeClient: AppServerClient? = null,
    vibesyncBaseUrl: String? = null,
    /**
     * lgns8.9: the letta-code on-disk backend root. Admin READS the App Server
     * exposes no command for (run/step history, agent context, memory blocks)
     * are served READ-ONLY from it — the same directory lettashim read. Unset =>
     * those methods fail closed; there is no HTTP admin fallback any more.
     */
    localBackendDir: String? = System.getenv("LETTA_LOCAL_BACKEND_DIR"),
    /**
     * letta-mobile-7dm1q / lgns8.21.2: the letta-code skills root. letta-code
     * 0.29.12 advertises no skill enumeration on the wire, so without a host-side
     * enumerator `skill.list` answers `hydrated=false` forever and the Skills
     * screen stays empty. Enumerating this directory at startup is that missing
     * authoritative source. Unset => `LETTA_SKILLS_DIR` => `~/.letta/skills`.
     */
    skillsDir: String? = null,
    eventScope: CoroutineScope? = null,
    /** Pushes `agent_updated` to connected clients after agent writes. */
    agentChanges: com.letta.mobile.data.controller.node.iroh.AgentChangeNotifier? = null,
    conversationChanges: com.letta.mobile.data.controller.node.iroh.ConversationChangeNotifier? = null,
    /** letta-mobile-w4q4p: persisted model exposure decisions; null keeps them in memory. */
    modelExposureFile: String? = null,
): AdminRpcRouter {
    val skillsCatalog = NativeSkillsCatalog()
    // Cold-start discovery: hydrate BEFORE the router is built, so the very first
    // skill.list after a restart is already authoritative (lgns8.21.2 AC:
    // "discovery at cold start" + "preserved across restart" — the skills root is
    // on disk, so re-enumerating on every boot preserves it by construction).
    val resolvedSkillsDir = HostSkillsEnumerator
        .resolveSkillsDir(skillsDir)
    HostSkillsEnumerator.enumerate(resolvedSkillsDir)
        ?.let { enumerated ->
            skillsCatalog.hydrateFromHost(enumerated)
            Telemetry.event(
                "SkillsCatalog",
                "host.hydrated",
                "skillsDir" to resolvedSkillsDir,
                "skills" to enumerated.size.toString(),
            )
        }
        ?: Telemetry.event(
            "SkillsCatalog",
            "host.root_missing",
            "skillsDir" to resolvedSkillsDir,
        )
    val subagentStore = subagentRegistryFile
        ?.let { com.letta.mobile.data.subagents.FileSubagentRegistryStore(java.nio.file.Path.of(it)) }
        ?: com.letta.mobile.data.subagents.InMemorySubagentRegistryStore()
    val subagentSource = subagentRegistrySource
        ?: com.letta.mobile.data.controller.node.iroh.ControllerSubagentRegistrySource(
            com.letta.mobile.data.subagents.DurableSubagentRegistry(store = subagentStore),
        ).also { source ->
            if (nativeClient != null && eventScope != null) {
                source.start(eventScope, nativeClient.events)
            }
        }
    if (nativeClient != null && eventScope != null) {
        skillsCatalog.start(eventScope, nativeClient.events)
    }
    return AdminRpcRegistry.buildRouter(
        controller = controller,
        subagentRegistrySource = subagentSource,
        pairingService = pairingService,
        nativeClient = nativeClient,
        vibesyncBaseUrl = vibesyncBaseUrl,
        localBackendDir = localBackendDir,
        skillsListing = skillsCatalog.asListingSource(),
        agentChanges = agentChanges,
        conversationChanges = conversationChanges,
        modelExposureFile = modelExposureFile,
    )
}
