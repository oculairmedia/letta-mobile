package com.letta.mobile.data.controller.reconnect

import com.letta.mobile.data.controller.fanout.AppServerRuntimeEventRouter
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope

/**
 * Binds a [ReconnectingAppServerClient] to a router and turn engine that
 * outlive the socket, so an in-flight turn survives a brief disconnect instead of failing on it.
 *
 *  - [onDisconnected]: the router is marked interrupted. Subscribers stay registered and each turn
 *    starts its grace window; [onSessionLost] drops generation-local state (the engine's cached
 *    runtime scopes, so the next turn re-issues runtime_start on the new socket).
 *  - [onRecovered]: [reattach] runs on the new generation for every runtime still being watched
 *    (runtime_start attaches it to this socket; throwing fails the recovery and the owner retries),
 *    then the router is restored, which resyncs those runtimes to recover what streamed in the gap.
 *  - [onGaveUp]: nothing will reconnect, so the router is detached and every waiting turn fails now.
 */
class SessionSurvivingReconnectListener(
    private val router: AppServerRuntimeEventRouter,
    private val onSessionLost: suspend () -> Unit = {},
    private val reattach: suspend (client: AppServerClient, runtimes: List<AppServerRuntimeScope>) -> Unit = { _, _ -> },
) : ReconnectingClientListener {
    override suspend fun onDisconnected(reason: String?) {
        router.interruptSession(reason)
        onSessionLost()
    }

    override suspend fun onRecovered(client: AppServerClient) {
        reattach(client, router.watchedRuntimes())
        router.restoreSession()
    }

    override suspend fun onGaveUp(reason: String?) {
        router.detach()
    }
}

/**
 * Re-issues `runtime_start` for each of [runtimes] on [this] generation (the App Server routes a
 * runtime's events to the socket that attached it). A refused start throws, failing the recovery.
 */
suspend fun AppServerClient.reattachRuntimes(
    runtimes: List<AppServerRuntimeScope>,
    startCommand: (AppServerRuntimeScope) -> AppServerCommand.RuntimeStart,
) {
    runtimes.forEach { runtime ->
        val response = runtimeStart(startCommand(runtime))
        check(response.success) {
            "runtime_start refused while reattaching ${runtime.agentId}/${runtime.conversationId}: ${response.error}"
        }
    }
}
