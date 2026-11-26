package com.letta.mobile.cli.commands

import com.letta.mobile.data.controller.DefaultAppServerController
import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.controller.node.iroh.ControllerSubagentRegistrySource
import com.letta.mobile.data.controller.node.iroh.IrohPairingService
import com.letta.mobile.data.controller.node.iroh.SubagentRegistryPublisher
import com.letta.mobile.data.controller.node.iroh.SubagentRegistrySource
import com.letta.mobile.data.node.NodeAdminRouterSpec
import com.letta.mobile.data.transport.appserver.AppServerClient
import kotlinx.coroutines.CoroutineScope

/**
 * The wrapper's admin_rpc router: [NodeAdminRouterSpec] with this process's
 * `LETTA_LOCAL_BACKEND_DIR` as the default backend root.
 */
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
    /** Host-local App Server process proof, unknown for remote or unsupported hosts. */
    appServerProcessIdentity: com.letta.mobile.data.subagents.AppServerProcessIdentity =
        com.letta.mobile.data.subagents.AppServerProcessIdentity { null },
    pairingService: IrohPairingService? = null,
    nativeClient: AppServerClient? = null,
    vibesyncBaseUrl: String? = null,
    beadsBaseUrl: String? = null,
    beadsTokenFile: String? = null,
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
    /** Run/lease JSON file; defaults beside configured model exposure state, otherwise memory-only. */
    runRecordsFile: String? = null,
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
    canvasMountRoutes: com.letta.mobile.data.controller.node.iroh.AdminRpcRoutes? = null,
    /** The voice endpoint routes (shared speech setup, speech through the host); null registers none. */
    voiceRoutes: com.letta.mobile.data.controller.node.iroh.AdminRpcRoutes? = null,
    /** The pinned-agents routes (one list shared by every device, pushed live); null registers none. */
    pinsRoutes: com.letta.mobile.data.controller.node.iroh.AdminRpcRoutes? = null,
    /** The shell-state routes (one versioned document of shell and user state shared by every device); null registers none. */
    shellStateRoutes: com.letta.mobile.data.controller.node.iroh.AdminRpcRoutes? = null,
    /** The service credential routes (non-LLM service secrets held on the host); null registers none. */
    serviceCredentialRoutes: com.letta.mobile.data.controller.node.iroh.AdminRpcRoutes? = null,
    /** The conversation feed route (`feed.subscribe` over the host's durable turn journal); null registers none. */
    feedRoutes: com.letta.mobile.data.controller.node.iroh.AdminRpcRoutes? = null,
): AdminRpcRouter = NodeAdminRouterSpec(
    controller = controller,
    subagentRegistrySource = subagentRegistrySource,
    subagentRegistryFile = subagentRegistryFile,
    appServerProcessIdentity = appServerProcessIdentity,
    pairingService = pairingService,
    nativeClient = nativeClient,
    vibesyncBaseUrl = vibesyncBaseUrl,
    beadsBaseUrl = beadsBaseUrl,
    beadsTokenFile = beadsTokenFile,
    localBackendDir = localBackendDir,
    skillsDir = skillsDir,
    eventScope = eventScope,
    agentChanges = agentChanges,
    conversationChanges = conversationChanges,
    modelExposureFile = modelExposureFile,
    runRecordsFile = runRecordsFile,
    turnIdentity = turnIdentity,
    subagentPublisher = subagentPublisher,
    pairingQr = pairingQr,
    permissionModes = permissionModes,
    pluginRoutes = pluginRoutes,
    canvasMountRoutes = canvasMountRoutes,
    voiceRoutes = voiceRoutes,
    pinsRoutes = pinsRoutes,
    shellStateRoutes = shellStateRoutes,
    serviceCredentialRoutes = serviceCredentialRoutes,
    feedRoutes = feedRoutes,
).build()
