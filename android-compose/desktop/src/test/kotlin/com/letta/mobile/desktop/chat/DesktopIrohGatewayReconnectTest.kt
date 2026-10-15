package com.letta.mobile.desktop.chat

import com.letta.mobile.data.controller.reconnect.AppServerClientGeneration
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerConnectionState
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * letta-mobile-by4vm.56: the desktop's iroh:// gateway mints its QUIC connections through the shared
 * reconnect owner, as ws:// does. A lost connection is redialled behind the same gateway and client
 * (no [DesktopChatController.retryConnection] rebuild), and its status reaches the chat controller's
 * watcher; a refused auth is reported at once instead of retried.
 */
class DesktopIrohGatewayReconnectTest {

    @Test
    fun aLostIrohConnectionIsRedialledBehindTheSameGateway() = runBlocking {
        val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val generations = FakeIrohGenerations()
        try {
            val gateway = builder(controllerScope, generations).create(remoteConfig(), DesktopAppServerRuntimeConfig(serverUrl = IROH_URL))
            assertTrue(gateway.ownsReconnect, "the iroh gateway owns its reconnect now")
            gateway.awaitState { it is ChannelTransportState.Connected }

            generations.minted.single().state.value = AppServerConnectionState.Failed(terminal = false, reason = "connection_closed")
            gateway.awaitState { it is ChannelTransportState.Disconnected && it.willReconnect }
            assertTrue(gateway.resumeSession(), "a wake cuts the backoff short instead of rebuilding the gateway")
            withTimeout(TIMEOUT) { generations.mintedCount.first { it == 2 } }
            gateway.awaitState { it is ChannelTransportState.Connected }

            assertTrue(generations.minted.first().closed, "the dead generation is closed")
            gateway.close()
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun aRefusedAuthOnTheFirstDialIsReportedAtOnce() = runBlocking {
        val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val generations = FakeIrohGenerations(
            firstState = AppServerConnectionState.Failed(terminal = true, reason = "Desktop iroh App Server auth failed: revoked"),
        )
        try {
            val failure = assertFailsWith<IllegalStateException> {
                builder(controllerScope, generations).create(remoteConfig(), DesktopAppServerRuntimeConfig(serverUrl = IROH_URL))
            }
            assertEquals("Desktop iroh App Server auth failed: revoked", failure.message)
            assertEquals(1, generations.mintedCount.value, "a refusal is terminal, never redialled")
        } finally {
            controllerScope.cancel()
        }
    }

    private fun builder(scope: CoroutineScope, generations: DesktopIrohGenerationSource) = DesktopAppServerChatGatewayBuilder(
        controllerScope = scope,
        irohIdentity = { ByteArray(32) },
        irohGenerations = { _, _, _ -> generations },
    )

    private suspend fun DesktopAppServerChatGateway.awaitState(predicate: (ChannelTransportState) -> Boolean) {
        withTimeout(TIMEOUT) { connectionState.first(predicate) }
    }

    private fun remoteConfig() = LettaConfig(
        id = "desktop-iroh-reconnect-test",
        mode = LettaConfig.Mode.SELF_HOSTED,
        serverUrl = IROH_URL,
    )

    private class FakeGeneration(initial: AppServerConnectionState) {
        val state = MutableStateFlow(initial)
        val client = IdleClient()

        @Volatile
        var closed = false
    }

    private class FakeIrohGenerations(
        private val firstState: AppServerConnectionState = AppServerConnectionState.Ready,
    ) : DesktopIrohGenerationSource {
        override val hostCapabilities: Set<String> = emptySet()
        val minted = CopyOnWriteArrayList<FakeGeneration>()
        val mintedCount = MutableStateFlow(0)

        override suspend fun mint(): AppServerClientGeneration {
            val generation = FakeGeneration(if (minted.isEmpty()) firstState else AppServerConnectionState.Ready)
            minted += generation
            mintedCount.value = minted.size
            return AppServerClientGeneration(generation.client, generation.state) { generation.closed = true }
        }
    }

    /** A generation's client: nothing is watched in these tests, so recovery issues no calls. */
    private class IdleClient : AppServerClient {
        override val events: Flow<AppServerReceivedFrame> = MutableSharedFlow()

        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart): AppServerInboundFrame.RuntimeStartResponse =
            error("runtime_start unused")
        override suspend fun input(command: AppServerCommand.Input) = Unit
        override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse = error("sync unused")
        override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse =
            error("abort unused")
        override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse =
            error("adminRpc unused")
        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = Unit
    }

    private companion object {
        const val IROH_URL = "iroh://node-under-test"
        val TIMEOUT = 5.seconds
    }
}
