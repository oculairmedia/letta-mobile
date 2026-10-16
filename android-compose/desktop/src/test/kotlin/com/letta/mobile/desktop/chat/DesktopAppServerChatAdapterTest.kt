package com.letta.mobile.desktop.chat

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.runtime.host.LocalAppServerLease
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking

/**
 * letta-mobile-ckmub.4: the desktop has one backend path. Every config resolves to an App Server
 * address and is served by [DesktopAppServerChatGateway]; nothing falls back to a Letta REST server.
 */
class DesktopAppServerChatAdapterTest {

    @Test
    fun remoteHostDialsItsOwnAppServerAddress() = runBlocking {
        val runtime = FakeLocalRuntime()
        val (gateway, received) = dialRecording(LettaConfig("remote", LettaConfig.Mode.SELF_HOSTED, " ws://remote:4500 "), runtime)

        assertEquals("ws://remote:4500", received?.serverUrl)
        assertEquals(0, runtime.acquireCount)
        assertIs<DesktopAppServerChatGateway>(gateway).close()
        assertEquals(0, runtime.closeCount)
    }

    @Test
    fun remoteHostWithoutAnAddressFailsInsteadOfFallingBackToAnotherServer() = runBlocking {
        val config = LettaConfig("remote", LettaConfig.Mode.SELF_HOSTED, "")

        assertFailsWith<IllegalArgumentException> {
            createDefaultDesktopChatGateway(
                config = config,
                appServerConfig = DesktopAppServerRuntimeConfig(),
                appServerGatewayFactory = DesktopAppServerChatGatewayBuilder(),
                acquireLocalRuntime = FakeLocalRuntime()::acquire,
            )
        }
        Unit
    }

    @Test
    fun localModeStartsBundledRuntimeAndPassesItsUrlToFactory() = runBlocking {
        val runtime = FakeLocalRuntime(url = "ws://127.0.0.1:43123")
        val (gateway, received) = dialRecording(LettaConfig("local", LettaConfig.Mode.LOCAL, "local://bundled"), runtime)

        assertEquals(1, runtime.acquireCount)
        assertEquals(0, runtime.closeCount)
        assertEquals("ws://127.0.0.1:43123", received?.serverUrl)
        assertEquals("lease-token", received?.bearerToken, "the local dial carries the runtime's capability token")
        assertIs<AutoCloseable>(gateway).close()
        assertEquals(1, runtime.closeCount)
    }

    /**
     * letta-mobile-9v9nu regression: a LOCAL config that still carries a stale `iroh://` serverUrl
     * (leftover from a prior remote session) must still take the local-runtime path.
     */
    @Test
    fun localModeStartsBundledRuntimeEvenWithStaleIrohServerUrlOnConfig() = runBlocking {
        val runtime = FakeLocalRuntime(url = "ws://127.0.0.1:43125")
        val staleLocalConfig = LettaConfig(
            id = "desktop-361c792e",
            mode = LettaConfig.Mode.LOCAL,
            serverUrl = "iroh://330415cc15c111596d0b18b730441be7717b92822b7517ccc09f92bb3946fa7f@192.168.50.90:4501",
        )

        val (gateway, received) = dialRecording(staleLocalConfig, runtime)

        assertEquals(1, runtime.acquireCount)
        assertEquals("ws://127.0.0.1:43125", received?.serverUrl)
        assertIs<AutoCloseable>(gateway).close()
        assertEquals(1, runtime.closeCount)
    }

    @Test
    fun explicitOverrideWinsAndDoesNotAcquireBundledRuntime() = runBlocking {
        val runtime = FakeLocalRuntime()
        val (gateway, received) = dialRecording(
            config = LettaConfig("local", LettaConfig.Mode.LOCAL, ""),
            runtime = runtime,
            appServerConfig = DesktopAppServerRuntimeConfig(serverUrl = "ws://override:4500"),
        )

        assertEquals("ws://override:4500", received?.serverUrl)
        assertEquals(0, runtime.acquireCount)
        assertIs<DesktopAppServerChatGateway>(gateway).close()
    }

    @Test
    fun factoryFailureReleasesTheBundledRuntime() = runBlocking {
        val runtime = FakeLocalRuntime()

        assertFailsWith<IllegalStateException> {
            createDefaultDesktopChatGateway(
                config = LettaConfig("local", LettaConfig.Mode.LOCAL, ""),
                appServerConfig = DesktopAppServerRuntimeConfig(),
                appServerGatewayFactory = DesktopAppServerChatGatewayFactory { _, _ -> error("dial failed") },
                acquireLocalRuntime = runtime::acquire,
            )
        }
        assertEquals(1, runtime.closeCount)
    }

    @Test
    fun desktopAppServerRuntimeConfig_fromProcess_readsSystemProperty() {
        try {
            System.setProperty(DesktopAppServerRuntimeConfig.SERVER_URL_PROPERTY, "ws://test:4500")

            assertEquals("ws://test:4500", DesktopAppServerRuntimeConfig.fromProcess().serverUrl)
        } finally {
            System.clearProperty(DesktopAppServerRuntimeConfig.SERVER_URL_PROPERTY)
        }
    }

    @Test
    fun desktopAppServerRuntimeConfig_fromProcess_hasNoOverrideByDefault() {
        assertNull(DesktopAppServerRuntimeConfig.fromProcess().serverUrl)
    }

    @Test
    fun desktopAppServerControllerGatewayFactory_requiresServerUrl() = runBlocking {
        val config = LettaConfig(id = "test-config", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "")

        assertFailsWith<IllegalArgumentException> {
            DesktopAppServerChatGatewayBuilder().create(config, DesktopAppServerRuntimeConfig(serverUrl = null))
        }
        Unit
    }

    private fun recordingFactory(onCreate: (DesktopAppServerRuntimeConfig) -> Unit) =
        DesktopAppServerChatGatewayFactory { _, appServer ->
            onCreate(appServer)
            DesktopAppServerChatGateway(turnEngine = IdleTurnEngine, client = IdleAppServerClient)
        }

    private object IdleTurnEngine : TurnEngine {
        override fun runTurn(command: TurnCommand): Flow<RuntimeEventDraft> = emptyFlow()
    }

    private object IdleAppServerClient : AppServerClient {
        override val events: Flow<AppServerReceivedFrame> = emptyFlow()

        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart): AppServerInboundFrame.RuntimeStartResponse =
            unused()

        override suspend fun input(command: AppServerCommand.Input): Unit = unused()

        override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse = unused()

        override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse =
            unused()

        override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse =
            unused()

        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse): Unit = unused()

        private fun unused(): Nothing = error("not used by the adapter tests")
    }

    /** Builds the default gateway over [runtime], returning it and the config the factory received. */
    private suspend fun dialRecording(
        config: LettaConfig,
        runtime: FakeLocalRuntime,
        appServerConfig: DesktopAppServerRuntimeConfig = DesktopAppServerRuntimeConfig(),
    ): Pair<DesktopChatGateway, DesktopAppServerRuntimeConfig?> {
        var received: DesktopAppServerRuntimeConfig? = null
        val gateway = createDefaultDesktopChatGateway(config, appServerConfig, recordingFactory { received = it }, runtime::acquire)
        return gateway to received
    }

    private class FakeLocalRuntime(
        private val url: String = "ws://127.0.0.1:49920",
    ) {
        var acquireCount = 0
        var closeCount = 0

        fun acquire(): LocalAppServerLease {
            acquireCount += 1
            return object : LocalAppServerLease {
                override val serverUrl: String = url
                override val bearerToken: String = "lease-token"
                private var closed = false
                override fun close() {
                    if (!closed) {
                        closed = true
                        closeCount += 1
                    }
                }
            }
        }
    }
}
