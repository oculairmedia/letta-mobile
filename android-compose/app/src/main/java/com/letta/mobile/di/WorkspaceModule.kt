package com.letta.mobile.di

import com.letta.mobile.data.memory.memfs.AppServerMemfsSource
import com.letta.mobile.data.memory.memfs.MemfsSource
import com.letta.mobile.data.secrets.AgentSecretsFeature
import com.letta.mobile.data.secrets.AgentSecretsSource
import com.letta.mobile.data.secrets.AppServerAgentSecretsSource
import com.letta.mobile.data.session.SessionManager
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import com.letta.mobile.data.workspace.AppServerWorkspaceFileSource
import com.letta.mobile.data.workspace.WorkspaceFileSource
import com.letta.mobile.data.workspace.relay.WorkspaceClientRoute
import com.letta.mobile.data.workspace.relay.WorkspaceRelayCall
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.UUID
import javax.inject.Singleton
import kotlinx.coroutines.flow.emptyFlow

/**
 * letta-mobile-bzvro.37: the agent-workspace sources (MemFS browser, file mentions and viewer,
 * secrets vault) over the session's Iroh connection, whose host relays them to its App Server.
 * The sources and their page controllers live in sharedLogic, the same ones desktop binds; this
 * module only binds them. Nothing here persists a value: the secrets vault stays server-side and
 * behind [AgentSecretsFeature.Android] (off).
 */
@Module
@InstallIn(SingletonComponent::class)
object WorkspaceModule {
    @Provides
    @Singleton
    fun provideWorkspaceClientRoute(transport: IChannelTransport, sessionManager: SessionManager): WorkspaceClientRoute =
        // Only an Iroh session has a host that relays; on any other the pages show NO_CONNECTION
        // rather than the transport's raw "admin_rpc is not supported".
        WorkspaceClientRoute(
            relay = {
                if (sessionManager.current.channelTransport is IrohChannelTransport) {
                    WorkspaceRelayCall.overTransport { transport }
                } else {
                    null
                }
            },
        )

    /** The relay pushes no `memory_updated`; the page refreshes on demand. */
    @Provides
    fun provideMemfsSource(route: WorkspaceClientRoute): MemfsSource =
        AppServerMemfsSource(client = route::client, events = emptyFlow(), requestId = ::requestId)

    @Provides
    fun provideWorkspaceFileSource(route: WorkspaceClientRoute): WorkspaceFileSource =
        AppServerWorkspaceFileSource(client = route::client, requestId = ::requestId)

    @Provides
    fun provideAgentSecretsSource(route: WorkspaceClientRoute): AgentSecretsSource =
        AppServerAgentSecretsSource(client = route::client, requestId = ::requestId)

    @Provides
    fun provideAgentSecretsFeature(): AgentSecretsFeature = AgentSecretsFeature.Android

    private fun requestId(operation: String): String = "android-$operation-${UUID.randomUUID()}"
}
