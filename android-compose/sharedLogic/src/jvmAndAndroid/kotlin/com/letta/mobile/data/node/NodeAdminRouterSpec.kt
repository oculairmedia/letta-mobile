package com.letta.mobile.data.node

import com.letta.mobile.data.controller.DefaultAppServerController
import com.letta.mobile.data.controller.node.iroh.AdminRpcHostServices
import com.letta.mobile.data.controller.node.iroh.AdminRpcNativeSources
import com.letta.mobile.data.controller.node.iroh.AdminRpcNotifications
import com.letta.mobile.data.controller.node.iroh.AdminRpcPairing
import com.letta.mobile.data.controller.node.iroh.AdminRpcRegistry
import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.controller.node.iroh.AdminRpcRoutes
import com.letta.mobile.data.controller.node.iroh.AgentChangeNotifier
import com.letta.mobile.data.controller.node.iroh.ControllerSubagentRegistrySource
import com.letta.mobile.data.controller.node.iroh.ConversationChangeNotifier
import com.letta.mobile.data.controller.node.iroh.HostSkillsEnumerator
import com.letta.mobile.data.controller.node.iroh.IrohPairingService
import com.letta.mobile.data.controller.node.iroh.NativeSkillsCatalog
import com.letta.mobile.data.controller.node.iroh.NativeSlashCommandCatalog
import com.letta.mobile.data.controller.node.iroh.PairingQrConfig
import com.letta.mobile.data.controller.node.iroh.PermissionModePublisher
import com.letta.mobile.data.controller.node.iroh.SubagentRegistryPublisher
import com.letta.mobile.data.controller.node.iroh.SubagentRegistrySource
import com.letta.mobile.data.controller.node.iroh.beads.BeadsRouteClient
import com.letta.mobile.data.controller.node.iroh.beads.BeadsRoutes
import com.letta.mobile.data.controller.node.iroh.records.FileWrapperRecordStore
import com.letta.mobile.data.runtime.TurnIdentityLedger
import com.letta.mobile.data.subagents.AppServerProcessIdentity
import com.letta.mobile.data.subagents.DurableSubagentRegistry
import com.letta.mobile.data.subagents.FileSubagentRegistryStore
import com.letta.mobile.data.subagents.InMemorySubagentRegistryStore
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.attr
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.nio.file.Paths

/**
 * The inputs of a node's admin_rpc router. [build] assembles the router; every null input leaves
 * its methods unregistered or failing closed.
 *
 * @property controller the runtime controller the router's runtime and agent methods act through.
 * @property subagentRegistrySource replaces the controller-native subagent registry when set.
 * @property subagentRegistryFile backs the controller-native registry with this JSON file, so chips
 *   survive a restart and are reconciled against live state on the next authoritative snapshot.
 * @property appServerProcessIdentity host-local App Server process proof; unknown for remote hosts.
 * @property pairingService the paired-peer and invite stores for `pair.*`; null when pairing is off.
 * @property nativeClient the App Server client native reads and the event-driven catalogs use;
 *   null when the node has no App Server.
 * @property vibesyncBaseUrl the VibeSync service `project.*` methods call.
 * @property beadsBaseUrl the `bd serve` URL for `beads.*` methods; blank or null registers none.
 * @property beadsTokenFile the bearer token file for [beadsBaseUrl].
 * @property localBackendDir the letta-code on-disk backend root. Admin reads the App Server has no
 *   command for (run/step history, agent context, memory blocks) are served read-only from it.
 * @property skillsDir the letta-code skills root. letta-code advertises no skill enumeration on the
 *   wire, so `skill.list` is answered from this directory, else `LETTA_SKILLS_DIR`, else
 *   `<LETTA_HOME>/skills`.
 * @property eventScope where the catalogs and the subagent registry follow App Server events.
 * @property agentChanges pushes `agent_updated` to connected clients after agent writes.
 * @property conversationChanges pushes conversation changes to connected clients after writes.
 * @property modelExposureFile persisted model exposure decisions; null keeps them in memory.
 * @property runRecordsFile run/lease JSON file; defaults beside [modelExposureFile], else memory-only.
 * @property turnIdentity serves stream-joined ids on `message.list`; null serves rows as stored.
 * @property subagentPublisher builds the publisher that pushes the controller-native registry to
 *   conversation viewers; the router then advertises [ControllerSubagentRegistrySource.PUSH_CAPABILITY].
 * @property pairingQr QR minting identity for `pair.invite.create`; null mints no QR.
 * @property permissionModes pushes confirmed permission-mode changes to conversation viewers.
 * @property pluginRoutes the plugin host's `plugins.*` handlers; null when plugins are off.
 * @property canvasMountRoutes the canvas mount methods; null keeps them unregistered.
 * @property voiceRoutes the voice endpoint routes; null registers none.
 * @property pinsRoutes the pinned-agents routes; null registers none.
 * @property shellStateRoutes the shared shell-state document routes; null registers none.
 * @property serviceCredentialRoutes the non-LLM service credential routes; null registers none.
 * @property feedRoutes `feed.subscribe` over the node's durable turn journal; null registers none.
 */
@Suppress("LongParameterList")
class NodeAdminRouterSpec(
    val controller: DefaultAppServerController,
    val subagentRegistrySource: SubagentRegistrySource? = null,
    val subagentRegistryFile: String? = null,
    val appServerProcessIdentity: AppServerProcessIdentity = AppServerProcessIdentity { null },
    val pairingService: IrohPairingService? = null,
    val nativeClient: AppServerClient? = null,
    val vibesyncBaseUrl: String? = null,
    val beadsBaseUrl: String? = null,
    val beadsTokenFile: String? = null,
    val localBackendDir: String? = null,
    val skillsDir: String? = null,
    val eventScope: CoroutineScope? = null,
    val agentChanges: AgentChangeNotifier? = null,
    val conversationChanges: ConversationChangeNotifier? = null,
    val modelExposureFile: String? = null,
    val runRecordsFile: String? = null,
    val turnIdentity: TurnIdentityLedger? = null,
    val subagentPublisher: ((ControllerSubagentRegistrySource) -> SubagentRegistryPublisher)? = null,
    val pairingQr: PairingQrConfig? = null,
    val permissionModes: PermissionModePublisher? = null,
    val pluginRoutes: AdminRpcRoutes? = null,
    val canvasMountRoutes: AdminRpcRoutes? = null,
    val voiceRoutes: AdminRpcRoutes? = null,
    val pinsRoutes: AdminRpcRoutes? = null,
    val shellStateRoutes: AdminRpcRoutes? = null,
    val serviceCredentialRoutes: AdminRpcRoutes? = null,
    val feedRoutes: AdminRpcRoutes? = null,
) {
    /** Assembles the router, starting the catalogs and registry on [eventScope] when there is an App Server. */
    fun build(): AdminRpcRouter {
        val skillsCatalog = hydratedSkillsCatalog()
        val nativeSource = if (subagentRegistrySource == null) nativeSubagentSource() else null
        val subagentSource = subagentRegistrySource ?: requireNotNull(nativeSource)
        // Slash commands ride update_device_status on the runtime lane.
        val slashCommands = nativeClient?.let { NativeSlashCommandCatalog() }
        if (nativeClient != null && eventScope != null) {
            skillsCatalog.start(eventScope, nativeClient.events)
            slashCommands?.start(eventScope, nativeClient.events)
        }
        return AdminRpcRegistry.buildRouter(
            services = hostServices(subagentSource),
            pairing = AdminRpcPairing(pairingService = pairingService, pairingQr = pairingQr),
            native = AdminRpcNativeSources(
                nativeClient = nativeClient,
                localBackendDir = localBackendDir,
                skillsListing = skillsCatalog.asListingSource(),
                slashCommands = slashCommands,
                modelExposureFile = modelExposureFile,
                wrapperRecords = wrapperRecords(),
            ),
            notifications = AdminRpcNotifications(
                agentChanges = agentChanges,
                conversationChanges = conversationChanges,
                turnIdentity = turnIdentity,
                permissionModes = permissionModes,
            ),
        ).also { router ->
            if (nativeSource?.changeListener != null) router.featureCapabilities += ControllerSubagentRegistrySource.PUSH_CAPABILITY
            // Each route group registers all of its methods or none, and advertises its own capability.
            voiceRoutes?.register(router)
            pinsRoutes?.register(router)
            shellStateRoutes?.register(router)
            serviceCredentialRoutes?.register(router)
            feedRoutes?.register(router)
        }
    }

    /**
     * Every skill.list re-reads the root (a symlink is the controllable unit and can change on disk
     * at any time); this cold-start read also logs what the host sees, so the first listing after a
     * restart is already authoritative.
     */
    private fun hydratedSkillsCatalog(): NativeSkillsCatalog {
        val resolvedSkillsDir = HostSkillsEnumerator.resolveSkillsDir(skillsDir)
        val skillsCatalog = NativeSkillsCatalog(hostEnumeration = { HostSkillsEnumerator.enumerate(resolvedSkillsDir) })
        if (skillsCatalog.refreshFromHost()) {
            Telemetry.event(
                "SkillsCatalog",
                "host.hydrated",
                "skillsDir" attr resolvedSkillsDir,
                "skills" attr skillsCatalog.snapshot().size.toString(),
            )
        } else {
            Telemetry.event("SkillsCatalog", "host.root_missing", "skillsDir" attr resolvedSkillsDir)
        }
        return skillsCatalog
    }

    private fun nativeSubagentSource(): ControllerSubagentRegistrySource {
        val subagentStore = subagentRegistryFile
            ?.let { FileSubagentRegistryStore(Paths.get(it)) }
            ?: InMemorySubagentRegistryStore()
        return ControllerSubagentRegistrySource(
            DurableSubagentRegistry(store = subagentStore, processIdentity = appServerProcessIdentity),
        ).also { source ->
            subagentPublisher?.let { build -> source.changeListener = build(source) }
            if (nativeClient != null && eventScope != null) {
                source.start(eventScope, nativeClient.events)
            }
        }
    }

    private fun hostServices(subagentSource: SubagentRegistrySource) = AdminRpcHostServices(
        controller = controller,
        subagentRegistrySource = subagentSource,
        vibesyncBaseUrl = vibesyncBaseUrl,
        beadsBaseUrl = beadsBaseUrl,
        beadsTokenFile = beadsTokenFile,
        beadsRoutes = beadsBaseUrl?.takeIf { it.isNotBlank() }?.let { url ->
            val client = BeadsRouteClient(url, beadsTokenFile)
            AdminRpcRoutes { router ->
                (BeadsRoutes.reads + BeadsRoutes.writes).forEach { (method, operation) ->
                    router.register(method) { params -> client.execute(operation, params) }
                }
            }
        },
        pluginRoutes = pluginRoutes,
        canvasMountRoutes = canvasMountRoutes,
    )

    private fun wrapperRecords() = FileWrapperRecordStore(
        runRecordsFile?.let { Paths.get(it) }
            ?: modelExposureFile?.let { modelFile ->
                File(File(modelFile).absoluteFile.parentFile, "run-records.json").toPath()
            },
        warn = { Telemetry.event("WrapperRecords", "store.corrupt", level = Telemetry.Level.WARN) },
    )
}
