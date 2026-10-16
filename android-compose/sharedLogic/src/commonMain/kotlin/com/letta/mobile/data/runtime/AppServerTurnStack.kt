package com.letta.mobile.data.runtime

import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.fanout.AppServerRuntimeEventRouter
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.appserver.AppServerRuntimeStartClientInfo
import com.letta.mobile.runtime.TurnCommand
import kotlinx.coroutines.CoroutineScope

/** letta-mobile-by4vm.90: what a host varies in its App Server turn stack; every default is the engine's own. */
class AppServerTurnStackSpec(
    val clientInfo: AppServerRuntimeStartClientInfo = AppServerTurnEngine.DEFAULT_CLIENT_INFO,
    val permissionMode: AppServerPermissionMode = RuntimePermissionDefaults.DEFAULT_MODE,
    val permissionModeProvider: (TurnCommand) -> AppServerPermissionMode = { permissionMode },
    val onPermissionModeInForce: (AppServerRuntimeScope, AppServerPermissionMode) -> AppServerPermissionMode? = { _, _ -> null },
    val requestIdFactory: () -> String = AppServerTurnEngine.defaultRequestId,
    val externalToolRegistry: ExternalToolRegistry? = null,
)

/** letta-mobile-by4vm.90: the client -> router -> engine stack of desktop, web and Android Iroh: attaches, then [turnEngine]. */
fun AppServerRuntimeEventRouter.attachTurnStack(
    client: AppServerClient,
    scope: CoroutineScope,
    spec: AppServerTurnStackSpec = AppServerTurnStackSpec(),
): AppServerTurnEngine {
    attach(scope, client.events)
    return turnEngine(client, scope, spec)
}

/** The engine over this attached router, on its connection generation, re-answering approval replays (qygvv.5). */
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
).also { engine -> engine.answerApprovalReplaysFrom(this, scope) }
