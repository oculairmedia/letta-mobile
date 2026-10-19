package com.letta.mobile.data.runtime

import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.fanout.AppServerRuntimeEventRouter
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.appserver.AppServerRuntimeStartClientInfo
import com.letta.mobile.runtime.TurnCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What a host varies in its App Server turn stack; every default is the engine's own. */
data class AppServerTurnStackSpec(
    val clientInfo: AppServerRuntimeStartClientInfo = AppServerRuntimeStartClientInfo(name = "letta-mobile", version = "0.1"),
    val permissionMode: AppServerPermissionMode = RuntimePermissionDefaults.DEFAULT_MODE,
    val permissionModeProvider: (TurnCommand) -> AppServerPermissionMode = { permissionMode },
    val onPermissionModeInForce: (AppServerRuntimeScope, AppServerPermissionMode) -> AppServerPermissionMode? = { _, _ -> null },
    val requestIdFactory: () -> String = AppServerTurnEngine.defaultRequestId,
    val externalToolRegistry: ExternalToolRegistry? = null,
    /** False leaves every approval to the person (a client that must never answer one). */
    val autoAllowApprovals: Boolean = true,
    /** Durable terminals and client preferences, sent only to a host that supports them. */
    val durableDelivery: DurableTurnDelivery = DurableTurnDelivery.OFF,
    /** The saved default cwd a conversation with no working directory yet starts in. */
    val agentDefaults: AgentDefaultsRepository? = null,
)

/**
 *  (moved from desktop): a stack whose runtimes start in [modes]'
 * per-conversation choice, else [fallback], and report the mode in force back into it, so the
 * composer's mode chip and `runtime_start` agree. Null [modes] runs every runtime in [fallback].
 * Baking the mode in lets ensureRuntime's one runtime_start carry it.
 */
fun permissionGovernedTurnStackSpec(
    modes: PermissionModeRegistry?,
    clientInfo: AppServerRuntimeStartClientInfo = AppServerTurnStackSpec().clientInfo,
    fallback: AppServerPermissionMode = RuntimePermissionDefaults.DEFAULT_MODE,
    requestIdFactory: () -> String = AppServerTurnEngine.defaultRequestId,
    externalToolRegistry: ExternalToolRegistry? = null,
    durableDelivery: DurableTurnDelivery = DurableTurnDelivery.OFF,
) = AppServerTurnStackSpec(
    clientInfo = clientInfo,
    permissionMode = fallback,
    permissionModeProvider = { command ->
        modes?.modeFor(AppServerRuntimeScope(command.agentId.value, command.conversationId.value)) ?: fallback
    },
    onPermissionModeInForce = { runtime, mode -> modes?.observed(runtime, mode) },
    requestIdFactory = requestIdFactory,
    externalToolRegistry = externalToolRegistry,
    durableDelivery = durableDelivery,
)

/** The client -> router -> engine stack of desktop, web and Android Iroh: attaches, then [turnEngine]. */
fun AppServerRuntimeEventRouter.attachTurnStack(
    client: AppServerClient,
    scope: CoroutineScope,
    spec: AppServerTurnStackSpec = AppServerTurnStackSpec(),
): AppServerTurnEngine {
    attach(scope, client.events)
    return turnEngine(client, scope, spec)
}

/**
 * The engine over this attached router, on its connection generation, re-answering approval replays
 *  and, for a durable terminal consumer, acking the terminals no turn is collecting.
 */
fun AppServerRuntimeEventRouter.turnEngine(
    client: AppServerClient,
    scope: CoroutineScope,
    spec: AppServerTurnStackSpec,
): AppServerTurnEngine = AppServerTurnEngine(
    client = client,
    clientInfo = spec.clientInfo,
    permissionMode = spec.permissionMode,
    permissionModeProvider = spec.permissionModeProvider,
    onPermissionModeInForce = spec.onPermissionModeInForce,
    requestIdFactory = spec.requestIdFactory,
    externalToolRegistry = spec.externalToolRegistry,
    eventRouter = this,
    connectionGenerationProvider = connectionGenerationProvider,
    autoAllowApprovals = spec.autoAllowApprovals,
    durableDelivery = spec.durableDelivery,
    agentDefaults = spec.agentDefaults,
).also { engine ->
    engine.answerApprovalReplaysFrom(this, scope)
    engine.acknowledgeTerminalsOutsideTurnsFrom(client, scope)
}

/** Feeds every frame of [client] to the between-turns terminal ack; a no-op without a consumer id. */
internal fun AppServerTurnEngine.acknowledgeTerminalsOutsideTurnsFrom(client: AppServerClient, scope: CoroutineScope) {
    if (!keepsDurableTerminals) return
    scope.launch { client.events.collect { received -> acknowledgeTerminalOutsideTurns(received) } }
}
