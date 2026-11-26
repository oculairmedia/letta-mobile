package com.letta.mobile.data.node

import com.letta.mobile.data.controller.DefaultAppServerController
import com.letta.mobile.data.controller.channels.ChannelAccountCache
import com.letta.mobile.data.controller.channels.ChannelRestoreCoordinator
import com.letta.mobile.data.controller.channels.InMemoryChannelAccountCache
import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.reconnect.AppServerClientGeneration
import com.letta.mobile.data.controller.reconnect.ReconnectCoordinator
import com.letta.mobile.data.controller.reconnect.ReconnectingAppServerClient
import com.letta.mobile.data.controller.reconnect.ReconnectingClientListener
import com.letta.mobile.data.controller.reconnect.ReconnectingClientState
import com.letta.mobile.data.controller.registry.InMemoryRuntimeRegistry
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerEndpoint
import com.letta.mobile.data.transport.appserver.DefaultAppServerClient
import com.letta.mobile.data.transport.appserver.DualLaneAppServerClient
import com.letta.mobile.data.transport.appserver.KtorAppServerWebSocketTransport
import io.ktor.client.HttpClient
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * The node's controller and the App Server client it relays through.
 *
 * @property controller the runtime controller the Iroh endpoint serves.
 * @property client the App Server client admin_rpc and the tools socket use; null when the node
 *   runs without an App Server (Iroh-only stub mode).
 */
class NodeAppServerConnection(
    val controller: DefaultAppServerController,
    val client: AppServerClient?,
)

/**
 * Connects a node to its App Server: two reconnecting lanes (runtime and management) over fresh
 * WebSocket generations, and the recovery sequence each new generation runs.
 */
internal class NodeAppServerConnector(
    private val ports: NodePorts,
    private val requestTimeoutMs: Long,
    private val scope: CoroutineScope,
) {
    /**
     * One cache of the last-known enabled channel accounts for the node's lifetime, so every
     * reconnect can re-wire channel ingress in its first round trip instead of after a full
     * enumeration. Held here because a fresh restore coordinator is built per generation.
     */
    private val channelAccountCache = InMemoryChannelAccountCache()

    fun connect(endpoint: AppServerEndpoint?, tools: ExternalToolRegistry): NodeAppServerConnection =
        if (endpoint != null) live(endpoint, tools) else stub()

    private fun live(endpoint: AppServerEndpoint, tools: ExternalToolRegistry): NodeAppServerConnection {
        val httpClient = ports.httpClients.create(requestTimeoutMs)
        val runtimeRegistry = InMemoryRuntimeRegistry()
        var controllerRef: DefaultAppServerController? = null
        var coordinatorRef: ReconnectCoordinator? = null
        val adminClient = ReconnectingAppServerClient(
            connect = { mintGeneration(httpClient, endpoint) },
            telemetryComponent = "AppServerAdminReconnect",
        )
        val runtimeClient = ReconnectingAppServerClient(
            connect = { mintGeneration(httpClient, endpoint) },
            listener = NodeRecoveryListener(
                log = ports.log,
                controller = { controllerRef },
                coordinator = { coordinatorRef },
                adminState = adminClient.state,
                channelAccountCache = channelAccountCache,
                restoreScope = scope,
            ),
        )
        val routedClient = DualLaneAppServerClient(runtime = runtimeClient, admin = adminClient)
        val controller = DefaultAppServerController(
            client = routedClient,
            runtimeRegistry = runtimeRegistry,
            // Every runtime_start advertises the host's tools in external_tools.
            externalToolRegistry = tools,
            // An upstream socket blip no longer fails the turns the App Server keeps running;
            // they wait out the reconnect grace window instead.
            turnsSurviveReconnect = true,
        )
        controllerRef = controller
        coordinatorRef = ReconnectCoordinator(controller, runtimeRegistry)
        adminClient.start(scope)
        runtimeClient.start(scope)
        return NodeAppServerConnection(controller, routedClient)
    }

    private fun stub(): NodeAppServerConnection {
        val transport = KtorAppServerWebSocketTransport(
            httpClient = ports.httpClients.create(null),
            baseUrl = "ws://127.0.0.1:0",
            scope = scope,
            bearerToken = null,
        )
        val client = DefaultAppServerClient(transport, requestTimeoutMs = requestTimeoutMs)
        return NodeAppServerConnection(DefaultAppServerController(client), null)
    }

    /**
     * Mint one connection generation: a fresh WS transport + client on a job child of [scope],
     * closable independently of its successors.
     */
    @Suppress("NoDetachedCoroutineLifecycle")
    private fun mintGeneration(httpClient: HttpClient, endpoint: AppServerEndpoint): AppServerClientGeneration {
        val generationJob = Job(scope.coroutineContext.job)
        val generationScope = CoroutineScope(scope.coroutineContext + generationJob)
        val transport = KtorAppServerWebSocketTransport(
            httpClient = httpClient,
            baseUrl = endpoint.address,
            scope = generationScope,
            bearerToken = endpoint.bearerToken,
        )
        return AppServerClientGeneration(
            client = DefaultAppServerClient(
                transport,
                requestTimeoutMs = requestTimeoutMs,
                parentScope = generationScope,
                // This node's own socket to its local App Server, which stamps the runtime scopes.
                vouchesForRuntimeScope = true,
            ),
            connectionState = transport.connectionState,
            close = { reason -> generationJob.cancel(CancellationException(reason)) },
        )
    }
}

/**
 * Post-connect recovery sequence, run on every generation-ready: reattach runtimes, start the
 * channel-account restore for this generation, then mark connected.
 *
 * ## Channel restore does not hold readiness
 *
 * The restore is launched on [restoreScope] and recovery returns without waiting for it, so the
 * supervisor reports `generation.ready` as soon as runtimes are reattached and the management
 * lane is usable. An App Server started as `letta server --listen` never answers a `channel_*`
 * command at all: its socket router only forwards them to a channel service that this mode never
 * starts, and drops them without a reply otherwise. Waiting on the restore therefore delayed
 * readiness on every boot by the full request timeout and bought nothing.
 *
 * What the old ordering protected, and why it still holds:
 * - The restore runs on THIS generation's client (the one handed to [onRecovered]), so a repeat
 *   `channel_start` still re-wires ingress to the live socket. That client is the generation's
 *   own, not the reconnecting facade, so its calls are admitted whatever the supervisor's state.
 * - Nothing in recovery reads the restore's outcome: it never failed recovery, and a failure
 *   escaping it is logged by name and stops only that restore, never the node scope.
 * - One restore at a time: a new generation cancels and waits out the previous generation's
 *   restore before starting its own, so two passes never write the shared account cache at once.
 * - A dead generation's restore stops with it: [onDisconnected] cancels it, and so does
 *   [onGaveUp], since a terminal close reaches give-up with no disconnect callback first.
 *
 * The callbacks are invoked from the reconnecting client's single supervise loop, never
 * concurrently, and the one-restore-at-a-time guarantee relies on that.
 *
 * The controller and coordinator are read through suppliers because both are constructed
 * after the reconnecting client they are wired into.
 *
 * @param channelAccountCache one cache of the last-known enabled channel accounts for the node's
 *   lifetime, so every reconnect can re-wire channel ingress in its first round trip instead of
 *   after a full enumeration. A fresh restore coordinator is built per generation.
 * @param restoreScope the node's lifecycle scope; the channel restore runs here.
 */
internal class NodeRecoveryListener(
    private val log: NodeLog,
    private val controller: () -> DefaultAppServerController?,
    private val coordinator: () -> ReconnectCoordinator?,
    private val adminState: Flow<ReconnectingClientState>,
    private val channelAccountCache: ChannelAccountCache,
    private val restoreScope: CoroutineScope,
) : ReconnectingClientListener {
    /** The current generation's channel restore, if one is still running. */
    private val channelRestore = atomic<Job?>(null)

    override suspend fun onDisconnected(reason: String?) {
        log.info("App Server connection lost: ${reason ?: "unknown"}")
        // The restore was bound to the generation that just failed; its socket is gone.
        channelRestore.getAndSet(null)?.cancel()
        controller()?.onTransportDisconnected(reason)
    }

    override suspend fun onRecovered(client: AppServerClient) {
        awaitManagementLaneReady(adminState)
        val result = coordinator()?.reconnect()
        result?.errors?.forEach {
            log.error("reattach failed: ${it.message}")
        }
        // Restore channel accounts on THIS generation's client. Must run on every
        // generation-ready (not just the first): a repeat channel_start re-wires ingress to the
        // live socket, which a reconnect otherwise leaves pointed at the dead one. Launched, not
        // awaited: readiness must not wait on a command the App Server may never answer.
        launchChannelRestore(client)
        // Recovery work can take long enough for the independent management lane to roll
        // generations. Re-check at the readiness boundary so the runtime supervisor never
        // advertises a half-usable startup.
        awaitManagementLaneReady(adminState)
        controller()?.markConnected()
        log.info(
            "App Server connection recovered " +
                "(reattached runtimes: ${result?.reconnectedCount ?: 0})",
        )
    }

    override suspend fun onGaveUp(reason: String?) {
        log.error("App Server reconnect gave up: ${reason ?: "unknown"}")
        // A terminal close ends the generation with no onDisconnected first; its restore is dead too.
        channelRestore.getAndSet(null)?.cancel()
    }

    /**
     * Parent of every restore. The node scope is not a supervisor, so a restore that failed as its
     * direct child would cancel the whole node; under this supervisor a failure stops only that
     * restore and reaches [restoreFailureHandler]. Still a child of the node scope, so cancelling
     * the node cancels any restore in flight.
     */
    private val restoreSupervisor = SupervisorJob(restoreScope.coroutineContext[Job])

    private val restoreFailureHandler = CoroutineExceptionHandler { _, failure ->
        log.error("channel restore failed: ${failure::class.simpleName}: ${failure.message}")
    }

    private suspend fun launchChannelRestore(client: AppServerClient) {
        channelRestore.getAndSet(null)?.cancelAndJoin()
        channelRestore.value = restoreScope.launch(restoreSupervisor + restoreFailureHandler) {
            restoreChannels(client)
        }
    }

    /**
     * This node is the channels host: it restores enabled channel accounts on every App Server
     * connect and reconnect. Never fails recovery, since a channel outage must not block runtime
     * reattach.
     */
    private suspend fun restoreChannels(client: AppServerClient) {
        val result = ChannelRestoreCoordinator(
            client = client,
            log = { log.error(it) },
            accountCache = channelAccountCache,
        ).restore()
        // Identifiers and counts only: channel account config carries cleartext plugin
        // credentials and must never reach a log sink.
        log.info(
            "channels-host restore: channels=${result.channelIds.size} " +
                "started=${result.startedAccounts} failed=${result.failures.size}",
        )
        result.failures.forEach {
            log.error(
                "channel restore failed: " +
                    "${it.channelId ?: "-"}/${it.accountId ?: "-"} phase=${it.phase} reason=${it.reason}",
            )
        }
    }
}

/**
 * Runtime readiness is only truthful once the read lane needed by turn preflight is usable. This
 * wait runs inside runtime recovery, before its supervisor emits `generation.ready`; later admin
 * disconnects do not cancel the already-ready runtime generation.
 */
suspend fun awaitManagementLaneReady(state: Flow<ReconnectingClientState>) {
    when (val settled = state.first {
        it is ReconnectingClientState.Ready || it is ReconnectingClientState.GaveUp
    }) {
        is ReconnectingClientState.Ready -> Unit
        is ReconnectingClientState.GaveUp -> error(
            "App Server management lane gave up before runtime readiness: ${settled.reason ?: "unknown"}",
        )
        else -> error("unreachable management lane state: $settled")
    }
}
