package com.letta.mobile.data.workspace.relay

import com.letta.mobile.data.transport.appserver.AppServerClient

/**
 * letta-mobile-bzvro.37: which client the agent-workspace sources (MemFS, secrets, workspace files)
 * send their commands through. A direct App Server session wins; otherwise the Iroh host's relay
 * ([WorkspaceRelayClient]); with neither, every request fails with [NO_CONNECTION], which the
 * pages show in place of their content.
 *
 * Both are read per request, so a reconnect or a switch between direct and Iroh is followed.
 */
class WorkspaceClientRoute(
    private val direct: () -> AppServerClient? = { null },
    private val relay: () -> WorkspaceRelayCall? = { null },
) {
    fun client(): AppServerClient =
        direct() ?: relay()?.let(::WorkspaceRelayClient) ?: throw IllegalStateException(NO_CONNECTION)

    companion object {
        const val NO_CONNECTION: String =
            "This needs a direct App Server connection: the bundled local runtime or an App Server URL. " +
                "It is not available over this connection."
    }
}
