package com.letta.mobile.cli.commands

import com.letta.mobile.data.controller.DefaultAppServerController
import com.letta.mobile.data.controller.node.iroh.AdminRpcRegistry
import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.controller.node.iroh.ControllerSubagentRegistrySource
import com.letta.mobile.data.controller.node.iroh.HostSkillsEnumerator
import com.letta.mobile.data.controller.node.iroh.IrohPairingService
import com.letta.mobile.data.controller.node.iroh.NativeSkillsCatalog
import com.letta.mobile.data.controller.node.iroh.NativeSlashCommandCatalog
import com.letta.mobile.data.controller.node.iroh.SubagentRegistryPublisher
import com.letta.mobile.data.controller.node.iroh.SubagentRegistrySource
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CoroutineScope
import com.letta.mobile.data.controller.node.iroh.AdminRpcNativeSources
import com.letta.mobile.data.controller.node.iroh.AdminRpcNotifications
import com.letta.mobile.data.controller.node.iroh.AdminRpcPairing
import com.letta.mobile.data.controller.node.iroh.AdminRpcHostServices

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
     * authoritative source. Unset => `LETTA_SKILLS_DIR` => `<LETTA_HOME>/skills`
     * (letta-code's own control root; only symlinks there are controllable).
     */
    skillsDir: String? = null,
    eventScope: CoroutineScope? = null,
    /** Pushes `agent_updated` to connected clients after agent writes. */
    agentChanges: com.letta.mobile.data.controller.node.iroh.AgentChangeNotifier? = null,
    conversationChanges: com.letta.mobile.data.controller.node.iroh.ConversationChangeNotifier? = null,
    /** letta-mobile-w4q4p: persisted model exposure decisions; null keeps them in memory. */
    modelExposureFile: String? = null,
    /** letta-mobile-r1xkl: serves stream-joined ids on `message.list`; null serves rows as stored. */
    turnIdentity: com.letta.mobile.data.runtime.TurnIdentityLedger? = null,
    /**
     * letta-mobile-fxoew.2: builds the publisher that pushes the controller-native
     * registry to conversation viewers. Applies only to the default registry source;
     * when set, the router advertises [ControllerSubagentRegistrySource.PUSH_CAPABILITY].
     */
    subagentPublisher: ((ControllerSubagentRegistrySource) -> SubagentRegistryPublisher)? = null,
    /** letta-mobile-dpv7m.1: QR minting identity for pair.invite.create; null = no QR. */
    pairingQr: com.letta.mobile.data.controller.node.iroh.PairingQrConfig? = null,
    /** letta-mobile-a4tm7: pushes confirmed permission-mode changes to conversation viewers. */
    permissionModes: com.letta.mobile.data.controller.node.iroh.PermissionModePublisher? = null,
    /** letta-mobile-cdgpr.7: the plugin host's `plugins.*` handlers; null when plugins are off. */
    pluginRoutes: com.letta.mobile.data.controller.node.iroh.AdminRpcRoutes? = null,
): AdminRpcRouter {
    // Every skill.list re-reads the root (a symlink is the controllable unit and can
    // change on disk at any time); the cold-start read below also logs what the host
    // sees, so the first listing after a restart is already authoritative.
    val resolvedSkillsDir = HostSkillsEnumerator
        .resolveSkillsDir(skillsDir)
    val skillsCatalog = NativeSkillsCatalog(hostEnumeration = { HostSkillsEnumerator.enumerate(resolvedSkillsDir) })
    if (skillsCatalog.refreshFromHost()) {
        Telemetry.event(
            "SkillsCatalog",
            "host.hydrated",
            "skillsDir" to resolvedSkillsDir,
            "skills" to skillsCatalog.snapshot().size.toString(),
        )
    } else {
        Telemetry.event(
            "SkillsCatalog",
            "host.root_missing",
            "skillsDir" to resolvedSkillsDir,
        )
    }
    val subagentStore = subagentRegistryFile
        ?.let { com.letta.mobile.data.subagents.FileSubagentRegistryStore(java.nio.file.Path.of(it)) }
        ?: com.letta.mobile.data.subagents.InMemorySubagentRegistryStore()
    val nativeSource = if (subagentRegistrySource == null) {
        ControllerSubagentRegistrySource(
            com.letta.mobile.data.subagents.DurableSubagentRegistry(store = subagentStore),
        ).also { source ->
            subagentPublisher?.let { build -> source.changeListener = build(source) }
            if (nativeClient != null && eventScope != null) {
                source.start(eventScope, nativeClient.events)
            }
        }
    } else {
        null
    }
    val subagentSource = subagentRegistrySource ?: requireNotNull(nativeSource)
    // letta-mobile-by4vm.84: slash commands ride update_device_status on the runtime lane.
    val slashCommands = nativeClient?.let { NativeSlashCommandCatalog() }
    if (nativeClient != null && eventScope != null) {
        skillsCatalog.start(eventScope, nativeClient.events)
        slashCommands?.start(eventScope, nativeClient.events)
    }
    return AdminRpcRegistry.buildRouter(
        services = AdminRpcHostServices(
            controller = controller,
            subagentRegistrySource = subagentSource,
            vibesyncBaseUrl = vibesyncBaseUrl,
            pluginRoutes = pluginRoutes,
        ),
        pairing = AdminRpcPairing(
            pairingService = pairingService,
            pairingQr = pairingQr,
        ),
        native = AdminRpcNativeSources(
            nativeClient = nativeClient,
            localBackendDir = localBackendDir,
            skillsListing = skillsCatalog.asListingSource(),
            slashCommands = slashCommands,
            modelExposureFile = modelExposureFile,
        ),
        notifications = AdminRpcNotifications(
            agentChanges = agentChanges,
            conversationChanges = conversationChanges,
            turnIdentity = turnIdentity,
            permissionModes = permissionModes,
        ),
    ).also { router ->
        if (nativeSource?.changeListener != null) router.featureCapabilities += ControllerSubagentRegistrySource.PUSH_CAPABILITY
    }
}
